package me.kalfa.agentconsole.telephony.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.domain.model.AgentStatus

// Handles the presence notification's shade/lock-screen status actions
// (docs/android-presence-and-call-ux.md §1). Not exported (AndroidManifest.xml) —
// only this app's own PendingIntents target it. Forwards to
// PresenceForegroundService.updateStatus rather than calling PresenceActions
// directly: onReceive must return quickly and PresenceActions.applyStatus makes a
// network call, and the service already has a long-lived scope for exactly this kind
// of work instead of this receiver needing its own goAsync() lifecycle.
class PresenceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PresenceNotificationBuilder.ACTION_SET_STATUS) return
        val statusName = intent.getStringExtra(PresenceNotificationBuilder.EXTRA_STATUS) ?: return
        val status = runCatching { AgentStatus.valueOf(statusName) }.getOrNull() ?: return

        DependencyContainer.attach(context.applicationContext)
        PresenceForegroundService.updateStatus(context.applicationContext, status)
    }
}
