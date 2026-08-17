package me.kalfa.agentconsole.domain.telephony

import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.model.CallState
import me.kalfa.agentconsole.domain.error.AppResult
import me.kalfa.agentconsole.domain.model.CallDispatchStatus
import me.kalfa.agentconsole.domain.model.OutboundDispatchReceipt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface CallSession {
    val id: String
    val customerPhone: String
    val customerName: String
    val state: StateFlow<CallState>
    val isMuted: StateFlow<Boolean>
    val isHeld: StateFlow<Boolean>
    val durationSec: StateFlow<Int>

    /**
     * Whether the media path to the Voximplant cloud is DOWN right now on a leg that
     * is otherwise still up — the SDK's `onCallReconnecting` / `onCallReconnected`
     * pair (verified live against
     * `voximplant.com/api/v2/getDoc?fqdn=references.androidsdk3.android.sdk.calls.calllistener`
     * on 2026-08-15: "Triggered when the connection to the Voximplant Cloud is lost
     * due to a network issues and media streams may be interrupted").
     *
     * Separate from [state] rather than a new [CallState] entry, deliberately. The
     * existing enum is shared with AI-call rows read from the database and is matched
     * exhaustively in several screens; a new constant would change the meaning of
     * those rows and break those `when`s for a signal that only ever applies to an
     * SDK leg. This is additive and every other implementation keeps compiling.
     *
     * Why it must be surfaced at all: without it a mid-call media drop is invisible.
     * `VoxCallSession.mapState` folds the SDK's `Reconnecting` into `RINGING`, so a
     * call whose audio has just died renders as "מחייג..." beside a duration that
     * keeps counting up — the screen actively reassures the agent while the guest
     * hears nothing.
     *
     * Default (a constant false) keeps mock mode and every other implementer
     * compiling — see [DefaultSessionFlows] for why it must be a shared value and
     * not a fresh one.
     */
    val isReconnecting: StateFlow<Boolean>
        get() = DefaultSessionFlows.notReconnecting

    /**
     * Flips to `true` the moment the platform REFUSES a [hold] attempt (the SDK's
     * `CallCallback.onFailure`, real per-call feedback — `Call.hold` is not a
     * fire-and-forget command), and back to `false` right before every new attempt.
     * The reset-before-attempt half is deliberate: a `StateFlow` conflates equal
     * consecutive values, so without it a SECOND refusal in a row (the value
     * already sitting at `true`) would never re-emit and a collector watching for
     * the transition would silently miss it.
     *
     * Why this exists at all: `hold()` returns `Unit` — it cannot report failure
     * through its own call site, and `ActiveCallScreen` used to have no hold
     * control at all for exactly that reason (see its header comment's former "NO
     * hold" bullet — "a refused hold is a button that does nothing with no
     * explanation"). Before this, the SDK's own `isOnHold` was still polled every
     * second by the durationSec ticker and would silently correct a stale toggle
     * within ~1s, which fixes the STATE but not the SILENCE — an agent who tapped
     * hold, watched nothing happen, and got no explanation had no way to tell "the
     * platform said no" from "I haven't waited long enough". This is the signal a
     * caller (`ConsoleViewModel`) uses to say so.
     *
     * Default (a constant false) keeps mock mode and every other implementer
     * compiling — see [DefaultSessionFlows] for why it must be a shared value and
     * not a fresh one. `MockCallSession.hold` never fails, so the default is
     * correct there, not merely convenient.
     */
    val holdRefused: StateFlow<Boolean>
        get() = DefaultSessionFlows.holdNotRefused

    /**
     * The `console_calls` row id for this call, or null when it is not known.
     *
     * This is the address for every live-call action the SERVER performs on the
     * agent's behalf — transfer, consult, conference are each
     * `POST /api/console-calls/{id}/…`. Without it those controls have nothing to
     * aim at, and the app has no other way to work it out: its own live-call list
     * comes from `console_call_feed`, which is keyed on `call_attempts` (the AI
     * campaign calls), a different table describing different calls. Matching an
     * SDK leg to a `console_calls` row by phone number or timing would be a guess
     * about which call the agent is on.
     *
     * So the server states it outright: the ConsoleInbound scenario sends it as the
     * `X-Kalfa-Console-Call-Id` SIP header on the ring, and
     * `VoxIncomingCallCoordinator` reads it off the offer.
     *
     * Null is a real, expected value, not just a mock convenience — a leg from a
     * scenario version predating that header has none, and every caller must treat
     * "no id" as "these controls are unavailable for this call" rather than
     * guessing. Default null keeps mock mode and the monitor/takeover legs
     * compiling.
     */
    val consoleCallId: String?
        get() = null

    fun mute(muted: Boolean)
    fun hold(held: Boolean)
    fun sendDtmf(digit: String)
    fun hangup()

    /**
     * Answers a session that arrived via CallEngine.attachIncomingSession while still
     * RINGING — an inbound call Voximplant offered directly to this agent's device
     * (see VoxIncomingCallCoordinator, docs/android-presence-and-call-ux.md §3).
     * Default no-op: a monitor/takeover leg is already connected by the time it is
     * exposed here, so it never needs answering.
     */
    fun answer() {}

    /**
     * Declines a still-RINGING inbound offer. Default falls back to hangup() so a
     * caller that doesn't distinguish the two still tears the leg down.
     */
    fun decline() { hangup() }
}

interface CallEngine {
    val currentSession: StateFlow<CallSession?>
    val activeAiCallsCount: StateFlow<Int>
    val queueDepth: StateFlow<Int>
    // Same shared-default rule as AgentPresence's — see DefaultPresenceFlows. This one
    // had the identical fresh-flow-per-read defect and is fixed with it.
    val dispatchStatuses: StateFlow<Map<String, CallDispatchStatus>>
        get() = DefaultEngineFlows.noDispatchStatuses
    
    /**
     * Places a real agent-initiated SDK leg — `VICalls.createCall(destination, ...)`
     * — and returns the wrapping [CallSession] synchronously, matching the SDK's own
     * `createCall`/`start()`, which are themselves synchronous; everything after
     * that (ringing -> connected -> disconnected) arrives through the session's own
     * listener. [phone] is loosely named for the interface's oldest intended caller
     * but is really "whatever destination string the Voximplant rule table for
     * `kalfa-rsvp` is meant to route" — see `voxfiles/applications/…/rules.config.json`
     * in `beta`: `ct[0-9a-f]+` (a one-time dial token minted by
     * `POST /api/console-calls/dial-intent`, ConsoleOut rule) or `agent_<uuid>` (a
     * colleague's own SDK identity, ConsoleInternal rule) both route to
     * `ConsoleDial.voxengine.js`; anything else falls through to the catch-all
     * `OutCall` rule and lands in the AI RSVP scenario instead — silently wrong,
     * not a thrown error. **A raw PSTN phone number the agent typed is NOT a valid
     * destination and must never be passed here**: `dial-intent`'s own schema
     * comment states that shape has "no representation … on purpose", by deliberate
     * platform decision (no code path exists for an unconsent-checked cold call).
     * The implementation is free to throw for any destination it cannot honour.
     */
    fun startOutboundCall(phone: String, customerName: String): CallSession
    fun monitorCall(callId: String): CallSession
    fun takeoverCall(callId: String): CallSession

    /**
     * Sends a management command to the ElevenLabs AgentsClient of a live AI call,
     * via POST beta.kalfa.me/api/calls/{id}/agent-command. Commands:
     * contextual_update {text} | user_message {text} | clear_buffer | close_agent.
     * Default no-op keeps mock mode working.
     */
    suspend fun sendAgentCommand(
        callId: String,
        command: String,
        payload: Map<String, String> = emptyMap()
    ): AppResult<Unit> = AppResult.Success(Unit)

    /**
     * Hangs up a LIVE AI call — the conversation with the guest — via POST
     * beta.kalfa.me/api/calls/{id}/end. Distinct from sendAgentCommand("close_agent"),
     * which only closes the AI leg. 2xx means the hangup was DELIVERED; teardown is
     * async and the call row is the record of the outcome. Default no-op keeps mock
     * mode working.
     */
    suspend fun endCall(callId: String): AppResult<Unit> = AppResult.Success(Unit)

    /**
     * Enqueues ONE real outbound AI call to an EXISTING guest, via POST
     * beta.kalfa.me/api/events/{eventId}/outreach-call {guest_id}. ENQUEUE-ONLY:
     * the backend worker owns every gate (consent/DNC/reached/campaign-active/
     * caps/balance/event-day) + the actual dial. 2xx = queued (not yet dialing);
     * the call surfaces in the feed once the worker dials. Default no-op keeps mock
     * mode working.
     */
    suspend fun enqueueOutboundCall(
        eventId: String,
        guestId: String
    ): AppResult<OutboundDispatchReceipt> = AppResult.Failure(
        me.kalfa.agentconsole.domain.error.AppFailure.Unknown
    )

    /**
     * Publishes a CallSession that originated from an incoming SDK call — see
     * VoxIncomingCallCoordinator — into currentSession, so the rest of the app
     * (ConsoleViewModel/ConsoleUiState) observes it through the SAME abstraction as
     * any other call, rather than a parallel one. Default no-op keeps mock mode and
     * any other CallEngine implementation compiling unchanged.
     */
    fun attachIncomingSession(session: CallSession) {}

    /**
     * Clears currentSession once an attached incoming call ends (declined, hung up,
     * or failed) — called by VoxIncomingCallCoordinator's single cleanup path, not by
     * each individual answer/decline/hangup call site. Default no-op.
     */
    fun clearAttachedSession() {}

    // ── Live-call handoff: transfer / consult / conference ────────────────────
    //
    // All five act on a `console_calls` row via the server, never on the SDK leg
    // directly, because the topology change happens in the VoxEngine scenario:
    // each route posts a command envelope to the live session and the scenario
    // rewires the media. The device only asks.
    //
    // `consoleCallId` is [CallSession.consoleCallId] — see its kdoc for why the app
    // cannot derive it and the server has to state it. A null id means these are
    // unavailable for that call, which callers must surface rather than paper over.
    //
    // 2xx from any of them means DELIVERED to the live session, NOT that the
    // topology changed. The scenario reports the real outcome out-of-band
    // (transfer_started/transferred/transfer_failed and the consult/conference
    // equivalents), exactly like sendAgentCommand above. A UI that claims success
    // on the HTTP response is claiming something it has not been told.
    //
    // Defaults keep mock mode and every other implementation compiling.

    /** Agents this call can be handed to right now — GET /api/agents/transfer-targets. */
    suspend fun loadTransferTargets(): AppResult<List<TransferTarget>> =
        AppResult.Success(emptyList())

    /** Blind transfer — POST /api/console-calls/{id}/transfer. The agent leaves the call. */
    suspend fun transferCall(consoleCallId: String, toAgentId: String): AppResult<Unit> =
        AppResult.Success(Unit)

    /**
     * Consult — POST /api/console-calls/{id}/consult. Puts the CUSTOMER on hold and
     * bridges this agent privately with the target, so the customer hears neither
     * side. Ends via [cancelConsult] (back to the customer) or [completeConsult]
     * (a warm transfer: this agent drops, the target takes the customer).
     */
    suspend fun startConsult(consoleCallId: String, toAgentId: String): AppResult<Unit> =
        AppResult.Success(Unit)

    /**
     * Consult an outside PHONE NUMBER rather than a console agent — a manager, a
     * supplier, the event owner.
     *
     * The number is sent as typed and validated SERVER-side (E.164, an
     * Israel-only country allowlist, a per-agent rate limit and the DNC list). The
     * device deliberately does no validation of its own beyond "not blank": a
     * client-side rule would either duplicate that policy and drift from it, or
     * teach an agent a shape the server then rejects for a different reason.
     *
     * There is no transferTo equivalent, and that is deliberate — see the server
     * route: a blind transfer to an unverified number would leave a customer alone
     * with someone the platform has no record of.
     */
    suspend fun startConsultWithPhone(consoleCallId: String, phone: String): AppResult<Unit> =
        AppResult.Success(Unit)

    /** Abandons a consult and restores the customer bridge — POST …/consult/cancel. */
    suspend fun cancelConsult(consoleCallId: String): AppResult<Unit> = AppResult.Success(Unit)

    /** Completes a consult as a warm transfer — POST …/consult/complete. */
    suspend fun completeConsult(consoleCallId: String): AppResult<Unit> = AppResult.Success(Unit)

    /**
     * Conference — POST /api/console-calls/{id}/conference. Unlike a consult the
     * customer is NOT put on hold; the target joins all three into one mixer.
     */
    suspend fun addToConference(consoleCallId: String, toAgentId: String): AppResult<Unit> =
        AppResult.Success(Unit)

    /** Conference in an outside PHONE NUMBER — same contract as [startConsultWithPhone]. */
    suspend fun addToConferenceWithPhone(consoleCallId: String, phone: String): AppResult<Unit> =
        AppResult.Success(Unit)

    /**
     * Drops the third participant and collapses back to agent<->customer —
     * POST /api/console-calls/{id}/conference/remove.
     *
     * Takes no target: a conference here has at most one additional participant, so
     * there is nothing to name. The scenario handles both states — still ringing, or
     * already mixed — and picks the right teardown.
     */
    suspend fun removeFromConference(consoleCallId: String): AppResult<Unit> =
        AppResult.Success(Unit)

    // ── Missed calls ──────────────────────────────────────────────────────────

    /**
     * The open missed-call queue — GET /api/agents/callbacks.
     *
     * Every inbound call nobody answered records one of these server-side. Each
     * carries the caller's number for DISPLAY, including for someone who has never
     * been a customer and has no name attached.
     */
    suspend fun loadPendingCallbacks(): AppResult<List<PendingCallback>> =
        AppResult.Success(emptyList())

    /**
     * Returns a missed call, by callback id.
     *
     * The ID, never the number. POST /api/console-calls/dial-intent re-reads the
     * phone server-side and runs the whole consent chain — DNC, opt-out, quiet hours,
     * Shabbat, caps, balance — before minting a one-time dial token, and dial-intent
     * has no request shape that accepts a raw phone at all. So the device asks "call
     * back request X" and never "dial this number".
     *
     * On success the new leg is already attached as the current session, so the
     * active-call screen shows it the same way an answered inbound call does.
     */
    /**
     * Dials a number a human typed.
     *
     * The shape that did not exist. `dial-intent`'s union was built so the AI
     * campaign could never cold-call, and that rule ended up refusing the OWNER
     * dialling from their own console — a console that could not do what the handset
     * beside it does. DNC still applies server-side, and every attempt is audited.
     */
    suspend fun dialManual(phone: String, confirmOutsideHours: Boolean = false): AppResult<Unit> =
        AppResult.Failure(me.kalfa.agentconsole.domain.error.AppFailure.Unknown)

    suspend fun returnCallback(
        callbackId: String,
        confirmOutsideHours: Boolean = false,
    ): AppResult<Unit> =
        AppResult.Failure(me.kalfa.agentconsole.domain.error.AppFailure.Unknown)

    // ── Call history ──────────────────────────────────────────────────────────

    /**
     * The console's own recent calls — GET /api/agents/call-history.
     *
     * NOT the same list the history screen used to show. That one came from
     * `console_call_feed`, keyed on `call_attempts`: the AI campaign calls, rendered
     * without name or number because that feed carries no PII. These are the calls a
     * human actually took or placed, with who each one was with.
     */
    suspend fun loadCallHistory(
        filter: ConsoleHistoryFilter = ConsoleHistoryFilter(),
    ): AppResult<List<ConsoleCallRecord>> = AppResult.Success(emptyList())

    /**
     * Calls a past contact back, by the pair the history row carries.
     *
     * Same two-step shape as [returnCallback] and the same reason for it: the device
     * names a CONTACT, dial-intent resolves the number and runs the consent chain,
     * and only then is a one-time token minted. Offered only for rows where both ids
     * are present — see [ConsoleCallRecord.dialable].
     */
    suspend fun dialContact(
        eventId: String,
        contactId: String,
        confirmOutsideHours: Boolean = false,
    ): AppResult<Unit> =
        AppResult.Failure(me.kalfa.agentconsole.domain.error.AppFailure.Unknown)
}

/**
 * One past console call, as the history screen shows it.
 *
 * [name] is null for a caller we do not recognise, and the screen then shows the
 * number — for someone who has never been a customer that IS their identity. [phone]
 * is null only when the caller withheld it.
 */
/**
 * How the call log is narrowed.
 *
 * Time, outcome and direction — the axes a phone log is actually read along. It
 * REPLACES an event picker that sat above this list, and the replacement is not a
 * matter of taste: that picker filtered nothing (MainActivity passed the unfiltered
 * list straight through while filtering only the RSVP tab beside it), and even
 * working it would have been the wrong question. Measured on the live database
 * 2026-08-17: of 1,241 inbound calls in a week, 28 had any event attached. An axis
 * that classifies 2% of the rows leaves 98% in an "other" pile.
 *
 * Every filter here is applied SERVER-SIDE. Narrowing a page of rows already in
 * hand would answer "no calls" for a range full of them, which is the failure this
 * screen just had in another form.
 */
enum class HistoryRange(val labelHebrew: String, val days: Int) {
    TODAY("היום", 1),
    WEEK("שבוע", 7),
    MONTH("חודש", 30),
}

enum class HistoryOutcome(val labelHebrew: String, val wire: String?) {
    ALL("הכל", null),
    ANSWERED("נענו", "answered"),
    MISSED("לא נענו", "missed"),
}

enum class HistoryDirection(val labelHebrew: String, val wire: String?) {
    ALL("הכל", null),
    INBOUND("נכנסות", "inbound"),
    OUTBOUND("יוצאות", "outbound"),
}

/**
 * What the agent asked for.
 *
 * THE PRESETS ARE NOT THE FILTER. An earlier version offered only היום/שבוע/חודש
 * and called that filtering — three drawers where the platform accepts a date
 * range to the second, a specific number, and a duration band. The presets remain
 * as shortcuts because "today" is what an agent wants most mornings, but every
 * axis the API supports is now reachable:
 *
 *   from/to        exact window, hours included ('YYYY-MM-DD HH:mm:ss' upstream)
 *   phone          one caller, narrowed on Voximplant's side
 *   min/max sec    duration band, also server-side
 *   outcome        computed from the legs (the API has no parameter for it)
 *   direction      inbound / outbound
 *
 * `from`/`to` are epoch milliseconds. When either is set it WINS over `range`,
 * so a chosen window is never quietly widened back to a preset.
 */
data class ConsoleHistoryFilter(
    val range: HistoryRange = HistoryRange.WEEK,
    val outcome: HistoryOutcome = HistoryOutcome.ALL,
    val direction: HistoryDirection = HistoryDirection.ALL,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    /** E.164. Anything else is refused before it is sent. */
    val phone: String? = null,
    val minDurationSec: Int? = null,
    val maxDurationSec: Int? = null,
) {
    val hasExplicitWindow: Boolean get() = fromMs != null || toMs != null

    /**
     * True when nothing is narrowed. Drives the empty-state wording: "אין שיחות"
     * under an active filter reads as a broken screen, so the two are worded apart.
     */
    val isDefault: Boolean
        get() = outcome == HistoryOutcome.ALL &&
            direction == HistoryDirection.ALL &&
            !hasExplicitWindow &&
            phone == null &&
            minDurationSec == null &&
            maxDurationSec == null

    /** How many narrowing choices are active — shown on the filter button. */
    val activeCount: Int
        get() = listOf(
            outcome != HistoryOutcome.ALL,
            direction != HistoryDirection.ALL,
            hasExplicitWindow,
            phone != null,
            minDurationSec != null || maxDurationSec != null,
        ).count { it }

    /** Clears everything but keeps the preset window the agent is looking at. */
    fun cleared(): ConsoleHistoryFilter = ConsoleHistoryFilter(range = range)
}

/**
 * A LENIENT sanity check, not a format rule.
 *
 * The canonical form is produced ON THE SERVER by libphonenumber-js with region
 * IL, which is the same helper the dial path uses — so 0536212562,
 * 972536212562 and +972536212562 all become +972536212562 and mean one person.
 * Re-implementing E.164 here would have rejected the first two while the rest of
 * the system treats them as identical, which is a filter that refuses a valid
 * number and cannot say why.
 *
 * So this only catches input that is obviously not a phone number at all, and
 * everything else is normalized where the normalizer actually lives.
 */
fun looksLikePhoneNumber(raw: String): Boolean {
    val digits = raw.count(Char::isDigit)
    return digits in 7..15 && raw.trim().all { it.isDigit() || it in "+-() " }
}

/**
 * What Voximplant reports happened, per call.
 *
 * FOUR values, not two, because "not answered" covered populations that call for
 * opposite responses. Measured over 1,913 sessions in one week (2026-08-17):
 * ANSWERED 12, MISSED 157, ABANDONED 1,073, REJECTED 671. Folding the last two in
 * with MISSED is what made the one list worth acting on unreadable.
 */
enum class CallOutcome(val wire: String) {
    /** An agent was on the call. */
    ANSWERED("answered"),
    /** Agents were rung, nobody picked up. The list to act on. */
    MISSED("missed"),
    /** We answered — disclosure, hold music — and the caller hung up before any
     *  agent was rung. A real person who gave up. */
    ABANDONED("abandoned"),
    /** Never answered at all; the platform refused the leg. Flood traffic. */
    REJECTED("rejected"),
    /** Outbound that never connected. */
    FAILED("failed"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(v: String?): CallOutcome = entries.firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

data class ConsoleCallRecord(
    val id: String,
    val inbound: Boolean,
    /** Voximplant's own verdict, not ours. See [CallOutcome]. */
    val outcome: CallOutcome = CallOutcome.UNKNOWN,
    /**
     * The SIP code that decided it — 480 the agent's app was offline, 603 declined,
     * 486 busy, 200 normal. Per LEG, from the platform, which is strictly more than
     * the single reason string our own table used to record for a whole session.
     */
    val endCode: Int? = null,
    val endDetails: String? = null,
    /** How many agents were rung. 0 with outcome ABANDONED means nobody was. */
    val agentLegsTried: Int = 0,
    /** Seconds an agent was actually on the call — not the session length, which
     *  includes the disclosure and the hold music nobody was listening to. */
    val talkSec: Int = 0,
    val hasRecording: Boolean = false,
    val name: String?,
    val phone: String?,
    val startedAt: String,
    val durationSec: Int,
    val answered: Boolean,
    val eventId: String?,
    val contactId: String?,
) {
    /**
     * Whether a call-back button may be offered.
     *
     * ANY INBOUND CALL WITH A NUMBER, which is the change: it used to require
     * `eventId != null && contactId != null`, and on a business line that is 28 rows
     * out of 1,241 measured in a week. The other 98% showed no button at all — on a
     * call log, where returning a call is the main thing an owner does with one.
     *
     * That condition existed because the only dial shape available needed an event
     * and a contact of ours. `returned_call` needs neither: the server takes this
     * row's Voximplant session id and reads the number back from Voximplant's own
     * record, then runs the consent chain on it.
     *
     * ANY row with a number, inbound or outbound. Outbound rows used to be excluded
     * because `returned_call` refuses them (correctly — a call we placed is not
     * somebody who rang us), but that left a redial button missing from a call log,
     * which is absurd. They now dial through the `manual` shape instead.
     *
     * A withheld CLI still has nothing to ring.
     */
    val dialable: Boolean get() = phone != null

    /**
     * Which dial shape this row uses.
     *
     * Inbound goes through `returned_call`, which resolves the number from
     * Voximplant's own record of the session — the device names a session, never a
     * number. Outbound has no inbound leg to resolve, so it redials the number
     * itself through `manual`.
     */
    val redialsByNumber: Boolean get() = !inbound
}

/**
 * Someone waiting to be called back. Mirrors GET /api/agents/callbacks.
 *
 * [phone] is present so the agent can see WHO they are about to ring — the whole
 * point for a caller the platform has no record of. Display-only; the dial goes
 * by [id].
 */
data class PendingCallback(
    val id: String,
    val fullName: String,
    val phone: String,
    val topic: String?,
    val createdAt: String,
)

/** A colleague a live call can be handed to. Mirrors GET /api/agents/transfer-targets. */
data class TransferTarget(
    val agentId: String,
    val displayName: String,
)

/**
 * Whether the LAST setStatus/setShiftActive attempt actually reached and was
 * accepted by the server — distinct from AgentPresence.currentStatus/shiftActive,
 * which reflect what was last REQUESTED (updated optimistically, for immediate UI
 * responsiveness). Measured live incident that made this necessary: an agent
 * reinstalled the app (clearing the Supabase session), tapped "זמין", and the app
 * showed Ready — but setStatus's old fire-and-forget implementation sent an empty
 * JWT, got a 401, and silently discarded it in a bare `catch { printStackTrace() }`.
 * Nothing on screen contradicted the false "Ready" claim; three DB samples a minute
 * apart confirmed no write ever reached the server. A UI (in particular the
 * persistent presence notification, which is the ONLY surface a backgrounded agent
 * sees) must be able to tell "confirmed" from "requested" apart.
 */
sealed interface PresenceSyncState {
    data object Synced : PresenceSyncState
    data object Pending : PresenceSyncState
    data class Failed(val failure: me.kalfa.agentconsole.domain.error.AppFailure) : PresenceSyncState
}

/**
 * The constant flows [AgentPresence]'s defaults hand back.
 *
 * An interface cannot hold a backing field, so an expression-bodied default getter
 * re-evaluates its right-hand side on EVERY read. Both defaults below used to be
 * `get() = MutableStateFlow(...)`, which meant an implementer relying on them returned a
 * brand-new flow each time: a value that can never change, is never the same object
 * twice, and quietly defeats any `collect` or `combine` built on it.
 *
 * Real builds override both, so this was never a production fault — but `MockCallEngineImpl`
 * does not override `shiftActive`, so in DEBUG mock mode `shiftActive` was permanently
 * false: `MainActivity`'s effect never started `PresenceForegroundService`, and had the
 * service started anyway its `shiftActive.filter { !it }` watcher would have stopped it
 * immediately. Presence was simply dead there, with nothing to see.
 *
 * Shared singletons instead, so a default is a stable value rather than a factory.
 */
private object DefaultEngineFlows {
    val noDispatchStatuses: StateFlow<Map<String, CallDispatchStatus>> = MutableStateFlow(emptyMap())
}

/**
 * The constant flows [CallSession.isReconnecting] and [CallSession.holdRefused] hand
 * back. Same rule, and same reason, as [DefaultEngineFlows] and [DefaultPresenceFlows]:
 * an interface holds no backing field, so `get() = MutableStateFlow(false)` would mint a
 * fresh flow on every read — a value that can never change and is never the same object
 * twice, which quietly defeats any `collect` or `combine` built on it.
 */
private object DefaultSessionFlows {
    val notReconnecting: StateFlow<Boolean> = MutableStateFlow(false)
    val holdNotRefused: StateFlow<Boolean> = MutableStateFlow(false)
}

private object DefaultPresenceFlows {
    val shiftInactive: StateFlow<Boolean> = MutableStateFlow(false)
    val synced: StateFlow<PresenceSyncState> = MutableStateFlow(PresenceSyncState.Synced)
}

interface AgentPresence {
    val currentStatus: StateFlow<AgentStatus>

    /**
     * Readback of the last setShiftActive call, for UI/service code that needs to
     * know whether presence should be running RIGHT NOW (PresenceForegroundService's
     * start/stop trigger — see docs/android-presence-and-call-ux.md §1) rather than
     * just being able to fire a one-way declaration. Default (a constant false that
     * nothing ever updates) keeps mock mode and any other implementer compiling — see
     * DefaultPresenceFlows for why it must be a shared value and not a fresh one.
     */
    val shiftActive: StateFlow<Boolean>
        get() = DefaultPresenceFlows.shiftInactive

    /**
     * See PresenceSyncState's kdoc. Covers the outcome of the most recent
     * setStatus/setShiftActive call (both write through the same field — see
     * SupabaseImplementations.kt for why one shared signal is the deliberate v1
     * scope, not an oversight). Default (always Synced) keeps mock mode working — see
     * DefaultPresenceFlows for why it must be a shared value and not a fresh one.
     */
    val syncState: StateFlow<PresenceSyncState>
        get() = DefaultPresenceFlows.synced

    /**
     * Sets the agent's status, via POST beta.kalfa.me/api/agents/status + a direct
     * postgrest upsert. Returns AppResult like every other network-backed operation
     * on CallEngine (sendAgentCommand/endCall/enqueueOutboundCall) — this used to be
     * a fire-and-forget `Unit` that swallowed every failure; see PresenceSyncState's
     * kdoc for the measured incident that made that untenable. currentStatus still
     * updates optimistically for immediate UI responsiveness; syncState is the truth.
     */
    suspend fun setStatus(status: AgentStatus): AppResult<Unit>

    /**
     * Declares (or withdraws) a standing "on shift" intent, via POST
     * beta.kalfa.me/api/agents/shift {active}. Deliberately separate from
     * setStatus/agent_status (its own table, its own 12h freshness window,
     * server-side): route-inbound-retry reads it to decide who gets woken by an
     * inbound-call push even while nobody is currently connected (AGENTS.md "Push
     * wake-up"). Default no-op (success) keeps mock mode working.
     */
    suspend fun setShiftActive(active: Boolean): AppResult<Unit> = AppResult.Success(Unit)
}
