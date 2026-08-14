package me.kalfa.agentconsole.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.kalfa.agentconsole.di.DependencyContainer

// The ongoing-call notification's "נתק" (hang up) action — the one in-shade control
// for an already-answered incoming leg (docs/android-presence-and-call-ux.md §3,
// "What this change does not do": no in-app connected-call screen, so this is the
// agent's way to end the call without one). Not exported — only
// CallForegroundService's own PendingIntent targets it. Operates directly on
// CallEngine.currentSession, the same session VoxIncomingCallCoordinator attached.
class CallHangupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HANGUP) return
        DependencyContainer.callEngine.currentSession.value?.hangup()
    }

    companion object {
        const val ACTION_HANGUP = "me.kalfa.agentconsole.action.HANGUP_CALL"
    }
}
