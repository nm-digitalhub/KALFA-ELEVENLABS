package me.kalfa.agentconsole.telephony.vox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
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

    // The call that has been ANSWERED and is therefore the current owner of
    // CallForegroundService and CallEngine.currentSession. Deliberately separate from
    // _pendingOffer, which answer() clears: without it, cleanUp() had no way to tell
    // "the leg that owns the microphone service ended" from "some other leg ended",
    // and tore down both unconditionally. See cleanUp's comment for the sequence that
    // broke. @Volatile because the writers are on different threads: answer() runs on
    // whichever thread invoked it (the main thread from IncomingCallScreen, a binder
    // thread from IncomingCallActionReceiver), cleanUp() on Dispatchers.Main, and the
    // offers it tracks originate on the SDK's own executor (verified in
    // android-sdk-calls 3.2.0: VICalls' message listener dispatches through a
    // ScheduledExecutorService, so nothing on this path is main-thread by default).
    @Volatile private var answeredCallId: String? = null

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
        // KNOWN GAP, logged rather than fixed: a second offer arriving while one is
        // still pending REPLACES it. Offer A's SDK Call is still live and still
        // RINGING, but nothing can reach it any more — canActOnOffer gates on
        // `pendingCallId == actionCallId`, so decline("A") returns early forever and A
        // rings on until the platform's own RING_RETRY_WINDOW_MS gives up. The single
        // NOTIFICATION_ID means B's notification replaced A's too, so the agent never
        // sees that it happened.
        //
        // Not fixed here because every honest option is a design decision this change
        // has no mandate for — auto-reject the second call, auto-reject the first, or
        // build a call-waiting surface — and docs/android-presence-and-call-ux.md §3
        // does not cover concurrent offers at all. Guessing would be worse than
        // leaving it visible. Whether it can even happen depends on server-side ring
        // fan-out (beta's route-inbound `ring_order`), which is outside this repo.
        _pendingOffer.value?.let { superseded ->
            if (superseded.callId != offer.callId) {
                Log.w(TAG, "incoming offer ${offer.callId} superseded ${superseded.callId}; the older leg is now unreachable")
            }
        }
        _pendingOffer.value = offer

        // NO foreground service during the ring phase — a deliberate deviation from
        // docs/android-presence-and-call-ux.md §3 step 4, which says the coordinator
        // starts CallForegroundService here.
        //
        // The platform does not allow it on the path that matters. CallForegroundService
        // is `microphone`-typed, and `microphone` is a WHILE-IN-USE foreground-service
        // type: it cannot be started while the app is in the background. The
        // high-priority-FCM exemption that makes a push wake-up legal is on the separate
        // background-START list, NOT on the while-in-use list (which is: system
        // component, app widget, interacting with a notification, PendingIntent from a
        // visible app, device-owner DPC, VoiceInteractionService,
        // START_ACTIVITIES_FROM_BACKGROUND). This method is reached from the SDK's
        // incoming-call callback on a push-woken, backgrounded process — none of those.
        //
        // The `hasRecordAudioPermission()` check that used to guard this was necessary
        // but not sufficient, and that is the trap: the permission IS granted, it simply
        // is not usable from the background, so the check passed and the start threw
        // anyway. Verified with fgs-owner against the platform's own service-types docs;
        // no device here to confirm it empirically.
        //
        // answer() still starts it, and legally: an answer arrives either from the
        // notification action (interacting with a notification — on the while-in-use
        // list) or from IncomingCallScreen (a visible activity). Accepted cost: the ring
        // window runs with no FGS, so a low-memory kill before the agent answers is
        // possible. If CallForegroundService ever moves to the `phoneCall` type — under
        // discussion with telecom-owner, since core-telecom already supplies the
        // MANAGE_OWN_CALLS that type requires — this start becomes legal again and can
        // come back exactly as the spec describes it.

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
        answeredCallId = offer.callId
        callEngine.attachIncomingSession(offer.session)
        startCallForegroundService(title = "שיחה פעילה")
        NotificationManagerCompat.from(context).cancel(IncomingCallNotificationBuilder.NOTIFICATION_ID)
        _pendingOffer.value = null
    }

    fun decline(callId: String) {
        val offer = _pendingOffer.value ?: return
        if (!canActOnOffer(offer.callId, callId, offer.session.state.value)) return
        offer.session.decline() // triggers finish() synchronously -> cleanUp() below
    }

    /**
     * Tears down only what THIS call still owns.
     *
     * Every branch here used to be unconditional except the `_pendingOffer` clear, and
     * that made an ordinary second call destroy the first one. Sequence: call A is
     * answered — `attachIncomingSession(A)`, `CallForegroundService` running for the
     * live leg. Call B arrives and is declined (or the caller gives up). B's
     * `first { DISCONNECTED }` collector fires `cleanUp(B)`, which stopped the
     * foreground service A's audio depends on and nulled `CallEngine.currentSession`,
     * so `CallHangupActionReceiver`'s "נתק" action could no longer end A either.
     * Losing a `microphone`-typed FGS mid-call is also how the platform reclaims the
     * process on API 34+, so this was not merely cosmetic.
     *
     * The three resources have three different owners, so each is released by its own
     * owner: the ring notification belongs to the offer currently on screen, the
     * attached session belongs to the answered call, and the foreground service is
     * shared — released only once neither a pending offer nor an answered call needs
     * it.
     */
    private fun cleanUp(callId: String) {
        val plan = planIncomingCallCleanup(
            endedCallId = callId,
            pendingCallId = _pendingOffer.value?.callId,
            answeredCallId = answeredCallId,
        )
        if (plan.clearPendingOffer) _pendingOffer.value = null
        if (plan.clearAttachedSession) answeredCallId = null

        if (plan.cancelRingNotification) {
            NotificationManagerCompat.from(context)
                .cancel(IncomingCallNotificationBuilder.NOTIFICATION_ID)
        }
        if (plan.clearAttachedSession) callEngine.clearAttachedSession()
        if (plan.stopForegroundService) CallForegroundService.stop(context)
    }

    /**
     * Starting the call FGS can THROW, and the throw is not a bug to let crash.
     *
     * `Context.startForegroundService` raises `ForegroundServiceStartNotAllowedException`
     * when the app is in the background and no exemption applies (Android 12+), and the
     * only remaining caller — `answer()` — can be reached from
     * `IncomingCallActionReceiver`, a `BroadcastReceiver` where an escaping exception
     * kills the process outright instead of dropping one call.
     *
     * Both of `answer()`'s entry points are documented exemptions (a notification
     * action; a visible activity), so this should not fire. "Should not" is not
     * "cannot" on an OEM skin nobody here can test, and the cost of being wrong is a
     * process kill during a live ring, so the call is guarded rather than trusted.
     *
     * Kept even though CallForegroundService now guards its own side too (fgs-owner,
     * 74ee35b): the two throws happen in different places — this one synchronously from
     * `startForegroundService`, theirs from inside `Service.startForeground()` on the
     * main looper after this call has already returned — and Google's own pages
     * disagree about which one raises. Guarding both is what makes the outcome
     * independent of that.
     *
     * Degrading is what docs/android-presence-and-call-ux.md §3 already prescribes for
     * the sibling case (RECORD_AUDIO missing): the notification still shows, the agent
     * can still see and open the call. Logged rather than surfaced because there is no
     * action an agent could take about it mid-ring.
     */
    private fun startCallForegroundService(title: String) {
        try {
            CallForegroundService.start(context, title = title)
        } catch (e: Exception) {
            Log.w(TAG, "call foreground service refused to start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun hasRecordAudioPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "VoxIncomingCall"
    }
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

/** What a finished leg is allowed to tear down. See [planIncomingCallCleanup]. */
data class IncomingCallCleanup(
    val clearPendingOffer: Boolean,
    val cancelRingNotification: Boolean,
    val clearAttachedSession: Boolean,
    val stopForegroundService: Boolean,
)

/**
 * Decides which shared resources a just-ended call may release — pulled out to the top
 * level for the same reason as [canActOnOffer]: unit-testable with no Android or SDK
 * class on the classpath, and this project has no mocking library that could stand in
 * for a `Context` or an SDK `Call`.
 *
 * The bug this encodes against: the three resources are shared across calls but were
 * being released unconditionally by whichever leg happened to end. Call A is answered
 * (holding `CallForegroundService` and `CallEngine.currentSession`); call B arrives and
 * is declined; B's teardown stopped A's foreground service and cleared A's session,
 * leaving a live call with no microphone FGS — which on API 34+ is how the platform
 * reclaims the process — and no working "נתק" action.
 *
 * Ownership, one rule each:
 *  - the ring notification belongs to the offer currently displayed,
 *  - the attached `CallSession` belongs to the answered call,
 *  - the foreground service is shared, so it is stopped only once NEITHER a pending
 *    offer nor an answered call is left to need it.
 */
fun planIncomingCallCleanup(
    endedCallId: String,
    pendingCallId: String?,
    answeredCallId: String?,
): IncomingCallCleanup {
    val wasPending = pendingCallId == endedCallId
    val wasAnswered = answeredCallId == endedCallId
    val pendingAfter = if (wasPending) null else pendingCallId
    val answeredAfter = if (wasAnswered) null else answeredCallId
    return IncomingCallCleanup(
        clearPendingOffer = wasPending,
        cancelRingNotification = wasPending,
        clearAttachedSession = wasAnswered,
        stopForegroundService = pendingAfter == null && answeredAfter == null,
    )
}

