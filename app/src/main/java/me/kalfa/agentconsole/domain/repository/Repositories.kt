package me.kalfa.agentconsole.domain.repository

import me.kalfa.agentconsole.domain.model.Call
import me.kalfa.agentconsole.domain.model.Campaign
import me.kalfa.agentconsole.domain.model.CampaignTarget
import me.kalfa.agentconsole.domain.model.RsvpResult
import kotlinx.coroutines.flow.StateFlow

interface CallRepository {
    val liveCalls: StateFlow<List<Call>>
    val callHistory: StateFlow<List<Call>>
    
    fun updateCall(call: Call)
    fun addCall(call: Call)
}

interface CampaignRepository {
    val campaigns: StateFlow<List<Campaign>>
    fun getTargets(campaignId: String): StateFlow<List<CampaignTarget>>
    fun toggleCampaign(campaignId: String)
    fun updateTargetResult(targetId: String, result: String)
}

interface RsvpRepository {
    val rsvpResults: StateFlow<List<RsvpResult>>
    fun saveRsvpResult(result: RsvpResult)
}
