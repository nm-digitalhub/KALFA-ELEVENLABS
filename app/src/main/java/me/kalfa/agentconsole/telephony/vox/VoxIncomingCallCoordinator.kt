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
import me.kalfa.agentconsole.telemetry.Telemetry
import me.kalfa.agentconsole.telemetry.TelemetryEvents
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
        /**
         * The caller's number formatted for a human to read, or "" when there is
         * nothing to show. This is what the ring screen renders — [number] is the
         * raw SDK value and stays as the diagnostic/fallback.
         *
         * See [displayNumberFrom] for where it comes from and why it is not simply
         * [number].
         */
        val displayNumber: String,
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

    /**
     * One SIP header value, matched case-insensitively.
     *
     * Header names are case-insensitive by RFC 3261 §7.3.1 and nothing promises this
     * SDK preserves the sender's casing, so matching the exact string would be
     * relying on an accident.
     */
    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()

    /**
     * The caller's number to put in front of the agent, or "" for none.
     *
     * The server decides this, not the device, and sends it in the
     * `X-Kalfa-Caller-Number` SIP header that the ConsoleInbound VoxEngine scenario
     * attaches via `callUser`'s `extraHeaders`. It is an E.164 when the CLI parsed,
     * and the literal `withheld` when it did not — stated explicitly so that "the
     * caller hid their number" is distinguishable from "the header never arrived",
     * which are the same absence otherwise and want opposite handling.
     *
     * WHY NOT JUST `call.number`. On a `callUser` leg that mirrors whatever the
     * scenario passed as `callerid`, which is measured (`call.offer numlen` went
     * 11 → 12 across the 17.8 deploy: our own DID before, the caller's number
     * after). But the scenario has to pass SOME callerid, and for a withheld caller
     * it falls back to our DID — so trusting `call.number` blindly would print OUR
     * number as the caller's, a sharper version of the bug this all started with.
     * It is kept as the fallback for the one case the header cannot cover: an
     * incoming call placed by a scenario version that predates the header.
     */
    private fun displayNumberFrom(headers: Map<String, String>, call: Call): String {
        val header = headerValue(headers, CALLER_NUMBER_HEADER)
        return when {
            header == null -> call.number.trim()   // pre-header scenario — best effort
            header.equals(WITHHELD, ignoreCase = true) -> ""
            else -> header
        }
    }

    // The SDK's IncomingCallListener callback, forwarded via VoxClientManager. Wraps
    // the raw Call in the EXISTING VoxCallSession immediately (state starts RINGING)
    // so an abandoned-before-answer call is observed through the same CallListener
    // path VoxCallSession already has, rather than a second bespoke listener.
    fun handleIncomingCall(call: Call, headers: Map<String, String>) {
        val session = VoxCallSession(
            call,
            connectedState = CallState.ACTIVE,
            // Read here, at the ONE moment it is available. The headers arrive with
            // the offer and nothing retains them afterwards, so a session built
            // without this can never recover the id — which is why it is a
            // constructor argument and not something looked up later.
            consoleCallId = headerValue(headers, CONSOLE_CALL_ID_HEADER)?.takeIf { it.isNotBlank() },
        )
        val offer = IncomingOffer(
            callId = call.id,
            session = session,
            displayName = call.remoteDisplayName ?: "",
            number = call.number,
            displayNumber = displayNumberFrom(headers, call),
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
                Telemetry.emit(
                    TelemetryEvents.CALL_OFFER_SUPERSEDED,
                    "id" to shortId(offer.callId),
                    "was" to shortId(superseded.callId),
                )
            }
        }
        _pendingOffer.value = offer

        // `named`/`numlen`/`hdrnum` rather than the values themselves:
        // remoteDisplayName and number are guest PII and must never reach a log
        // file. Whether they were PRESENT is still diagnostic — an offer arriving
        // with neither is a different shape of problem from one arriving with both.
        //
        // `hdrnum` is the one fact the next live call has to settle: that
        // `extraHeaders` set by a VoxEngine `callUser` actually reaches this
        // SDK's IncomingCallListener. The vendor documents the mechanism for
        // "a SIP phone or WEB SDK" and says nothing explicit about Android, so
        // it is INFERRED until this reads `hdr` rather than `fallback`.
        // `fallback` means the number on screen came from `Call.number` instead
        // — which is right on an ordinary call and WRONG on a withheld one, so
        // this is worth knowing rather than discovering from a screenshot.
        Telemetry.emit(
            TelemetryEvents.CALL_OFFER,
            "id" to shortId(offer.callId),
            "named" to offer.displayName.isNotBlank().toString(),
            "numlen" to offer.number.length.toString(),
            "hdrnum" to when {
                headers.keys.none { it.equals(CALLER_NUMBER_HEADER, ignoreCase = true) } -> "fallback"
                offer.displayNumber.isBlank() -> "withheld"
                else -> "hdr"
            },
        )

        // Read just before the notification is posted, because it decides whether
        // the notification can do anything at all. AGENTS.md §D records that
        // canRingOnLockedScreen may be optimistic on a device whose channel
        // importance was lowered by hand — so all three are recorded, not just the
        // verdict, and the one that is false says which remedy applies.
        RingCapabilityState.refresh(context).let { cap ->
            Telemetry.emit(
                TelemetryEvents.CALL_RING_CAPABILITY,
                "alert" to cap.canAlert.toString(),
                "fsi" to cap.fullScreenIntentAllowed.toString(),
                "locked_ring" to cap.canRingOnLockedScreen.toString(),
            )
        }

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
        // Deliberately AFTER the notify rather than wrapped around it. AOSP rejects
        // a CallStyle notification outright in some states (AGENTS.md §A-1), and
        // catching that here would change this method's behaviour, which is not
        // this change's business. Recorded instead: a trace that reaches
        // call.ring_capability and stops has told you the notify threw.
        Telemetry.emit(TelemetryEvents.CALL_NOTIFY_OK, "id" to shortId(offer.callId))

        // One cleanup path for every way this leg can end (declined, hung up after
        // answer, remote hangup, SDK failure) — see docs §3, "the coordinator observes
        // that single transition once". first{} lets this coroutine complete naturally
        // instead of collecting forever after the call is long over.
        scope.launch {
            // The predicate runs once per emission, so recording inside it yields
            // every state transition while leaving `first`'s completion semantics
            // exactly as they were. A separate collector would have been a second
            // coroutine on a path this file is careful about.
            session.state.first { state ->
                Telemetry.emit(
                    TelemetryEvents.CALL_STATE,
                    "id" to shortId(offer.callId),
                    "s" to state.name,
                )
                state == CallState.DISCONNECTED
            }
            cleanUp(offer.callId)
        }
    }

    fun answer(callId: String) {
        val offer = _pendingOffer.value
        if (offer == null) {
            Telemetry.emit(TelemetryEvents.CALL_ACTION_IGNORED, "a" to "answer", "why" to "no_offer")
            return
        }
        if (!canActOnOffer(offer.callId, callId, offer.session.state.value)) {
            Telemetry.emit(TelemetryEvents.CALL_ACTION_IGNORED, "a" to "answer", "why" to "stale")
            return
        }

        if (!hasRecordAudioPermission()) {
            Telemetry.emit(TelemetryEvents.CALL_NO_RECORD_AUDIO, "id" to shortId(offer.callId))
            // Can't safely claim the microphone FGS type without the permission this
            // app already relies on for every other call leg (CallAudioPermissions) —
            // decline rather than crash inside startForeground(). See docs §3.
            decline(callId)
            return
        }

        Telemetry.emit(TelemetryEvents.CALL_ANSWER, "id" to shortId(offer.callId))
        offer.session.answer()
        answeredCallId = offer.callId
        callEngine.attachIncomingSession(offer.session)
        startCallForegroundService(title = "שיחה פעילה")
        NotificationManagerCompat.from(context).cancel(IncomingCallNotificationBuilder.NOTIFICATION_ID)
        _pendingOffer.value = null
    }

    fun decline(callId: String) {
        val offer = _pendingOffer.value
        if (offer == null) {
            Telemetry.emit(TelemetryEvents.CALL_ACTION_IGNORED, "a" to "decline", "why" to "no_offer")
            return
        }
        if (!canActOnOffer(offer.callId, callId, offer.session.state.value)) {
            Telemetry.emit(TelemetryEvents.CALL_ACTION_IGNORED, "a" to "decline", "why" to "stale")
            return
        }
        Telemetry.emit(TelemetryEvents.CALL_DECLINE, "id" to shortId(offer.callId))
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

        Telemetry.emit(
            TelemetryEvents.CALL_CLEANUP,
            "id" to shortId(callId),
            "fgs_stop" to plan.stopForegroundService.toString(),
        )
        // The trace closes only when NOTHING is left that could still belong to it
        // — the same condition the foreground service is released under. Closing on
        // any leg ending would end the trace of a live call because a second,
        // declined one finished.
        if (plan.stopForegroundService) Telemetry.closeCallSession("leg_ended")
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
            Telemetry.emit(TelemetryEvents.CALL_FGS_START_OK)
        } catch (e: Exception) {
            Log.w(TAG, "call foreground service refused to start: ${e.javaClass.simpleName}: ${e.message}")
            // The SecurityException / ForegroundServiceStartNotAllowedException case
            // AGENTS.md §B-2 is about. It degrades silently by design, and a
            // degradation nobody can see is how a night gets spent — so it gets a
            // line. The CLASS NAME only: the message can quote arbitrary platform
            // text, and this one is not worth widening the PII surface for.
            Telemetry.emit(TelemetryEvents.CALL_FGS_REFUSED, "err" to e.javaClass.simpleName)
        }
    }

    private fun hasRecordAudioPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "VoxIncomingCall"

        // Contract with ConsoleInbound.voxengine.js's ringIdentity() — both sides
        // carry the same two literals and a comment pointing at the other. Changing
        // either without the other silently loses the number from the ring screen,
        // which is why they are named constants here rather than inline strings.
        const val CALLER_NUMBER_HEADER = "X-Kalfa-Caller-Number"
        const val CONSOLE_CALL_ID_HEADER = "X-Kalfa-Console-Call-Id"
        const val WITHHELD = "withheld"
    }
}

/**
 * The first 8 characters of a Voximplant call id.
 *
 * Not PII — it is an opaque platform identifier — but the full value is long
 * enough to wrap a terminal line, and 8 characters is ample to tie two lines to
 * the same leg in a log covering one device over one evening.
 */
internal fun shortId(callId: String): String = callId.take(8)

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

