package me.kalfa.agentconsole.telephony.vox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.kalfa.agentconsole.di.DependencyContainer

// Handles the incoming-call notification's Answer/Decline actions
// (docs/android-presence-and-call-ux.md §3). Not exported (AndroidManifest.xml) —
// only this app's own PendingIntents target it. Deliberately a broadcast, not an
// Activity: answering/declining an SDK Call is a fast, non-UI operation
// (Call.answer/Call.reject), so this works with the app fully backgrounded and does
// not need to wait for any Activity to exist or resume.
class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(IncomingCallNotificationBuilder.EXTRA_CALL_ID) ?: return
        val coordinator = DependencyContainer.incomingCallCoordinator ?: return
        when (intent.action) {
            IncomingCallNotificationBuilder.ACTION_ANSWER -> coordinator.answer(callId)
            IncomingCallNotificationBuilder.ACTION_DECLINE -> coordinator.decline(callId)
        }
    }
}
