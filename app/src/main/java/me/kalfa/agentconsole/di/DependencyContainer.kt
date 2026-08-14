package me.kalfa.agentconsole.di

import android.content.Context
import me.kalfa.agentconsole.BuildConfig
import me.kalfa.agentconsole.data.LiveTranscriptManager
import me.kalfa.agentconsole.data.*
import me.kalfa.agentconsole.data.mock.*
import me.kalfa.agentconsole.domain.repository.*
import me.kalfa.agentconsole.domain.telephony.*
import me.kalfa.agentconsole.telephony.presence.PresenceStateStore
import me.kalfa.agentconsole.telephony.vox.VoxClientManager
import me.kalfa.agentconsole.telephony.vox.VoxIncomingCallCoordinator
import me.kalfa.agentconsole.telephony.vox.VoxSdkAuthClient
import me.kalfa.agentconsole.telephony.vox.VoxTokenStore
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

object DependencyContainer {
    // Needed only for VoxTokenStore's DataStore. Set from BOTH MainActivity.onCreate
    // (normal launch) and VoxFirebaseMessagingService.onCreate — a push can
    // cold-start the process straight into the FCM service, with MainActivity never
    // running (AGENTS.md "Push wake-up"). Idempotent: the second caller is a no-op.
    @Volatile private var applicationContext: Context? = null

    fun attach(context: Context) {
        if (applicationContext == null) applicationContext = context.applicationContext
    }

    // Read-only escape hatch for the rare caller that genuinely needs a raw Context
    // and has no other way to get one — e.g. PresenceActions checking RingCapability
    // (telephony/vox/RingCapability.kt) from a suspend function ConsoleViewModel AND
    // a BroadcastReceiver both call, neither of which has a Context of its own.
    // Prefer a constructed, Context-holding class (VoxTokenStore, PresenceStateStore)
    // over reading this directly when one already exists for the job.
    val appContext: Context? get() = applicationContext

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

    // Persisted Voximplant AuthParams (access/refresh token pair), so a push-woken
    // app can log in silently with no human present. null until attach() has run.
    //
    // DELIBERATELY NOT `by lazy`: Kotlin's `lazy` caches the FIRST evaluation
    // permanently, including a null one. Any code path that reads this property
    // before attach() has run (a real risk: attach() is called first thing in both
    // MainActivity.onCreate and VoxFirebaseMessagingService.onCreate today, but
    // nothing enforces that ordering for a future call site) would pin this to null
    // for the rest of the process, and since a null store also nulls out
    // voxClientManager below, that failure is exactly "push wake-up silently stops
    // working" — the one failure mode this class exists to prevent. A plain nullable
    // backing field that is only WRITTEN when non-null lets a later attach() still
    // be picked up by the next read.
    private var _voxTokenStore: VoxTokenStore? = null
    val voxTokenStore: VoxTokenStore?
        get() {
            if (_voxTokenStore == null) {
                _voxTokenStore = applicationContext?.let { VoxTokenStore(it) }
            }
            return _voxTokenStore
        }

    // Voximplant v3 human-agent SDK client (login/connect for monitor/takeover legs,
    // and now push wake-up). Created lazily but a LOGIN is never triggered here — a
    // login costs Voximplant MAU quota, so ensureLoggedIn(me.voxUsername) is called
    // only when a real leg must be handled: interactively when the agent declares
    // "Ready" (ConsoleViewModel.setAgentStatus), or from a push
    // (VoxFirebaseMessagingService). null when Supabase isn't configured or attach()
    // hasn't run yet — same non-sticky-null reasoning as voxTokenStore above.
    private var _voxClientManager: VoxClientManager? = null
    val voxClientManager: VoxClientManager?
        get() {
            if (_voxClientManager == null) {
                val client = supabaseClient
                val store = voxTokenStore
                if (client != null && store != null) {
                    val http = HttpClient(OkHttp)
                    val authClient = VoxSdkAuthClient(http, getJwt = { client.auth.currentAccessTokenOrNull() })
                    val manager = VoxClientManager(authClient, store)
                    // THE missing link (AGENTS.md "Push wake-up" / "Known state" #1):
                    // wire onIncomingCall the moment a real VoxClientManager exists, so
                    // a delivered call always has somewhere to go — see
                    // docs/android-presence-and-call-ux.md §3. incomingCallCoordinator
                    // is safe to read here: it only needs a Context (already attached
                    // by this point in every real call path) and callEngine, not this
                    // property itself.
                    incomingCallCoordinator?.let { coordinator ->
                        manager.onIncomingCall = coordinator::handleIncomingCall
                    }
                    _voxClientManager = manager
                }
            }
            return _voxClientManager
        }

    // Durable "was this agent on shift" + "did push registration last succeed"
    // record — see PresenceStateStore's kdoc. Shared instance (not one per
    // constructor call) so PresenceForegroundService and PresenceActions read/write
    // the exact same underlying DataStore file through one canonical handle, same
    // non-sticky-null reasoning as voxTokenStore above.
    private var _presenceStateStore: PresenceStateStore? = null
    val presenceStateStore: PresenceStateStore?
        get() {
            if (_presenceStateStore == null) {
                _presenceStateStore = applicationContext?.let { PresenceStateStore(it) }
            }
            return _presenceStateStore
        }

    // Coordinates a delivered incoming SDK call end to end (notification, FSI,
    // answer/decline) — see docs/android-presence-and-call-ux.md §3. Needs a Context
    // (for notifications/CallForegroundService) and CallEngine (to publish an
    // answered leg via attachIncomingSession) — null until attach() has run.
    private var _incomingCallCoordinator: VoxIncomingCallCoordinator? = null
    val incomingCallCoordinator: VoxIncomingCallCoordinator?
        get() {
            if (_incomingCallCoordinator == null) {
                applicationContext?.let { ctx ->
                    _incomingCallCoordinator = VoxIncomingCallCoordinator(ctx, callEngine)
                }
            }
            return _incomingCallCoordinator
        }
}
