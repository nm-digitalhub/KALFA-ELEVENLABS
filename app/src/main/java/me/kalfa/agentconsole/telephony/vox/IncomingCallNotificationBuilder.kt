package me.kalfa.agentconsole.telephony.vox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import me.kalfa.agentconsole.MainActivity

// Builds the incoming-call notification: NotificationCompat.CallStyle.forIncomingCall,
// full-screen intent, and the lock-screen-redacted public version. Split out from
// VoxIncomingCallCoordinator for the same reason PresenceNotificationBuilder is split
// from PresenceForegroundService — unit-testable (Robolectric) without a real Service
// or SDK Call object. See docs/android-presence-and-call-ux.md §3.
object IncomingCallNotificationBuilder {
    const val CHANNEL_ID = "kalfa_incoming_call"
    private const val CHANNEL_NAME = "שיחות נכנסות"
    private const val CHANNEL_DESC = "שיחה נכנסת שהועברה למכשיר שלך"
    const val NOTIFICATION_ID = 4713

    const val ACTION_ANSWER = "me.kalfa.agentconsole.action.ANSWER_CALL"
    const val ACTION_DECLINE = "me.kalfa.agentconsole.action.DECLINE_CALL"
    const val ACTION_INCOMING_CALL_UI = "me.kalfa.agentconsole.action.INCOMING_CALL_UI"
    const val EXTRA_CALL_ID = "call_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
                        .build(),
                )
            }
            mgr.createNotificationChannel(channel)
        }
    }

    // displayName/number come straight off the live SDK Call, not any PII-free
    // console_call_feed view — see docs §3 "Lock-screen redaction" for why this
    // notification, unlike the presence one, needs a redacted public version.
    fun build(context: Context, callId: String, displayName: String, number: String): android.app.Notification {
        // Own the precondition rather than inheriting it from VoxIncomingCallCoordinator's
        // init block, which is the only other place this channel is created. Posting to a
        // channel that does not exist is not an exception — AOSP
        // NotificationManagerService logs "No Channel found for pkg=..." and returns false
        // — so the whole incoming call would simply never appear, which is exactly the
        // class of silent failure this path exists to eliminate. Idempotent (ensureChannel
        // no-ops when the channel is already there); CallForegroundService.buildNotification
        // already builds its notification the same way.
        ensureChannel(context)

        // `number` is accepted and deliberately NOT rendered — recorded here because the
        // opposite is easy to assume. docs §3 justifies VISIBILITY_PRIVATE + the redacted
        // public version on the grounds that this notification "carries the real caller
        // number"; it does not, and never has. Nothing here reads `number`, and CallStyle
        // shows only the Person's name. So the redaction machinery is currently protecting
        // a caller LABEL, not a phone number. Left as-is on purpose: putting the number on
        // the Person (e.g. a tel: uri, which would also let the OS match a contact) is a
        // privacy decision about PII reaching every app with notification-listener access,
        // not a defect to quietly fix inside a builder. Raised for the owner; if the answer
        // is "don't show it", drop the parameter instead of leaving the doc claiming it.
        val caller = Person.Builder()
            .setName(displayName.ifBlank { "אורח" })
            .setImportant(true)
            .build()

        val contentIntent = uiPendingIntent(context, callId)

        val fullNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    declinePendingIntent(context, callId),
                    answerPendingIntent(context, callId),
                ),
            )
            .addPerson(caller)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedNotification(context))
            .build()

        return fullNotification
    }

    private fun redactedNotification(context: Context) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentTitle("שיחה נכנסת למסוף KALFA")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun uiPendingIntent(context: Context, callId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_INCOMING_CALL_UI)
            .putExtra(EXTRA_CALL_ID, callId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun answerPendingIntent(context: Context, callId: String) =
        actionPendingIntent(context, ACTION_ANSWER, callId, requestCode = 1)

    private fun declinePendingIntent(context: Context, callId: String) =
        actionPendingIntent(context, ACTION_DECLINE, callId, requestCode = 2)

    private fun actionPendingIntent(context: Context, action: String, callId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, IncomingCallActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_CALL_ID, callId)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
