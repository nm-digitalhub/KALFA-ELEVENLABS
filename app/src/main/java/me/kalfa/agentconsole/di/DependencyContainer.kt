package me.kalfa.agentconsole.di

import android.os.Build
import android.content.Context
import me.kalfa.agentconsole.BuildConfig
import me.kalfa.agentconsole.data.LiveTranscriptManager
import me.kalfa.agentconsole.data.*
import me.kalfa.agentconsole.data.mock.*
import me.kalfa.agentconsole.domain.repository.*
import me.kalfa.agentconsole.domain.telephony.*
import me.kalfa.agentconsole.telemetry.DeviceTelemetry
import me.kalfa.agentconsole.telemetry.Telemetry
import me.kalfa.agentconsole.telemetry.TelemetryEvents
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

    // `via` is additive with a default, so no existing call site breaks: it names
    // which entry point created this process, and "fcm" with no matching
    // "activity" is the signature of a headless push wake — the case the whole
    // telemetry channel exists to observe.
    fun attach(context: Context, via: String = "other") {
        // `synchronized`, NOT a bare check-then-act on the @Volatile field, and the
        // difference only became consequential when `via` arrived.
        //
        // The old body — `if (applicationContext == null) applicationContext = …` —
        // raced harmlessly: two threads could both observe null and both assign,
        // but they assigned the SAME application context, so nothing downstream
        // could tell. Hanging a side effect off that read changes the stakes.
        // `attach` is called from three threads with no happens-before between
        // them (MainActivity.onCreate on main, VoxFirebaseMessagingService.onCreate
        // on the FCM delivery thread, PresenceActionReceiver.onReceive on a
        // binder thread), so two of them could each take the `first` branch and
        // each emit app.attach with a DIFFERENT `via`.
        //
        // That is the one field this whole channel most depends on: "fcm with no
        // matching activity" is the signature of a headless push wake, and a race
        // there can produce both, or file the process under the wrong entry point.
        // A diagnostic whose headline field can be wrong is worse than no
        // diagnostic. Same lock and same reasoning as the four properties below,
        // whose kdoc already documents this hazard in as many words. (Found by
        // `analyst` in review; the old body was correct until this change.)
        val first = synchronized(this) {
            if (applicationContext == null) {
                applicationContext = context.applicationContext
                true
            } else {
                false
            }
        }
        if (!first) return
        // Reading the property is what CREATES and installs telemetry, so the
        // writer thread is up before any other call-path code runs. Deliberately
        // here rather than lazily at the first emit: on a push cold start the
        // first emit IS fcm.service_created, and creating the recorder inside the
        // call that wants to record would lose it.
        deviceTelemetry
        installCrashRecorder()
        Telemetry.emit(TelemetryEvents.APP_ATTACH, "via" to via)
        emitDeviceProfile()
    }

    /**
     * One line saying what is running and on what — see [TelemetryEvents.APP_DEVICE] for
     * why each field is here and why none of them identifies a person.
     *
     * Emitted right after `app.attach` so it is at the TOP of every process's trace: a
     * reader who tails this file mid-incident should not have to scroll to learn which
     * build they are looking at, and a crash can truncate everything after it.
     *
     * Wrapped, like the crash recorder above, because none of this is worth a failure in
     * process startup. `Build` fields can be null on modified ROMs and `packageManager`
     * can throw during early attach; a missing profile line costs a lookup, an exception
     * here would cost the app.
     */
    private fun emitDeviceProfile() {
        try {
            val ctx = applicationContext ?: return
            @Suppress("DEPRECATION")
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            // `v` prefix and the parenthesised code are deliberate: a bare "5.5.1" is
            // digits-and-dots only, which scrubTelemetryValue reads as a phone shape.
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toString()
            }
            Telemetry.emit(
                TelemetryEvents.APP_DEVICE,
                "app" to "v${pkg.versionName ?: "?"}($versionCode)",
                "os" to "Android${Build.VERSION.RELEASE ?: "?"}/api${Build.VERSION.SDK_INT}",
                "dev" to "${Build.MANUFACTURER ?: "?"}/${Build.MODEL ?: "?"}",
                "abi" to (Build.SUPPORTED_ABIS?.firstOrNull() ?: "?"),
            )
        } catch (_: Throwable) {
            // See the kdoc: a diagnostic must never be the reason startup fails.
        }
    }

    /**
     * Make the process record WHY it died, in the one channel that can be read remotely.
     *
     * The gap this closes, measured 2026-08-17: the telemetry traced an incoming call
     * end to end — `tm.session_open reason=sdk_incoming`, `vox.incoming_call`,
     * `fcm.message_received`, `wake.login_ok`, `vox.push_register_start` — and then
     * simply stopped, with a fresh `app.attach` 5.8 seconds later. The log recorded the
     * BOUNDARY of a crash and nothing about its cause, so the one question that mattered
     * ("what threw?") was answerable only from logcat, on a phone with no ADB, no USB and
     * no Wi-Fi. That is exactly the situation this whole channel exists to avoid, and it
     * had a hole in it precisely where it hurt most.
     *
     * Installed here rather than in an Application subclass because there isn't one, and
     * [attach] already runs exactly once per process from all three entry points —
     * including `VoxFirebaseMessagingService.onCreate`, which is the headless push wake
     * where a crash is least observable and most likely.
     *
     * THREE THINGS THIS DELIBERATELY DOES NOT DO:
     *
     * It does not swallow the crash. The previous handler is always called, so Android
     * still terminates the process, still shows whatever dialog it would have shown, and
     * any future crash reporter still sees the throwable. A handler that "helpfully"
     * recovers turns a loud fatal into a silent corrupt state.
     *
     * It does not upload. `flushLocalBlocking` writes to the device's own file and stops
     * there — an HTTP round trip inside a dying process is a good way to hang the death
     * itself, and the uploader will ship the line on the next launch anyway. The 400ms
     * ceiling is a bound on how long a dead process is allowed to keep the user waiting.
     *
     * It does not let its own failure become the crash. Everything here is wrapped: if
     * emitting or flushing throws, the original throwable must still reach the original
     * handler, or a diagnostic would have replaced the fault it was written to explain.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Same cause-chain shape as VoxClientManager's sdk_init tag — the top
                // frame of a crash is routinely a wrapper (ExceptionInInitializerError,
                // InvocationTargetException, RuntimeException from a coroutine) whose
                // own message says nothing, and the link below it is the answer.
                val chain = generateSequence(throwable) { it.cause }
                    .take(4)
                    .joinToString(" <- ") { t ->
                        val name = t::class.simpleName ?: "Throwable"
                        val msg = t.message?.takeIf { m -> m.isNotBlank() }?.take(140)
                        if (msg != null) "$name: $msg" else name
                    }
                // `at` is the first application frame, not the first frame overall: the
                // top of the stack is usually framework or library code that is the same
                // for every crash, while "which of OUR lines was running" is the field
                // that turns a report into a location.
                val appFrame = throwable.stackTrace
                    .firstOrNull { it.className.startsWith("me.kalfa.agentconsole") }
                    ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                Telemetry.emit(
                    TelemetryEvents.APP_CRASH,
                    "err" to chain,
                    "at" to (appFrame ?: "none"),
                    "thread" to thread.name,
                )
                Telemetry.flushLocalBlocking(CRASH_FLUSH_TIMEOUT_MS)
            } catch (_: Throwable) {
                // Intentionally empty: see the kdoc. The line below is what must happen.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private const val CRASH_FLUSH_TIMEOUT_MS = 400L

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
                // ctx is bound and null-checked HERE rather than read inside
                // VoxClientManager, so a missing Context cannot make VICore.initialize
                // silently do nothing — which would be the same bug it repairs, wearing
                // a different face. In practice it is never null on this path (store is
                // built from the same applicationContext), but "in practice" is what the
                // whole night has been about.
                val ctx = applicationContext
                if (client != null && store != null && ctx != null) {
                    val http = HttpClient(OkHttp)
                    val authClient = VoxSdkAuthClient(http, getJwt = { client.auth.currentAccessTokenOrNull() })
                    // ctx is the same applicationContext that built `store` above, so
                    // it is non-null on every path that reaches here. VoxClientManager
                    // needs it to call VICore.initialize before touching the SDK.
                    val manager = VoxClientManager(authClient, store, ctx)
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

    // Records the steps of the call path so they can be read from a phone nobody
    // can attach a debugger to — see DeviceTelemetry's kdoc. Off by default at
    // both ends; creating it costs a daemon thread and nothing else.
    //
    // Its own HttpClient rather than the one built for VoxSdkAuthClient below,
    // and that isolation is the point: a diagnostic must not be able to affect
    // the login path's connection pool, timeouts or failure behaviour. The cost
    // is a second OkHttp dispatcher, which is cheap; the alternative risks the
    // observer changing what it observes.
    //
    // `getJwt` resolves supabaseClient at CALL time, not construction time, so
    // building this on a push cold start does not drag the whole Supabase client
    // into existence before the wake path needs it.
    @Volatile private var _deviceTelemetry: DeviceTelemetry? = null
    val deviceTelemetry: DeviceTelemetry?
        get() = _deviceTelemetry ?: synchronized(this) {
            _deviceTelemetry ?: applicationContext?.let { ctx ->
                DeviceTelemetry.create(
                    context = ctx,
                    httpClient = HttpClient(OkHttp),
                    getJwt = { supabaseClient?.auth?.currentAccessTokenOrNull() },
                ).also {
                    _deviceTelemetry = it
                    Telemetry.install(it)
                }
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
