package com.example.domain.repository

import com.example.domain.model.Call
import com.example.domain.model.Campaign
import com.example.domain.model.CampaignTarget
import com.example.domain.model.RsvpResult
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
