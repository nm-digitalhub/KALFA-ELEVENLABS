package me.kalfa.agentconsole.domain.repository

import me.kalfa.agentconsole.domain.model.Call
import me.kalfa.agentconsole.domain.model.Campaign
import me.kalfa.agentconsole.domain.model.CampaignTarget
import me.kalfa.agentconsole.domain.model.RsvpResult
import kotlinx.coroutines.flow.StateFlow

interface CallRepository {
    fun refresh() {}
    suspend fun getCallAnalysis(callId: String): me.kalfa.agentconsole.domain.model.CallAnalysis? = null
    val eventNames: kotlinx.coroutines.flow.StateFlow<Map<String, String>>
        get() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    val events: kotlinx.coroutines.flow.StateFlow<List<me.kalfa.agentconsole.domain.model.ConsoleEvent>>
        get() = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    val liveCalls: StateFlow<List<Call>>
    val callHistory: StateFlow<List<Call>>
    
    fun updateCall(call: Call)
    fun addCall(call: Call)
}

interface CampaignRepository {
    fun refresh() {}
    val campaigns: StateFlow<List<Campaign>>
    fun getTargets(campaignId: String): StateFlow<List<CampaignTarget>>
    fun toggleCampaign(campaignId: String)
    fun updateTargetResult(targetId: String, result: String)
}

interface RsvpRepository {
    fun refresh() {}
    val rsvpResults: StateFlow<List<RsvpResult>>
    fun saveRsvpResult(result: RsvpResult)
}
