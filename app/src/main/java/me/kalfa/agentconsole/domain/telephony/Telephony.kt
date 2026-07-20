package me.kalfa.agentconsole.domain.telephony

import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.model.CallState
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
}

interface CallEngine {
    val currentSession: StateFlow<CallSession?>
    val activeAiCallsCount: StateFlow<Int>
    val queueDepth: StateFlow<Int>
    
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
    ): Boolean = false
}

interface AgentPresence {
    val currentStatus: StateFlow<AgentStatus>
    fun setStatus(status: AgentStatus)
}
