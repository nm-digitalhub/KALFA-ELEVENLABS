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
// HOW TO RE-CHECK EVERY AOSP LINE NUMBER BELOW. They index `refs/heads/main`, which
// moves, so the numbers rot while the claims stay true — and a drifted number fails
// silently: land in unrelated code and this file looks sloppy, land in plausible-looking
// wrong code and it looks authoritative. Each citation is quoted next to its distinctive
// source text, so resolve by phrase, not by number:
//
//   curl -s "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main\
//   /<path>?format=TEXT" | base64 -d | grep -n "<quoted phrase>"
//
// If a phrase no longer resolves, treat the claim as UNVERIFIED rather than assuming the
// line merely moved. AOSP changing this code is exactly the event these findings would
// need re-deriving for, and "anchor moved" and "behaviour changed" look identical from a
// stale line number. (Convention adopted from play-policy-owner's AGENTS.md §E.)
//
// FOREGROUND-SERVICE TYPE — phased, and the phase is the whole point. Settled against
// AOSP source on 2026-08-14 (`core/java/android/app/ForegroundServiceTypePolicy.java`),
// after a first answer that was confidently wrong in both directions:
//
//   FGS_TYPE_POLICY_MICROPHONE  — foregroundOnlyPermission = TRUE  (L460-479)
//   FGS_TYPE_POLICY_PHONE_CALL  — foregroundOnlyPermission = FALSE (L340-355),
//                                 anyOf{ RegularPermission(MANAGE_OWN_CALLS),
//                                        RolePermission(ROLE_DIALER) }
//
// That one flag is the mechanism behind both halves:
//
//  * `microphone` CANNOT be claimed while the app is in the background — which is
//    exactly the push-wake path a call arrives on when the phone is pocketed. The
//    high-priority-FCM exemption is on the background-START list, NOT the while-in-use
//    list ("interacting with a notification" is, which is why answering works). This
//    was a live SecurityException, not a theoretical one.
//  * `phoneCall` has no such restriction, and its prerequisite is a plain permission
//    check — a DECLARED MANAGE_OWN_CALLS satisfies it (protectionLevel="normal", merged
//    in from androidx.core:core-telecom). No PhoneAccount, no ConnectionService, no
//    CallsManager.addCall, so none of the deferred Telecom risk is pulled in here.
//
// But `phoneCall` ALONE is not the fix either, and that is the trap: OomAdjuster
// (~L2420-2427) grants PROCESS_CAPABILITY_FOREGROUND_MICROPHONE only when the microphone
// bit is set in the RUNNING type — per the framework's own comment on
// CAMERA_MICROPHONE_CAPABILITY_CHANGE_ID (@EnabledAfter targetSdk Q; this app targets
// 36). A `phoneCall`-only FGS therefore fixes the crash and leaves an answered call with
// no microphone whenever the app is not visible: the agent answers, the guest hears
// silence, and it gets blamed on the network. A visible crash traded for an invisible
// one is not a fix.
//
// Hence: `phoneCall` while RINGING (legal from a background push, no mic needed yet),
// promoted to `phoneCall|microphone` on ANSWER (legal because answering comes from a
// notification action or the visible ring activity — both while-in-use exempt — and it
// is this call that actually grants the mic capability). Re-calling startForeground on
// an already-foreground service updates its type, so the promotion is just the second
// call. The manifest declares `phoneCall|microphone` as the ceiling; ActiveServices
// (L2207) only requires the value PASSED to be a subset of it, and validates the
// prerequisites of the bits passed rather than those declared.
//
// Started/stopped by VoxIncomingCallCoordinator. As of crash-hunter's d27cbac the
// ringing-phase start is GONE from handleIncomingCall — it threw under the old type —
// so today the only start is the answer, and the ring window runs with no foreground
// service (a low-memory kill before the agent answers is possible). Phase.RINGING below
// is what makes restoring it safe; docs §3 step 4 still describes the intended shape.
// The monitor/takeover leg described above still has no call site — that phase is
// unchanged and still OPEN. Foreground/background
// call-continuity MUST still be validated on a real device before release (a known
// release gate — see docs §3's "What could not be verified").
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    // Which half of a call's life this start is for — it selects the foreground-service
    // type, and the two are not interchangeable (see the type discussion in this file's
    // header). Not a UI concern: the notification title is passed separately.
    enum class Phase { RINGING, ACTIVE }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
        // Defaults to ACTIVE, deliberately: an unlabelled start is an answered call in
        // every current call path, and ACTIVE is the phase that asks for the microphone.
        // Defaulting to RINGING instead would silently mute a call rather than fail.
        val phase = intent?.getStringExtra(EXTRA_PHASE)
            ?.let { runCatching { Phase.valueOf(it) }.getOrNull() }
            ?: Phase.ACTIVE
        if (!startForegroundCompat(title, phase)) {
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
     * Claims the foreground-service type for [phase], or reports that it could not —
     * this is the one call in this file that can take the whole app down.
     *
     * The RECORD_AUDIO pre-check applies to ACTIVE only, because only ACTIVE asks for the
     * microphone bit. "Runtime prerequisites: Request and be granted the `RECORD_AUDIO`
     * runtime permission" (`.../fgs/service-types`) and "If your app doesn't fulfill all
     * of the runtime requirements for starting a foreground service, the system throws a
     * `SecurityException` after you call `startForeground()`"
     * (`.../about/versions/14/changes/fgs-types-required`). Checked up front so the
     * common, recoverable case produces a precise log line rather than a stack trace.
     * VoxIncomingCallCoordinator checks too, but a permission can be revoked between its
     * check and this service starting, and this file must not depend on every future
     * caller remembering. Note what this check is NOT: it does not make a background
     * `microphone` start legal — that was the trap in the previous version of this guard,
     * where the permission was granted, the check passed, and the start threw anyway.
     *
     * Notification building sits inside the guard for the same reason: it is ordinary
     * code that can throw, and a throw between `startForegroundService()` and
     * `startForeground()` is the did-not-start-in-time crash on top of whatever actually
     * failed.
     */
    private fun startForegroundCompat(title: String, phase: Phase): Boolean {
        if (phase == Phase.ACTIVE && !hasRecordAudio()) {
            Log.w(TAG, "RECORD_AUDIO not granted — refusing to claim the microphone FGS type")
            return false
        }
        return try {
            startTyped(buildNotification(title), typeFor(phase))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "call foreground start refused: ${t::class.simpleName}: ${t.message}")
            false
        }
    }

    private fun typeFor(phase: Phase): Int = when (phase) {
        // No microphone bit while ringing: nothing is capturing yet, and asking for it
        // from a push-woken background process is precisely what threw.
        Phase.RINGING -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        Phase.ACTIVE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }

    /**
     * Starts typed, with NO fallback if the type is refused — deliberately.
     *
     * The value passed must be a subset of the manifest's `foregroundServiceType`
     * attribute or ActiveServices (refs/heads/main, L2207) throws IllegalArgumentException
     * carrying its own diagnosis: "foregroundServiceType 0x… is not a subset of
     * foregroundServiceType attribute 0x… in service element of manifest file". The
     * caller logs that message verbatim, so the failure names its own cause.
     *
     * An earlier version of this function retried with the microphone bit alone, to
     * absorb the window where the manifest widening had not merged yet. It was removed at
     * telecom-owner's request and they were right: the hazard is merge ordering, whose
     * control is the lead, and a retry here would have masked an open-ended class of
     * FUTURE manifest misconfiguration — a narrowed ceiling, a renamed service, an edited
     * attribute — by silently downgrading it to microphone-only forever. Insuring against
     * one known transient at the price of hiding every later one is a bad trade, and this
     * repo already carries the receipt for "temporary" code that outlives its reason (the
     * commented-out ConsoleConnectionService stub, removed only in 66ad7dc).
     *
     * Worth stating precisely, because the reasoning for removing it was partly built on
     * a wrong premise: a refused type does NOT crash. startForegroundCompat catches it,
     * logs ERROR, and returns false, so the service stops and the answered call runs with
     * no ongoing notification and no process protection. Visible on a desk (the "שיחה
     * פעילה" notification and its hangup action simply never appear) and legible in
     * logcat — but it is a missing notification, not a stack trace. Do not go looking for
     * a crash that will not come.
     *
     * The API-level branch lives HERE rather than at the call site so Lint can see the
     * guard in the same function as the API-29 `startForeground` overload — `NewApi` is
     * fatal in `lintVitalRelease`, and this project has no lint config to soften it.
     */
    private fun startTyped(notification: Notification, type: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        startForeground(NOTIFICATION_ID, notification, type)
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
        private const val EXTRA_PHASE = "phase"

        /**
         * Starts (or re-types) the ongoing-call service.
         *
         * [phase] selects the foreground-service type and is NOT cosmetic — see this
         * file's header. Pass [Phase.RINGING] for an offered-but-unanswered call, which
         * is legal from a push-woken background process; the default [Phase.ACTIVE] adds
         * the microphone bit and is legal from the two paths an answer arrives on (a
         * notification action, or the visible ring activity), both of which are
         * while-in-use exempt.
         *
         * Calling this a second time to promote RINGING → ACTIVE is supported and is the
         * intended way to do it: a repeat `startForeground` updates the running type
         * (`ActiveServices` L2622), and a while-in-use denial recorded at the first start
         * is re-evaluated rather than latched (L2504-2515, L8305-8312 — grants are
         * sticky, denials are retried against current app state).
         *
         * The ring-phase call site does not exist right now: it was removed from
         * VoxIncomingCallCoordinator.handleIncomingCall (crash-hunter, d27cbac) because
         * under the old `microphone` type it threw, leaving the ring window with no
         * foreground service and a low-memory kill possible before the agent answers.
         * This parameter is what lets that start come back safely — it is the missing
         * half, not speculative API.
         */
        fun start(context: Context, title: String = DEFAULT_TITLE, phase: Phase = Phase.ACTIVE) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PHASE, phase.name)
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
