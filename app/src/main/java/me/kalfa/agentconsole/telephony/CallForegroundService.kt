package me.kalfa.agentconsole.telephony

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

// Ongoing-call foreground service. Keeps the process alive and audio-capable while a
// live agent leg (monitor / takeover) is connected, and shows the mandatory ongoing
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
// NOT started anywhere yet: the telephony-wiring step calls start()/stop() when a leg
// connects/disconnects. Foreground/background call-continuity MUST still be validated
// on a real device before release (a known release gate).
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
        startForegroundCompat(buildNotification(title))
        // If the system kills us we do NOT auto-restart with a null intent — a call
        // that died should not silently resurrect a foreground notification.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(CONTENT_TEXT)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
        private const val CHANNEL_ID = "kalfa_active_call"
        private const val CHANNEL_NAME = "שיחות פעילות"
        private const val CHANNEL_DESC = "התראה קבועה בזמן שיחה פעילה במסוף"
        private const val CONTENT_TEXT = "המסוף מנהל שיחה פעילה"
        private const val DEFAULT_TITLE = "שיחה פעילה"
        private const val NOTIFICATION_ID = 4711
        private const val EXTRA_TITLE = "title"

        // Called by the telephony-wiring step when a leg connects.
        fun start(context: Context, title: String = DEFAULT_TITLE) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // Called when the leg disconnects.
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
