package com.example.di

import com.example.BuildConfig
import com.example.data.*
import com.example.data.mock.*
import com.example.domain.repository.*
import com.example.domain.telephony.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.auth.Auth

object DependencyContainer {
    val isSupabaseConfigured: Boolean by lazy {
        try {
            val url = BuildConfig.SUPABASE_URL
            val key = BuildConfig.SUPABASE_ANON_KEY
            url.isNotEmpty() && !url.contains("placeholder") && key.isNotEmpty() && !key.contains("placeholder")
        } catch (e: Exception) {
            false
        }
    }

    val supabaseClient by lazy {
        if (isSupabaseConfigured) {
            try {
                createSupabaseClient(
                    supabaseUrl = BuildConfig.SUPABASE_URL,
                    supabaseKey = BuildConfig.SUPABASE_ANON_KEY
                ) {
                    install(Postgrest)
                    install(Realtime)
                    install(Auth)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }
    }

    val callRepository: CallRepository by lazy {
        val client = supabaseClient
        if (client != null) {
            SupabaseCallRepository(client)
        } else {
            MockCallRepositoryImpl()
        }
    }

    val campaignRepository: CampaignRepository by lazy {
        val client = supabaseClient
        if (client != null) {
            SupabaseCampaignRepository(client)
        } else {
            MockCampaignRepositoryImpl()
        }
    }

    val rsvpRepository: RsvpRepository by lazy {
        val client = supabaseClient
        if (client != null) {
            SupabaseRsvpRepository(client)
        } else {
            MockRsvpRepositoryImpl()
        }
    }
    
    private val mockCallEngine: MockCallEngineImpl by lazy {
        MockCallEngineImpl(callRepository, rsvpRepository)
    }

    private val supabaseCallEngine: SupabaseCallEngineImpl? by lazy {
        val client = supabaseClient
        if (client != null) {
            SupabaseCallEngineImpl(client, callRepository, rsvpRepository)
        } else {
            null
        }
    }
    
    val callEngine: CallEngine get() = supabaseCallEngine ?: mockCallEngine
    val agentPresence: AgentPresence get() = (supabaseCallEngine ?: mockCallEngine) as AgentPresence
}
