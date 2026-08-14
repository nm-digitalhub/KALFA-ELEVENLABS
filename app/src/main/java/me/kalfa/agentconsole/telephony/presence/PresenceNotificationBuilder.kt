package me.kalfa.agentconsole.telephony.presence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.telephony.PresenceSyncState
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
    const val EXTRA_STATUS = "status"

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
    // kdoc); pushRegistrationFailure is an ORTHOGONAL signal — presence can be fully
    // Synced (the status write reached the server) while the device separately never
    // registered for push (so a killed/backgrounded app can never be woken — see
    // docs/android-presence-and-call-ux.md's "Update 2026-08-14 (later)", the live
    // incident of Voximplant reporting "No push notifications has been sent"). This
    // notification is the ONLY surface a backgrounded agent sees, so neither failure
    // may be silently absent from it. syncState takes priority when both are wrong —
    // it's the more urgent fact (the agent isn't even confirmed present at all).
    fun contentTextFor(status: AgentStatus, syncState: PresenceSyncState, pushRegistrationFailure: AppFailure?): String =
        when {
            syncState is PresenceSyncState.Failed -> syncState.failure.toHebrewMessage(FailureContext.PRESENCE)
            syncState == PresenceSyncState.Pending -> "סטטוס: ${status.labelHebrew} (מעדכן מול השרת...)"
            pushRegistrationFailure != null ->
                "סטטוס: ${status.labelHebrew} — " + pushRegistrationFailure.toHebrewMessage(FailureContext.PUSH_REGISTRATION)
            else -> "סטטוס: ${status.labelHebrew}"
        }

    fun build(
        context: Context,
        status: AgentStatus,
        syncState: PresenceSyncState,
        pushRegistrationFailure: AppFailure? = null,
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("מסוף KALFA")
            .setContentText(contentTextFor(status, syncState, pushRegistrationFailure))
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
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
}
