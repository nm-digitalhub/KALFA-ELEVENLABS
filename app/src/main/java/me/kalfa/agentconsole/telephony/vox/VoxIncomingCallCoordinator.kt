package me.kalfa.agentconsole.telephony.vox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.voximplant.android.sdk.calls.Call
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.kalfa.agentconsole.domain.model.CallState
import me.kalfa.agentconsole.domain.telephony.CallEngine
import me.kalfa.agentconsole.telephony.CallForegroundService

// THE missing link AGENTS.md flags: assigned to VoxClientManager.onIncomingCall in
// DependencyContainer so a push-woken app's incoming call finally has somewhere to go.
// See docs/android-presence-and-call-ux.md §3 for the full end-to-end flow and why the
// design looks like this (notification-first, minimal ring screen only for the
// locked-device FSI case, everything routed through CallEngine/CallSession rather than
// a parallel state machine).
class VoxIncomingCallCoordinator(
    private val context: Context,
    private val callEngine: CallEngine,
) {
    data class IncomingOffer(
        val callId: String,
        val session: VoxCallSession,
        val displayName: String,
        val number: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _pendingOffer = MutableStateFlow<IncomingOffer?>(null)
    val pendingOffer: StateFlow<IncomingOffer?> = _pendingOffer.asStateFlow()

    init {
        IncomingCallNotificationBuilder.ensureChannel(context)
    }

    // The SDK's IncomingCallListener callback, forwarded via VoxClientManager. Wraps
    // the raw Call in the EXISTING VoxCallSession immediately (state starts RINGING)
    // so an abandoned-before-answer call is observed through the same CallListener
    // path VoxCallSession already has, rather than a second bespoke listener.
    fun handleIncomingCall(call: Call, headers: Map<String, String>) {
        val session = VoxCallSession(call, connectedState = CallState.ACTIVE)
        val offer = IncomingOffer(
            callId = call.id,
            session = session,
            displayName = call.remoteDisplayName ?: "",
            number = call.number,
        )
        _pendingOffer.value = offer

        // The offer's own leg needs the microphone FGS type available BEFORE answer()
        // can be safely called (see answer() below) — start it now too, matching how
        // a real phone rings while already holding the resources it will need.
        if (hasRecordAudioPermission()) {
            CallForegroundService.start(context, title = "שיחה נכנסת...")
        }

        NotificationManagerCompat.from(context).notify(
            IncomingCallNotificationBuilder.NOTIFICATION_ID,
            IncomingCallNotificationBuilder.build(context, offer.callId, offer.displayName, offer.number),
        )

        // One cleanup path for every way this leg can end (declined, hung up after
        // answer, remote hangup, SDK failure) — see docs §3, "the coordinator observes
        // that single transition once". first{} lets this coroutine complete naturally
        // instead of collecting forever after the call is long over.
        scope.launch {
            session.state.first { it == CallState.DISCONNECTED }
            cleanUp(offer.callId)
        }
    }

    fun answer(callId: String) {
        val offer = _pendingOffer.value ?: return
        if (!canActOnOffer(offer.callId, callId, offer.session.state.value)) return

        if (!hasRecordAudioPermission()) {
            // Can't safely claim the microphone FGS type without the permission this
            // app already relies on for every other call leg (CallAudioPermissions) —
            // decline rather than crash inside startForeground(). See docs §3.
            decline(callId)
            return
        }

        offer.session.answer()
        callEngine.attachIncomingSession(offer.session)
        CallForegroundService.start(context, title = "שיחה פעילה")
        NotificationManagerCompat.from(context).cancel(IncomingCallNotificationBuilder.NOTIFICATION_ID)
        _pendingOffer.value = null
    }

    fun decline(callId: String) {
        val offer = _pendingOffer.value ?: return
        if (!canActOnOffer(offer.callId, callId, offer.session.state.value)) return
        offer.session.decline() // triggers finish() synchronously -> cleanUp() below
    }

    private fun cleanUp(callId: String) {
        if (_pendingOffer.value?.callId == callId) {
            _pendingOffer.value = null
        }
        NotificationManagerCompat.from(context).cancel(IncomingCallNotificationBuilder.NOTIFICATION_ID)
        CallForegroundService.stop(context)
        callEngine.clearAttachedSession()
    }

    private fun hasRecordAudioPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

// Pure gating logic for answer()/decline(), pulled out to the top level so it is
// unit-testable with no Android/SDK classes on the classpath — same
// separation-for-testability reasoning as VoxSilentLogin.kt's planSilentLogin. A
// stale notification/UI action (the offer already disconnected, or a second call
// replaced it) must be a no-op instead of acting on a dead or wrong Call — see
// docs/android-presence-and-call-ux.md §3 and Call.answer's documented
// CallException for the dead-call case this guards against.
fun canActOnOffer(pendingCallId: String?, actionCallId: String, sessionState: CallState): Boolean =
    pendingCallId == actionCallId && sessionState == CallState.RINGING

