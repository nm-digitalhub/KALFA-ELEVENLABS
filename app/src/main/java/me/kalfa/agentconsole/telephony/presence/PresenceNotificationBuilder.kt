package me.kalfa.agentconsole.telephony.presence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.MainActivity
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.telephony.PresenceSyncState
import me.kalfa.agentconsole.telephony.vox.RingCapability
import me.kalfa.agentconsole.telephony.vox.RingCapabilityChecker
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.toHebrewMessage

// Builds the persistent presence notification (docs/android-presence-and-call-ux.md
// §1 "Notification (Part 2)"). Split out from PresenceForegroundService so the
// Notification this produces is unit-testable with Robolectric without starting a
// real Service — same separation-for-testability reasoning used throughout
// telephony/vox (see VoxSilentLogin.kt's kdoc).
object PresenceNotificationBuilder {
    const val CHANNEL_ID = "kalfa_agent_presence"
    private const val CHANNEL_NAME = "נוכחות סוכן"
    private const val CHANNEL_DESC = "התראה קבועה על סטטוס הזמינות שלך"
    const val NOTIFICATION_ID = 4712

    const val ACTION_SET_STATUS = "me.kalfa.agentconsole.action.SET_STATUS"
    // Carried so MainActivity can tell "opened from the presence notification"
    // apart from a launcher start, without changing what it shows today.
    const val ACTION_OPEN_CONSOLE = "me.kalfa.agentconsole.action.OPEN_CONSOLE"
    const val EXTRA_STATUS = "status"

    // RingCapability (telephony/vox/RingCapability.kt) is a device-CONFIGURATION
    // snapshot, not a failed request — deliberately NOT folded into AppFailure/
    // FailureContext (which model an attempted operation's outcome). Two distinct
    // texts because the consequences differ: canAlert==false means no call reaches
    // this agent at all; canRingOnLockedScreen==false (canAlert still true) means
    // calls DO arrive but a locked/pocketed phone will miss them.
    private const val NOTIFICATIONS_BLOCKED_TEXT = "התראות למסוף חסומות — שיחות נכנסות לא יוצגו כלל."
    private const val CANNOT_RING_LOCKED_TEXT = "מסך שיחה נכנסת לא ייפתח במכשיר נעול — שיחות עלולות להתפספס."

    // The only three states an agent can set themselves — mirrors AgentStatus
    // exactly, per the brief's "do not invent new states". IN_CALL is server-managed
    // (AgentPresence.setStatus's own kdoc: "the app must never write it") and is
    // deliberately never offered as an action here.
    private val ACTIONABLE_STATUSES = listOf(AgentStatus.READY, AgentStatus.NOT_READY, AgentStatus.DND)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = CHANNEL_DESC
                },
            )
        }
    }

    // syncState distinguishes "requested" from "confirmed-by-server" (PresenceSyncState's
    // kdoc); pushRegistrationFailure and ringCapability are further ORTHOGONAL signals —
    // presence can be fully Synced (the status write reached the server) while the
    // device separately never registered for push (killed/backgrounded app can never be
    // woken — docs' "Update 2026-08-14 (later)", Voximplant reporting "No push
    // notifications has been sent") or while notifications/full-screen-intent are
    // blocked at the OS level (a call arrives with nowhere to show itself —
    // RingCapability's kdoc). This notification is the ONLY surface a backgrounded
    // agent sees, so none of these may be silently absent from it.
    //
    // Priority when more than one is wrong: syncState first (not even confirmed present
    // at all is the most urgent fact); then notifications being fully blocked
    // (!canAlert defeats every other channel too, including this very notification's own
    // future updates); then push-registration (defeats being WOKEN, but a live app can
    // still show the SDK's own incoming-call notification); then the narrower
    // locked-screen-only gap.
    fun contentTextFor(
        status: AgentStatus,
        syncState: PresenceSyncState,
        pushRegistrationFailure: AppFailure?,
        ringCapability: RingCapability? = null,
    ): String =
        when {
            syncState is PresenceSyncState.Failed -> syncState.failure.toHebrewMessage(FailureContext.PRESENCE)
            syncState == PresenceSyncState.Pending -> "סטטוס: ${status.labelHebrew} (מעדכן מול השרת...)"
            ringCapability != null && !ringCapability.canAlert -> NOTIFICATIONS_BLOCKED_TEXT
            pushRegistrationFailure != null ->
                "סטטוס: ${status.labelHebrew} — " + pushRegistrationFailure.toHebrewMessage(FailureContext.PUSH_REGISTRATION)
            ringCapability != null && !ringCapability.canRingOnLockedScreen -> CANNOT_RING_LOCKED_TEXT
            else -> "סטטוס: ${status.labelHebrew}"
        }

    fun build(
        context: Context,
        status: AgentStatus,
        syncState: PresenceSyncState,
        pushRegistrationFailure: AppFailure? = null,
        ringCapability: RingCapability? = null,
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("מסוף KALFA")
            .setContentText(contentTextFor(status, syncState, pushRegistrationFailure, ringCapability))
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            // Tapping the notification opened NOTHING before this: the builder set a
            // title, text, icon and actions but never a content intent, and a
            // notification without one simply swallows the tap. Reported by the owner
            // 2026-08-14 ("לחיצה על ההתראה לא פותחת דבר"). The incoming-call
            // notification in telephony/vox already did this correctly; the presence
            // one was the outlier.
            //
            // Deliberately NOT paired with setAutoCancel(true), which the docs
            // recommend for ordinary notifications: this is the ongoing
            // foreground-service notification, so dismissing it on tap would remove
            // the agent's only always-visible status surface — and it would come
            // straight back on the next heartbeat, which is worse than not moving.
            .setContentIntent(openConsolePendingIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // No PII of any kind in this notification (agent's own status only) — see
            // docs/android-presence-and-call-ux.md §1 "Lock-screen visibility".
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                ACTIONABLE_STATUSES.forEach { candidate ->
                    if (candidate != status) {
                        addAction(0, candidate.labelHebrew, statusPendingIntent(context, candidate))
                    }
                }
                // "Give the agent a way to fix it", one tap — mutually exclusive by
                // construction (canRingOnLockedScreen implies canAlert), so at most one
                // of these ever adds a third action.
                if (ringCapability != null && !ringCapability.canAlert) {
                    addAction(0, "פתח הגדרות התראות", appNotificationSettingsPendingIntent(context))
                } else if (ringCapability != null && !ringCapability.canRingOnLockedScreen) {
                    RingCapabilityChecker.fullScreenIntentSettingsIntent(context)?.let { settingsIntent ->
                        addAction(0, "אפשר מסך שיחה נעולה", fullScreenIntentSettingsPendingIntent(context, settingsIntent))
                    }
                }
            }
            .build()

    private fun statusPendingIntent(context: Context, status: AgentStatus): PendingIntent {
        val intent = Intent(context, PresenceActionReceiver::class.java)
            .setAction(ACTION_SET_STATUS)
            .putExtra(EXTRA_STATUS, status.name)
        return PendingIntent.getBroadcast(
            context,
            status.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // FLAG_ACTIVITY_SINGLE_TOP alongside NEW_TASK so an already-open console is
    // brought forward rather than stacked on itself — MainActivity is launchMode
    // singleTask, and this matches what the incoming-call notification already does.
    // FLAG_IMMUTABLE is mandatory from Android 12; omitting it throws at creation.
    private fun openConsolePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CONSOLE)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_CONSOLE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun appNotificationSettingsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_NOTIFICATION_SETTINGS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun fullScreenIntentSettingsPendingIntent(context: Context, settingsIntent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE_FSI_SETTINGS,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Distinct from ACTIONABLE_STATUSES' request codes (status.ordinal, 0-2).
    private const val REQUEST_CODE_OPEN_CONSOLE = 99
    private const val REQUEST_CODE_NOTIFICATION_SETTINGS = 100
    private const val REQUEST_CODE_FSI_SETTINGS = 101
}
