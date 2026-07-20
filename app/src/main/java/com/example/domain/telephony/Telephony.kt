package com.example.domain.telephony

import com.example.domain.model.AgentStatus
import com.example.domain.model.CallState
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
}

interface AgentPresence {
    val currentStatus: StateFlow<AgentStatus>
    fun setStatus(status: AgentStatus)
}
