package me.kalfa.agentconsole.di

import me.kalfa.agentconsole.BuildConfig
import me.kalfa.agentconsole.data.LiveTranscriptManager
import me.kalfa.agentconsole.data.*
import me.kalfa.agentconsole.data.mock.*
import me.kalfa.agentconsole.domain.repository.*
import me.kalfa.agentconsole.domain.telephony.*
import me.kalfa.agentconsole.telephony.vox.VoxClientManager
import me.kalfa.agentconsole.telephony.vox.VoxSdkAuthClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

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

    // Release fail-closed: the mock implementations are a DEBUG-only convenience.
    // A release build that isn't configured for Supabase must NOT silently boot into
    // fabricated data — serving mock calls/guests as if they were real is exactly the
    // dishonest failure this guard prevents. In release we fail loudly instead; a
    // misconfigured release (secrets not injected) is a build error to fix, not a
    // state to paper over with demo data.
    private fun <T> mockOrFailClosed(component: String, mock: () -> T): T =
        if (BuildConfig.DEBUG) {
            mock()
        } else {
            error(
                "$component unavailable: Supabase is not configured in a release build. " +
                    "Refusing to fall back to mock data (fail-closed)."
            )
        }

    val callRepository: CallRepository by lazy {
        supabaseClient?.let { SupabaseCallRepository(it) }
            ?: mockOrFailClosed("CallRepository") { MockCallRepositoryImpl() }
    }

    val campaignRepository: CampaignRepository by lazy {
        supabaseClient?.let { SupabaseCampaignRepository(it) }
            ?: mockOrFailClosed("CampaignRepository") { MockCampaignRepositoryImpl() }
    }

    val rsvpRepository: RsvpRepository by lazy {
        supabaseClient?.let { SupabaseRsvpRepository(it) }
            ?: mockOrFailClosed("RsvpRepository") { MockRsvpRepositoryImpl() }
    }

    private val mockCallEngine: MockCallEngineImpl by lazy {
        MockCallEngineImpl(callRepository, rsvpRepository)
    }

    private val supabaseCallEngine: SupabaseCallEngineImpl? by lazy {
        supabaseClient?.let { SupabaseCallEngineImpl(it, callRepository, rsvpRepository) }
    }

    val callEngine: CallEngine get() =
        supabaseCallEngine ?: mockOrFailClosed("CallEngine") { mockCallEngine }
    val agentPresence: AgentPresence get() =
        (supabaseCallEngine ?: mockOrFailClosed("AgentPresence") { mockCallEngine }) as AgentPresence

    val liveTranscriptManager: LiveTranscriptManager? by lazy {
        supabaseClient?.let { LiveTranscriptManager(it) }
    }

    // Voximplant v3 human-agent SDK client (login/connect for monitor/takeover legs).
    // Created lazily but DELIBERATELY NOT logged in here — a login costs Voximplant
    // MAU quota, so ensureLoggedIn(me.voxUsername) is called only when a real leg must
    // be handled (that flow ships with monitor/takeover, gated on the backend
    // Conference). null when Supabase isn't configured.
    val voxClientManager: VoxClientManager? by lazy {
        val client = supabaseClient ?: return@lazy null
        val http = HttpClient(OkHttp)
        val authClient = VoxSdkAuthClient(http, getJwt = { client.auth.currentAccessTokenOrNull() })
        VoxClientManager(authClient)
    }
}
