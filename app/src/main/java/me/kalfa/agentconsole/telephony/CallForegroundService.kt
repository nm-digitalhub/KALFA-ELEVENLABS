package me.kalfa.agentconsole.telephony

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

// Ongoing-call foreground service. Keeps the process alive and audio-capable while a
// live agent leg is connected — either a future monitor/takeover leg (still unwired,
// see AGENTS.md), or, since docs/android-presence-and-call-ux.md §3, an inbound call
// answered via VoxIncomingCallCoordinator — and shows the mandatory ongoing
// notification so Android does not kill an active call in the background.
//
// FOREGROUND-SERVICE TYPE — v1 uses `microphone`, deliberately NOT `phoneCall`:
// `phoneCall` on Android 14+ additionally requires self-managed Telecom
// (MANAGE_OWN_CALLS + a ConnectionService) OR the default-dialer role, which this app
// does not adopt yet — that is the option-(a) ConnectionService path deferred to the
// telephony-wiring step. `microphone` matches the actual media op (two-way call audio)
// and its runtime prerequisite is RECORD_AUDIO, requested at the live-supervision
// surface (see CallAudioPermissions). The receive-only monitor leg still opens the
// audio unit; its send-isolation is enforced server-side, not by this service.
//
// KNOWN COST OF THAT CHOICE, verified 2026-08-14 against the live docs and deliberately
// NOT fixed here: RECORD_AUDIO is a while-in-use permission, so a `microphone` FGS
// cannot be created while the app is in the background — which is exactly the push-wake
// path this service is started from when a call reaches a pocketed phone. The
// high-priority-FCM exemption covers the separate background-START restriction, not this
// one. startForegroundCompat below documents it in full and now degrades instead of
// crashing. The actual fix is a manifest-side type change (`phoneCall`, whose stated
// prerequisite — a declared MANAGE_OWN_CALLS — this app already satisfies via
// core-telecom), which belongs to the manifest's owner and must land in ONE commit with
// the constant passed to startForeground here.
//
// Started/stopped by VoxIncomingCallCoordinator around an incoming call's ringing and
// connected phases (docs §3). The monitor/takeover leg described above still has no
// call site — that phase is unchanged and still OPEN. Foreground/background
// call-continuity MUST still be validated on a real device before release (a known
// release gate — see docs §3's "What could not be verified").
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
        if (!startForegroundCompat(title)) {
            // Not a foreground service and unable to become one. Stop rather than linger:
            // AOSP's ActiveServices.serviceForegroundTimeout() discards the
            // "Context.startForegroundService() did not then call
            // Service.startForeground()" ANR once the record is `destroying`, so this is
            // what keeps a refused start from turning into a second crash.
            stopSelf()
            return START_NOT_STICKY
        }
        // If the system kills us we do NOT auto-restart with a null intent — a call
        // that died should not silently resurrect a foreground notification.
        return START_NOT_STICKY
    }

    /**
     * Claims the `microphone` foreground-service type, or reports that it could not —
     * this is the one call in this file that can take the whole app down, and it has two
     * documented ways to do so on Android 14+.
     *
     * 1. No RECORD_AUDIO. "Runtime prerequisites: Request and be granted the
     *    `RECORD_AUDIO` runtime permission"
     *    (`developer.android.com/develop/background-work/services/fgs/service-types`),
     *    and "If your app doesn't fulfill all of the runtime requirements for starting a
     *    foreground service, the system throws a `SecurityException` after you call
     *    `startForeground()`" (`.../about/versions/14/changes/fgs-types-required`).
     *    Checked up front so the common, recoverable case produces a precise log line
     *    instead of a stack trace: the caller is expected to have checked already
     *    (VoxIncomingCallCoordinator does), but a permission can be revoked between that
     *    check and this service actually starting, and this file must not depend on
     *    every future caller remembering.
     * 2. Started while the app is in the background — even WITH the permission granted.
     *    RECORD_AUDIO is a while-in-use permission: "you cannot create a `microphone`
     *    foreground service while your app is in the background ... with a few
     *    exceptions", and a high-priority FCM message is NOT among those while-in-use
     *    exceptions (it exempts only the separate background-START restriction). That is
     *    precisely this app's push-wake path, so this is a live crash, not a hypothetical
     *    one — see the note sent to telecom-owner about `phoneCall`/`shortService`, which
     *    is the real fix and is not this file's to make.
     *
     * The RECORD_AUDIO pre-check belongs to the `microphone` type specifically. If the
     * declared type ever changes it must move with it — `phoneCall`'s runtime
     * prerequisite is a declared MANAGE_OWN_CALLS or the dialer role, not a granted
     * microphone permission — and the constant below must change in the SAME commit as
     * the manifest, since the type passed here must be one the manifest declares.
     *
     * Notification building is inside the guard too: it is ordinary code that can throw,
     * and a throw between `startForegroundService()` and `startForeground()` is the
     * did-not-start-in-time crash on top of whatever actually failed.
     */
    private fun startForegroundCompat(title: String): Boolean {
        if (!hasRecordAudio()) {
            Log.w(TAG, "RECORD_AUDIO not granted — refusing to claim the microphone FGS type")
            return false
        }
        return try {
            val notification = buildNotification(title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "microphone foreground start refused: ${t::class.simpleName}: ${t.message}")
            false
        }
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(title: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(CONTENT_TEXT)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // The one in-shade control for an answered leg — see
            // CallHangupActionReceiver's kdoc and docs/android-presence-and-call-ux.md
            // §3 for why there is no dedicated connected-call screen to put this on
            // instead.
            .addAction(0, "נתק", hangupPendingIntent())
            .build()
    }

    private fun hangupPendingIntent(): PendingIntent {
        val intent = Intent(this, CallHangupActionReceiver::class.java)
            .setAction(CallHangupActionReceiver.ACTION_HANGUP)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = CHANNEL_DESC },
                )
            }
        }
    }

    companion object {
        private const val TAG = "CallFgs"
        private const val CHANNEL_ID = "kalfa_active_call"
        private const val CHANNEL_NAME = "שיחות פעילות"
        private const val CHANNEL_DESC = "התראה קבועה בזמן שיחה פעילה במסוף"
        private const val CONTENT_TEXT = "המסוף מנהל שיחה פעילה"
        private const val DEFAULT_TITLE = "שיחה פעילה"
        private const val NOTIFICATION_ID = 4711
        private const val EXTRA_TITLE = "title"

        // Called by VoxIncomingCallCoordinator both while a call is still ringing and
        // once it's answered (different titles); the future monitor/takeover phase
        // will call this too once it exists.
        fun start(context: Context, title: String = DEFAULT_TITLE) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) extends
                // ServiceStartNotAllowedException extends IllegalStateException — AOSP
                // frameworks/base/core/java/android/app/ServiceStartNotAllowedException.java.
                // Caught by superclass so no API-31-only type is named in a catch clause
                // on a minSdk-24 app.
                Log.e(TAG, "call FGS start not allowed: ${e.message}")
            } catch (e: SecurityException) {
                // Android 14 while-in-use rule for the microphone type. The docs put this
                // throw at Context.startForegroundService() ("The exception is thrown when
                // Context.startForegroundService() is called") while the Android 14
                // migration page puts it after startForeground(); both are guarded, here
                // and in startForegroundCompat, because which one fires is not worth
                // guessing and neither may crash a live call.
                Log.e(TAG, "call FGS start refused: ${e.message}")
            }
        }

        // Called when the leg disconnects.
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
