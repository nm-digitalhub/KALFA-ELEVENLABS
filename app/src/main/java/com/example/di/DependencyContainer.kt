package com.example.di

import com.example.data.mock.*
import com.example.domain.repository.*
import com.example.domain.telephony.*

object DependencyContainer {
    val callRepository: CallRepository by lazy { MockCallRepositoryImpl() }
    val campaignRepository: CampaignRepository by lazy { MockCampaignRepositoryImpl() }
    val rsvpRepository: RsvpRepository by lazy { MockRsvpRepositoryImpl() }
    
    val mockCallEngine: MockCallEngineImpl by lazy {
        MockCallEngineImpl(callRepository, rsvpRepository)
    }
    
    val callEngine: CallEngine get() = mockCallEngine
    val agentPresence: AgentPresence get() = mockCallEngine
}
