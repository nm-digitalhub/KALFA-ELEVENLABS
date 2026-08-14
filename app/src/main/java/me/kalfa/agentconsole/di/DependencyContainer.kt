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
                    install(Auth) {
                        // supabase-kt's Android build registers a ProcessLifecycleOwner
                        // observer whose onStop calls resetLoadingState(), which sets
                        // sessionStatus back to SessionStatus.Initializing — and leaves it
                        // there for as long as the app is backgrounded. Read from the
                        // library's own androidMain source (auth-kt-android 3.1.4,
                        // io/github/jan/supabase/auth/setupPlatform.kt) and its
                        // AuthConfig defaults (enableLifecycleCallbacks = true,
                        // alwaysAutoRefresh = true), neither of which this app overrode.
                        //
                        // ProcessLifecycleOwner tracks ACTIVITY lifecycle only. A
                        // foreground service does not hold it started. So this fires on
                        // exactly the app this console is — one whose whole job runs while
                        // no Activity is up — and the consequences are both remaining
                        // symptoms of 2026-08-14:
                        //
                        //  - PresenceForegroundService's 30s heartbeat calls
                        //    awaitAuthToken(), whose awaitInitialization() cannot complete
                        //    while the status is Initializing. It times out at 3s, returns
                        //    Unsettled -> AppFailure.Unknown, and no write leaves the
                        //    device. agent_status.updated_at therefore freezes while the
                        //    phone is in a pocket, the server's 90s freshness gate ages the
                        //    agent out, and the notification reads
                        //    "הסטטוס לא התעדכן בשרת. ייתכן ששיחות לא יגיעו." — measured
                        //    frozen across four samples on the owner's account.
                        //  - VoxSdkAuthClient's getJwt is a bare currentAccessTokenOrNull(),
                        //    null in Initializing, so a backgrounded ensureLoggedIn throws
                        //    NoSession, Voximplant login fails, registerForPushNotifications
                        //    is never reached, and the platform holds no push token —
                        //    measured directly as push_results: [] on 76 ring attempts.
                        //
                        // VERIFIED that turning this off does not disable or delay the
                        // initial session load, which is the obvious way this could have
                        // backfired. AuthImpl.init() calls setupPlatform() and then, as a
                        // SEPARATE statement, runs the `if (config.autoLoadFromStorage)`
                        // branch; autoLoadFromStorage defaults true and is not overridden.
                        // Auto-refresh is started by importSession ("Starting auto refresh…",
                        // sessionJob = authScope.launch), reached via loadFromStorage —
                        // never by the observer. With the flag off, setupPlatform() is a
                        // no-op and everything else is untouched.
                        //
                        // The observer's two halves are a matched pair: onStop stops
                        // auto-refresh, onStart restarts it. Its own kdoc says that is all
                        // it does ("stop auto-refresh on focus loss, and resume it on focus
                        // again"). Nothing in this app calls stop/startAutoRefresh, so with
                        // the pair removed auto-refresh simply keeps running.
                        //
                        // That is the intended trade, not a side effect: token refresh now
                        // continues while backgrounded, which is precisely what an app with
                        // a 24/7 foreground service needs and precisely what the library
                        // default is not written for. The cost is honest and worth naming —
                        // refreshes now happen when they fall due rather than when the agent
                        // next opens the app, so more of them land on whatever network the
                        // phone has at the time. A failed one becomes RefreshFailure, and
                        // AuthGate currently renders that as a LOGIN FORM for an agent who
                        // is signed in. That gap is real, is not created by this change, and
                        // should be fixed next.
                        enableLifecycleCallbacks = false
                    }
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
    //
    // ---- Applies to this property and the three below it ----
    //
    // @Volatile + synchronized, NOT a bare check-then-assign. All four are read from at
    // least three threads with no happens-before between them:
    // the main thread (MainActivity's composition), the FCM service's delivery thread
    // (VoxFirebaseMessagingService — which can be the FIRST reader, on a push
    // cold-start), and PresenceForegroundService's Dispatchers.IO scope. Without
    // synchronization two threads can each observe null and each construct their own
    // instance, and the loser's writes are not even guaranteed to become visible.
    //
    // That is not a benign extra allocation here. Two VoxIncomingCallCoordinators mean
    // MainActivity collects instance A's pendingOffer while VoxClientManager.onIncomingCall
    // is bound to instance B's handleIncomingCall — the ring screen simply never
    // appears, with nothing logged. Two VoxClientManagers mean two independent
    // loginMutexes and two `initialized` flags, so VICalls.initialize() runs twice and
    // registers a second signaling message listener.
    //
    // The lock nests over the `by lazy` engine fields (callEngine, read while building
    // incomingCallCoordinator). Verified deadlock-free: neither SupabaseCallEngineImpl
    // nor MockCallEngineImpl nor VoxSdkAuthClient references DependencyContainer, so no
    // thread can hold a lazy initializer's lock while waiting on this monitor.
    @Volatile private var _voxTokenStore: VoxTokenStore? = null
    val voxTokenStore: VoxTokenStore?
        get() = _voxTokenStore ?: synchronized(this) {
            _voxTokenStore ?: applicationContext?.let { ctx ->
                VoxTokenStore(ctx).also { _voxTokenStore = it }
            }
        }

    // Voximplant v3 human-agent SDK client (login/connect for monitor/takeover legs,
    // and now push wake-up). Created lazily but a LOGIN is never triggered here — a
    // login costs Voximplant MAU quota, so ensureLoggedIn(me.voxUsername) is called
    // only when a real leg must be handled: interactively when the agent declares
    // "Ready" (ConsoleViewModel.setAgentStatus), or from a push
    // (VoxFirebaseMessagingService). null when Supabase isn't configured or attach()
    // hasn't run yet — same non-sticky-null reasoning as voxTokenStore above.
    @Volatile private var _voxClientManager: VoxClientManager? = null
    val voxClientManager: VoxClientManager?
        get() = _voxClientManager ?: synchronized(this) {
            _voxClientManager ?: run {
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
                    manager.also { _voxClientManager = it }
                } else {
                    null
                }
            }
        }

    // Durable "was this agent on shift" + "did push registration last succeed"
    // record — see PresenceStateStore's kdoc. Shared instance (not one per
    // constructor call) so PresenceForegroundService and PresenceActions read/write
    // the exact same underlying DataStore file through one canonical handle, same
    // non-sticky-null reasoning as voxTokenStore above.
    @Volatile private var _presenceStateStore: PresenceStateStore? = null
    val presenceStateStore: PresenceStateStore?
        get() = _presenceStateStore ?: synchronized(this) {
            _presenceStateStore ?: applicationContext?.let { ctx ->
                PresenceStateStore(ctx).also { _presenceStateStore = it }
            }
        }

    // Coordinates a delivered incoming SDK call end to end (notification, FSI,
    // answer/decline) — see docs/android-presence-and-call-ux.md §3. Needs a Context
    // (for notifications/CallForegroundService) and CallEngine (to publish an
    // answered leg via attachIncomingSession) — null until attach() has run.
    @Volatile private var _incomingCallCoordinator: VoxIncomingCallCoordinator? = null
    val incomingCallCoordinator: VoxIncomingCallCoordinator?
        get() = _incomingCallCoordinator ?: synchronized(this) {
            _incomingCallCoordinator ?: applicationContext?.let { ctx ->
                VoxIncomingCallCoordinator(ctx, callEngine).also { _incomingCallCoordinator = it }
            }
        }
}
