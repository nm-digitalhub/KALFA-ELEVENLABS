package me.kalfa.agentconsole.telephony.vox

import android.util.Log
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

    private val _durationSec = MutableStateFlow(readDurationSec(fallback = 0))
    override val durationSec: StateFlow<Int> = _durationSec.asStateFlow()

    private val _isReconnecting = MutableStateFlow(call.state == VoxCallState.Reconnecting)
    override val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _holdRefused = MutableStateFlow(false)
    override val holdRefused: StateFlow<Boolean> = _holdRefused.asStateFlow()

    private var ticker: Job? = null

    init {
        call.setCallListener(object : CallListener {
            override fun onStartRinging(call: Call, headers: Map<String, String>?) {
                _state.value = CallState.RINGING
            }

            override fun onCallConnected(call: Call, withVideo: Boolean, headers: Map<String, String>?) {
                _state.value = connectedState
                _isReconnecting.value = false
                startTicker()
            }

            // The media path to the cloud is down; the leg itself is NOT over, and the
            // SDK may still recover it. Implemented because without it this is
            // completely invisible: `state` stays at connectedState, the duration keeps
            // climbing, and the screen goes on telling the agent the call is fine while
            // the guest hears silence. See CallSession.isReconnecting's kdoc.
            override fun onCallReconnecting(call: Call) {
                _isReconnecting.value = true
            }

            override fun onCallReconnected(call: Call) {
                _isReconnecting.value = false
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

        // A session can be constructed around an ALREADY-connected Call — mapState's
        // Connected branch exists precisely for that — in which case onCallConnected
        // has already fired and will not fire again, and the ticker would never start.
        // (No production path reaches this today: the one live caller is
        // VoxIncomingCallCoordinator, which wraps a still-ringing offer. The
        // monitor/takeover legs it was written for are still unwired, and this is the
        // shape they arrive in.)
        if (call.state == VoxCallState.Connected || call.state == VoxCallState.Reconnecting) {
            startTicker()
        }
    }

    private fun mapState(s: VoxCallState): CallState = when (s) {
        VoxCallState.Connected -> connectedState
        VoxCallState.Created, VoxCallState.Connecting, VoxCallState.Reconnecting -> CallState.RINGING
        VoxCallState.Disconnecting, VoxCallState.Disconnected, VoxCallState.Failed -> CallState.DISCONNECTED
    }

    /**
     * The call's elapsed time, from the SDK's own clock.
     *
     * `Call.duration` is a `Long` of milliseconds — verified live on 2026-08-15 against
     * `voximplant.com/api/v2/getDoc?fqdn=references.androidsdk3.android.sdk.calls.call`
     * ("The call duration in milliseconds"), not from recall.
     *
     * This used to be `_durationSec.value += 1` on a one-second `delay` loop, which is
     * not a clock: `delay` guarantees *at least* the requested time, so every late tick
     * permanently loses a second, and a backgrounded or dozing process is exactly where
     * ticks run late. The displayed time drifted DOWN from the truth for the whole rest
     * of the call, and the number an agent reads off the screen is the number they use
     * to decide whether a call is running long. Reading the SDK instead makes a
     * throttled tick mean a less frequent update rather than a wrong value.
     *
     * UNVERIFIED, and guarded rather than assumed: what `duration` returns before the
     * leg connects and after it ends. No device and no `javap` here to check, so the
     * value is clamped to a non-negative Int and a read that throws keeps the last good
     * value instead of resetting the display to zero.
     *
     * The [fallback] parameter is not a nicety — it is the fix for a crash that killed
     * the app on EVERY incoming call, measured 2026-08-17 from the device's own
     * `app.crash` line: `NullPointerException … at=VoxCallSession.readDurationSec:150`,
     * 2ms after `vox.incoming_call`.
     *
     * The default used to be `_durationSec.value`, and the first caller of this function
     * is `_durationSec`'s OWN initializer (see the property above). So constructing a
     * session read a `val` that was still null, on a line that only exists to provide a
     * safe fallback. Worse than a rare race: `getOrDefault`'s argument is evaluated
     * EAGERLY, before the result is even inspected, so the crash did not need
     * `call.duration` to fail — it fired on the happy path, every time, which is why no
     * incoming call had ever survived long enough to be answered.
     *
     * Passing 0 explicitly from the initializer keeps the ticker's "keep the last good
     * value" behaviour for every later call while removing the self-reference from the
     * one call that cannot have one.
     */
    private fun readDurationSec(fallback: Int): Int = runCatching {
        (call.duration / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(fallback)

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                _durationSec.value = readDurationSec(fallback = _durationSec.value)
                // Re-read the toggles from the SDK on the same beat rather than trusting
                // what this class last wrote. `hold`'s failure path deliberately keeps
                // the prior value (holdRefused is the separate signal for "why nothing
                // moved" — see its kdoc), and `muteAudio` is fire-and-forget with no
                // callback at all — so an optimistic write that the SDK did not honour
                // would otherwise stay on screen for the rest of the call. This
                // converges the display on the SDK's own answer within a second,
                // whoever changed it.
                runCatching { call.isMuted }.onSuccess { _isMuted.value = it }
                runCatching { call.isOnHold }.onSuccess { _isHeld.value = it }
            }
        }
    }

    private fun finish() {
        ticker?.cancel()
        ticker = null
        // A leg can end while the media path is still down (the reconnect never
        // succeeded). Clearing this here keeps "reconnecting" from outliving the call
        // it described — DISCONNECTED is the whole truth at that point.
        _isReconnecting.value = false
        _state.value = CallState.DISCONNECTED
    }

    override fun mute(muted: Boolean) {
        call.muteAudio(muted)
        _isMuted.value = muted
    }

    // Reset BEFORE the SDK call, not after a failure — see CallSession.holdRefused's
    // kdoc for why: a StateFlow does not re-emit an unchanged value, so without this
    // a SECOND consecutive refusal (holdRefused already sitting at true) would never
    // produce a new transition for ConsoleViewModel's collector to see.
    override fun hold(held: Boolean) {
        _holdRefused.value = false
        call.hold(held, object : CallCallback {
            override fun onSuccess() { _isHeld.value = held }
            // The prior hold state is kept (isHeld untouched here) — the ticker
            // re-reads call.isOnHold every second and converges the display on the
            // SDK's own answer regardless. What onFailure adds is telling someone: e
            // carries a CallError enum + description, but neither is echoed to the
            // agent (an SDK-internal string is not the kind of detail a user-facing
            // message should carry) — only the CLASS of event, via holdRefused,
            // which ConsoleViewModel turns into an honest Hebrew snackbar.
            override fun onFailure(e: CallException) {
                Log.w(TAG, "hold($held) refused: ${e.error}")
                _holdRefused.value = true
            }
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

    private companion object {
        const val TAG = "VoxCallSession"
    }
}
