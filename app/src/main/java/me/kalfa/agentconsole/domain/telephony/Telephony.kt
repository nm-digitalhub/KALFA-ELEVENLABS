package me.kalfa.agentconsole.domain.telephony

import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.model.CallState
import me.kalfa.agentconsole.domain.error.AppResult
import me.kalfa.agentconsole.domain.model.CallDispatchStatus
import me.kalfa.agentconsole.domain.model.OutboundDispatchReceipt
import kotlinx.coroutines.flow.StateFlow

interface CallSession {
    val id: String
    val customerPhone: String
    val customerName: String
    val state: StateFlow<CallState>
    val isMuted: StateFlow<Boolean>
    val isHeld: StateFlow<Boolean>
    val durationSec: StateFlow<Int>

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
    val dispatchStatuses: StateFlow<Map<String, CallDispatchStatus>>
        get() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    
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
}

interface AgentPresence {
    val currentStatus: StateFlow<AgentStatus>

    /**
     * Readback of the last setShiftActive call, for UI/service code that needs to
     * know whether presence should be running RIGHT NOW (PresenceForegroundService's
     * start/stop trigger — see docs/android-presence-and-call-ux.md §1) rather than
     * just being able to fire a one-way declaration. Default (a StateFlow(false) that
     * nothing ever updates) keeps mock mode and any other implementer compiling.
     */
    val shiftActive: StateFlow<Boolean>
        get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun setStatus(status: AgentStatus)

    /**
     * Declares (or withdraws) a standing "on shift" intent, via POST
     * beta.kalfa.me/api/agents/shift {active}. Deliberately separate from
     * setStatus/agent_status (its own table, its own 12h freshness window,
     * server-side): route-inbound-retry reads it to decide who gets woken by an
     * inbound-call push even while nobody is currently connected (AGENTS.md "Push
     * wake-up"). Default no-op keeps mock mode working.
     */
    fun setShiftActive(active: Boolean) {}
}
