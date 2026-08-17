package me.kalfa.agentconsole.telephony.vox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import me.kalfa.agentconsole.MainActivity

// Builds the incoming-call notification: NotificationCompat.CallStyle.forIncomingCall,
// full-screen intent, and the lock-screen-redacted public version. Split out from
// VoxIncomingCallCoordinator for the same reason PresenceNotificationBuilder is split
// from PresenceForegroundService — unit-testable (Robolectric) without a real Service
// or SDK Call object. See docs/android-presence-and-call-ux.md §3.
object IncomingCallNotificationBuilder {
    /**
     * VERSIONED, and the version bump is the entire fix for a phone that would not
     * vibrate.
     *
     * A notification channel is IMMUTABLE once created: "After you create a
     * notification channel, you can't change the notification behaviors. The user has
     * complete control at that point" (developer.android.com, channels guide, read
     * 2026-08-17), and "Recreating an existing notification channel with its original
     * values performs no operation". So every setting below was frozen on each device
     * the first time the app ran, and editing them here reaches nobody who already has
     * the app. Only a NEW id creates a new channel.
     *
     * The v1 channel is deleted in ensureChannel so agents are not left with a dead
     * duplicate in system settings.
     *
     * RAISE THIS AGAIN for any future change to importance, sound or vibration — and
     * only for those. Name and description ARE mutable and need no bump.
     */
    const val CHANNEL_ID = "kalfa_incoming_call_v2"

    /**
     * The pre-2026-08-17 channel. Kept only so ensureChannel can delete it; do not
     * post to it.
     */
    private const val LEGACY_CHANNEL_ID = "kalfa_incoming_call"
    private const val CHANNEL_NAME = "שיחות נכנסות"
    private const val CHANNEL_DESC = "שיחה נכנסת שהועברה למכשיר שלך"
    const val NOTIFICATION_ID = 4713

    /**
     * Explicit on/off waveform rather than relying on the platform default, because a
     * default is exactly what an OEM is free to make flat. Reads as
     * delay-0, buzz-400, pause-800, buzz-400 and repeats via the notification's own
     * cadence — deliberately close to a phone ring rather than a message blip, since
     * this competes for attention with a pocketed phone.
     */
    private val VIBRATION_PATTERN = longArrayOf(0L, 400L, 800L, 400L)

    const val ACTION_ANSWER = "me.kalfa.agentconsole.action.ANSWER_CALL"
    const val ACTION_DECLINE = "me.kalfa.agentconsole.action.DECLINE_CALL"
    const val ACTION_INCOMING_CALL_UI = "me.kalfa.agentconsole.action.INCOMING_CALL_UI"
    const val EXTRA_CALL_ID = "call_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        // Delete the superseded channel first. Harmless when it was never created,
        // and without it every agent who used an earlier build keeps a second,
        // identically-named "שיחות נכנסות" entry in system settings that nothing
        // posts to — the one they would open to fix exactly this problem.
        if (mgr.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                // These three attributes are Android's own recipe for an incoming
                // VoIP call (developer.android.com "Add notifications to a media
                // app" / telecom VoIP guide, read 2026-08-17), and the reason this
                // channel is being rebuilt at all.
                //
                // setLegacyStreamType(STREAM_RING) is the load-bearing one. The
                // ringer mode an agent flips to silent-or-vibrate governs the RING
                // stream; a channel that does not declare itself as ring audio is
                // not the thing that switch is deciding about, which is how a phone
                // in vibrate mode ends up neither ringing NOR buzzing — the reported
                // symptom: "כאשר הטלפון נמצא במצב רטט ונכנסת שיחה … נוצר מצב בו
                // שאני יפספס שיחות".
                //
                // The previous value, USAGE_NOTIFICATION_COMMUNICATION_REQUEST, is
                // deprecated as of API 33 and describes a message-style request for
                // attention, not a ringing phone.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_RING)
                        .build(),
                )
            }
            mgr.createNotificationChannel(channel)
        }
    }

    /**
     * The one settings screen where an agent can turn vibration back on for THIS
     * channel.
     *
     * Needed because the platform gives the app no other lever: channel behaviour is
     * immutable after creation and "Only the user can change the channel behaviours
     * from the system settings" (Android channels guide). If someone has previously
     * turned vibration off here, no app update can undo it — this intent is the whole
     * remedy.
     */
    fun channelSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
