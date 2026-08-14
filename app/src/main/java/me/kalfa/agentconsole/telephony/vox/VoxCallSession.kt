package me.kalfa.agentconsole.telephony.vox

import com.voximplant.android.sdk.calls.Call
import com.voximplant.android.sdk.calls.CallCallback
import com.voximplant.android.sdk.calls.CallDisconnectReason
import com.voximplant.android.sdk.calls.CallException
import com.voximplant.android.sdk.calls.CallListener
import com.voximplant.android.sdk.calls.CallSettings
import com.voximplant.android.sdk.calls.RejectMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kalfa.agentconsole.domain.model.CallState
import me.kalfa.agentconsole.domain.telephony.CallSession
import com.voximplant.android.sdk.calls.CallState as VoxCallState

// A real CallSession backed by a Voximplant v3 `Call` — the human-agent SDK leg
// for monitor/takeover. Maps the SDK call state onto the app's domain CallState
// and bridges the app's controls (mute/hold/DTMF/hangup) to the SDK.
//
// `connectedState` is what the session reports once the leg connects: ACTIVE for a
// plain leg, MONITORED for a silent-listen leg, TAKEN_OVER after a takeover. The
// receive-only isolation of a monitor leg is enforced SERVER-SIDE (VoxEngine
// Conference), never by muting locally — this class only reflects state.
//
// CallListener param nullability is matched to the SDK exactly (verified from the
// shipped AAR): `call` non-null, `headers` nullable, `disconnectReason` non-null,
// `description` nullable. A mismatch would not compile.
class VoxCallSession(
    private val call: Call,
    private val connectedState: CallState = CallState.ACTIVE,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) : CallSession {

    override val id: String = call.id
    override val customerPhone: String = call.number
    override val customerName: String = call.remoteDisplayName ?: "אורח"

    private val _state = MutableStateFlow(mapState(call.state))
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private val _isMuted = MutableStateFlow(call.isMuted)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isHeld = MutableStateFlow(call.isOnHold)
    override val isHeld: StateFlow<Boolean> = _isHeld.asStateFlow()

    private val _durationSec = MutableStateFlow(0)
    override val durationSec: StateFlow<Int> = _durationSec.asStateFlow()

    private var ticker: Job? = null

    init {
        call.setCallListener(object : CallListener {
            override fun onStartRinging(call: Call, headers: Map<String, String>?) {
                _state.value = CallState.RINGING
            }

            override fun onCallConnected(call: Call, withVideo: Boolean, headers: Map<String, String>?) {
                _state.value = connectedState
                startTicker()
            }

            override fun onCallDisconnected(
                call: Call,
                headers: Map<String, String>?,
                disconnectReason: CallDisconnectReason,
            ) {
                finish()
            }

            override fun onCallFailed(
                call: Call,
                code: Int,
                description: String?,
                headers: Map<String, String>?,
            ) {
                finish()
            }
        })
    }

    private fun mapState(s: VoxCallState): CallState = when (s) {
        VoxCallState.Connected -> connectedState
        VoxCallState.Created, VoxCallState.Connecting, VoxCallState.Reconnecting -> CallState.RINGING
        VoxCallState.Disconnecting, VoxCallState.Disconnected, VoxCallState.Failed -> CallState.DISCONNECTED
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                _durationSec.value += 1
            }
        }
    }

    private fun finish() {
        ticker?.cancel()
        ticker = null
        _state.value = CallState.DISCONNECTED
    }

    override fun mute(muted: Boolean) {
        call.muteAudio(muted)
        _isMuted.value = muted
    }

    override fun hold(held: Boolean) {
        call.hold(held, object : CallCallback {
            override fun onSuccess() { _isHeld.value = held }
            override fun onFailure(e: CallException) { /* keep the prior hold state */ }
        })
    }

    override fun sendDtmf(digit: String) {
        call.sendDTMF(digit)
    }

    override fun hangup() {
        runCatching { call.hangup(emptyMap()) }
        finish()
    }

    // Call.answer throws CallException (e.g. the platform already gave up on this
    // leg — RING_RETRY_WINDOW_MS elapsed, or the caller hung up first). The caller of
    // this method (VoxIncomingCallCoordinator) is expected to have already checked
    // state == RINGING before invoking it; runCatching here is a second, defensive
    // layer so a race never propagates an uncaught exception into a
    // BroadcastReceiver's onReceive.
    override fun answer() {
        runCatching { call.answer(CallSettings()) }
    }

    // Reject (not hangup): the SDK distinguishes "never answered" (reject, proper SIP
    // decline/busy) from "was connected, now ending" (hangup). Using reject here keeps
    // that distinction correct on the wire instead of relying on hangup's
    // any-state tolerance.
    override fun decline() {
        runCatching { call.reject(RejectMode.Decline, emptyMap()) }
        finish()
    }
}
