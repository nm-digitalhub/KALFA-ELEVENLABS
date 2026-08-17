package me.kalfa.agentconsole.telephony.vox

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.voximplant.android.sdk.calls.Call
import com.voximplant.android.sdk.calls.CallSettings
import com.voximplant.android.sdk.calls.IncomingCallListener
import com.voximplant.android.sdk.calls.VICalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.voximplant.android.sdk.core.AuthParams
import com.voximplant.android.sdk.core.Client
import com.voximplant.android.sdk.core.ClientSessionListener
import com.voximplant.android.sdk.core.ClientState
import com.voximplant.android.sdk.core.ConnectOptions
import com.voximplant.android.sdk.core.ConnectionCallback
import com.voximplant.android.sdk.core.ConnectionError
import com.voximplant.android.sdk.core.DisconnectReason
import com.voximplant.android.sdk.core.GenerateOneTimeKeyCallback
import com.voximplant.android.sdk.core.LoginCallback
import com.voximplant.android.sdk.core.LoginError
import com.voximplant.android.sdk.core.PushConfig
import com.voximplant.android.sdk.core.PushTokenError
import com.voximplant.android.sdk.core.RefreshTokenCallback
import com.voximplant.android.sdk.core.RegisterPushTokenCallback
import com.voximplant.android.sdk.core.VICore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.kalfa.agentconsole.telemetry.Telemetry
import me.kalfa.agentconsole.telemetry.TelemetryEvents
import me.kalfa.agentconsole.telemetry.telemetryFingerprint
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Single owner of the Voximplant v3 `Client` (a process-wide object) for the
// human-agent leg. Wraps the callback-based SDK auth in suspend functions, exposes
// login state as a StateFlow, and holds the incoming-call listener — the monitor/
// takeover leg arrives as an incoming SDK call once the backend calls the agent.
//
// `ensureLoggedIn` tries THREE paths in order and persists whatever AuthParams the
// SDK hands back after each successful one (via `tokenStore`), so the NEXT call
// (interactive or push-triggered) can skip straight to the cheapest path:
//   1. loginWithAccessToken, using a persisted, still-valid access token.
//   2. refreshToken + a follow-up loginWithAccessToken, when only the access token
//      has expired.
//   3. The original interactive one-time-key flow (POST /api/agents/sdk-auth),
//      when neither token is usable — the only path that needs network to
//      beta.kalfa.me and, structurally, no human interaction either (see
//      VoxSdkAuthClient) — but it is the slowest, and the one MAU discipline is
//      about (see below).
//
// This is what makes a push-woken app (no human present to type anything) able to
// log in at all — AGENTS.md "Push wake-up", path 3 there ("Persisted-token silent
// login"). NOT wired to run automatically: `ensureLoggedIn` MUST be called only
// when a real call has to be handled (interactively: the agent declaring "Ready";
// from a push: VoxFirebaseMessagingService) — never on launch. Every FIRST login of
// a calendar month for a given Voximplant user counts against the account's MAU
// quota (1000/month); repeat logins by the SAME user within the month do not
// multiply it, so the push-wake path re-using this same entry point is free once an
// interactive login has happened at least once.
class VoxClientManager(
    private val authClient: VoxSdkAuthClient,
    private val tokenStore: VoxTokenStore,
    // Required, not nullable, and injected rather than read from DependencyContainer at
    // call time: VICore.initialize needs a Context, and a null one here would make the
    // fix below silently do nothing -- the exact shape of the bug it repairs. The only
    // construction site already proves non-null (voxTokenStore is itself built from the
    // same applicationContext and is null-checked there), so the impossible case is
    // impossible by construction rather than by a check that could be forgotten.
    private val appContext: Context,
) {

    private val _loginState = MutableStateFlow(VoxLoginState.LOGGED_OUT)
    val loginState: StateFlow<VoxLoginState> = _loginState.asStateFlow()

    // The human agent's incoming SDK leg. Kept here (rather than constructed with a
    // fixed listener) so it survives across logins and so DependencyContainer can
    // assign it once VoxIncomingCallCoordinator exists, without this class needing
    // to know about that class. Corrected 2026-08-17: this comment used to say null
    // "until that feature is wired" and described a push-delivered call as dropped —
    // stale since the 2026-08-14 wiring. DependencyContainer's voxClientManager
    // getter assigns this to VoxIncomingCallCoordinator::handleIncomingCall the
    // moment both exist, which answers the inbound-to-human offer path end to end
    // (not device-verified — see docs/android-presence-and-call-ux.md §3). It is
    // still null only in the narrow window before that getter has run once.
    @Volatile var onIncomingCall: ((Call, Map<String, String>) -> Unit)? = null

    private val loginMutex = Mutex()
    @Volatile private var initialized = false

    val isLoggedIn: Boolean get() = Client.clientState == ClientState.LoggedIn

    // Idempotent one-time SDK setup. VICalls.initialize() prepares the calls
    // subsystem; the session listener keeps loginState honest across drops; the
    // incoming-call listener forwards to onIncomingCall (claimed only after the
    // backend has authorised the leg).
    private fun ensureInitialized() {
        if (initialized) return
        // THE ROOT CAUSE OF EVERY FAILED LOGIN ON THIS DEVICE, and it was one missing
        // line. The Voximplant SDK core has to be handed an application Context before
        // anything else in the SDK is touched; this app never did it, anywhere
        // (`grep -rn VICore app/src` returned nothing).
        //
        // Traced through the shipped AARs with javap rather than inferred:
        //   VICalls.initialize()            first real action is
        //   CallsShared.createPeerConnectionFactory()   which reads
        //   VICore.getApplicationContext()  whose bytecode is
        //       getstatic applicationContext ; ifnull -> throwUninitializedPropertyAccessException
        //
        // So `ensureLoggedIn`'s FIRST statement threw UninitializedPropertyAccessException
        // on every attempt, forever: VICalls.initialize sets its own `isInitialized` flag
        // only at the END of its body, and this class sets `initialized` only after all
        // three calls below, so nothing latched and every retry failed identically. The
        // exception carries an untagged message, which is why both surfaces rendered a
        // bare banner.
        //
        // It accounts for every measurement without remainder: no Android client in 2724
        // Voximplant sessions, push_results: [] on all 76 ring attempts, identical
        // behaviour foreground and background (which is what ruled out the session-race
        // theory), and a bundle-id fix that changed nothing because registration was
        // never reached.
        //
        // Neither AAR ships a <provider>, so there is no androidx.startup initializer to
        // do this for us -- unlike supabase-kt, which does. The app must make the call.
        //
        // Guarded by the SDK's own predicate rather than a local flag. Verified from
        // bytecode that this is belt-and-braces rather than required: initialize() is a
        // pure setter -- `applicationContext = context.getApplicationContext() ?: context`
        // and nothing else -- so calling it twice is harmless. isInitialized is literally
        // `applicationContext != null`, the same condition getApplicationContext throws
        // on, so it cannot disagree with reality. (It reads as a PROPERTY from Kotlin,
        // not a function: the AAR carries `isInitialized$annotations()`, so javap's
        // `boolean isInitialized()` is the JVM accessor behind a Kotlin val. The
        // compiler catches this; the bytecode signature alone would mislead.)
        if (!VICore.isInitialized) VICore.initialize(appContext)
        VICalls.initialize()
        VICalls.setIncomingCallListener(object : IncomingCallListener {
            override fun onIncomingCall(
                call: Call,
                hasIncomingVideo: Boolean,
                headers: Map<String, String>?,
            ) {
                // THE line the whole diagnostic is waiting for. Everything before
                // this is the app trying; this is the platform delivering.
                //
                // openCallSession is idempotent, so on the push path it joins the
                // trace the FCM service already opened rather than starting a
                // second one. It matters on the OTHER path too: an app already
                // foreground and logged in receives a call with no push at all,
                // and that attempt would otherwise have no `sid` of its own.
                //
                // `hdrs` is a COUNT. SIP headers routinely carry caller identity,
                // so neither keys nor values are ever recorded.
                Telemetry.openCallSession("sdk_incoming")
                Telemetry.noteIncomingCall()
                val listener = onIncomingCall
                Telemetry.emit(
                    if (listener != null) TelemetryEvents.VOX_INCOMING_CALL
                    else TelemetryEvents.VOX_INCOMING_NO_LISTENER,
                    "hdrs" to (headers?.size ?: 0).toString(),
                )
                listener?.invoke(call, headers ?: emptyMap())
            }
        })
        Client.setClientSessionListener(object : ClientSessionListener {
            override fun onConnectionClosed(reason: DisconnectReason) {
                // A connection closing between the push and the call is one of the
                // documented ways registerForPushNotifications silently registers
                // nothing (ConnectionClosed) — worth a line of its own.
                Telemetry.emit(TelemetryEvents.VOX_SESSION_STATE, "s" to "closed")
                _loginState.value = VoxLoginState.LOGGED_OUT
            }
            override fun onReconnecting() {
                Telemetry.emit(TelemetryEvents.VOX_SESSION_STATE, "s" to "reconnecting")
                _loginState.value = VoxLoginState.CONNECTING
            }
            override fun onReconnected() {
                Telemetry.emit(TelemetryEvents.VOX_SESSION_STATE, "s" to "reconnected")
                _loginState.value = VoxLoginState.LOGGED_IN
            }
        })
        initialized = true
    }

    // connect -> [silent: loginWithAccessToken | refreshToken+retry] -> otherwise
    // requestOneTimeKey -> POST /api/agents/sdk-auth -> loginWithOneTimeKey.
    // Serialized by a mutex so two concurrent callers run ONE login sequence. Safe
    // to call when already logged in (returns success without a second login).
    suspend fun ensureLoggedIn(voxUsername: String): Result<Unit> = loginMutex.withLock {
        runCatching {
            // Tagged, and caught as THROWABLE rather than Exception. That distinction is
            // the whole point: a failing static initialiser throws
            // ExceptionInInitializerError, and every access after it throws
            // NoClassDefFoundError -- both are Errors, not Exceptions, so
            // `catch (e: Exception)` would miss precisely the failure this tag exists to
            // name. (It would still reach runCatching, which catches Throwable, but
            // arrive untagged.)
            //
            // This is the step that was failing on the owner's device before
            // VICore.initialize was added below: ensureInitialized() is the first
            // statement here, and its throw is what produced a bare banner on every
            // attempt. If anything in SDK startup breaks again, it now says so by name
            // instead of costing another night.
            try {
                ensureInitialized()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Report the CAUSE CHAIN, not just this throwable's own message.
                //
                // Measured 2026-08-17 from live device telemetry, which reported
                // `err=com.voximplant.android.sdk.core.Client` on every attempt and
                // stopped exactly one level short of the answer. A bare fully-qualified
                // class name as a message is the signature of NoClassDefFoundError,
                // which is the SECOND symptom: the first access threw
                // ExceptionInInitializerError, whose own message is null and whose
                // `cause` is the only place the real failure (a missing transitive, an
                // incompatible binary API, an UnsatisfiedLinkError) is named. Every
                // access after that gets the bare NoClassDefFoundError forever.
                //
                // `e.message ?: e::class.simpleName` therefore produced a string that
                // names the victim and never the culprit. Walking `cause` closes that:
                // the chain is what turns "the SDK would not start" into "the SDK would
                // not start BECAUSE x".
                //
                // Capped at 4 links and truncated per link — this string reaches a
                // Slack-adjacent log and a device banner, neither of which is a stack
                // trace viewer, and an unbounded chain from a deeply nested loader
                // failure would drown both.
                val chain = generateSequence<Throwable>(e) { it.cause }
                    .take(4)
                    .joinToString(" <- ") { t ->
                        val name = t::class.simpleName ?: "Throwable"
                        val msg = t.message?.takeIf { m -> m.isNotBlank() }?.take(120)
                        if (msg != null) "$name: $msg" else name
                    }
                Telemetry.emit(TelemetryEvents.VOX_SDK_INIT_FAIL, "err" to chain)
                throw VoxAuthException.Sdk("sdk_init: $chain")
            }
            if (Client.clientState == ClientState.LoggedIn) {
                Telemetry.emit(TelemetryEvents.VOX_LOGIN_START, "plan" to "already")
                return@runCatching
            }
            val fullUsername = VoxConfig.fullUsername(voxUsername)

            if (Client.clientState != ClientState.Connected) {
                _loginState.value = VoxLoginState.CONNECTING
                Telemetry.emit(TelemetryEvents.VOX_CONNECT_START)
                try {
                    connectSuspend()
                } catch (e: Throwable) {
                    // A wrong VoxConfig.node fails HERE, not at login, and presents
                    // as a network problem — AGENTS.md flags it as the first thing
                    // to re-check. Naming the step removes the guess.
                    Telemetry.emit(
                        TelemetryEvents.VOX_CONNECT_FAIL,
                        "err" to (e.message ?: e::class.simpleName ?: "unknown"),
                    )
                    throw e
                }
                Telemetry.emit(TelemetryEvents.VOX_CONNECT_OK)
            }
            _loginState.value = VoxLoginState.LOGGING_IN

            val stored = tokenStore.load()
            val plan = planSilentLogin(
                nowMs = System.currentTimeMillis(),
                isLoggedIn = false, // already returned above if true
                stored = stored,
                voxUsername = voxUsername,
            )
            // WHICH of the three login paths was taken is a timing fact, not
            // trivia: AGENTS.md records that they have very different budgets
            // against the server's 15s retry window — the access-token path is one
            // round trip, the refresh path two, and the interactive fallback adds
            // a round trip to beta.kalfa.me and plausibly does NOT fit. A wake that
            // ran out of time is diagnosed differently depending on this line.
            Telemetry.emit(
                TelemetryEvents.VOX_LOGIN_START,
                "plan" to when (plan) {
                    is SilentLoginPlan.UseAccessToken -> "access"
                    is SilentLoginPlan.UseRefreshToken -> "refresh"
                    SilentLoginPlan.AlreadyLoggedIn -> "already"
                    SilentLoginPlan.FallBackToInteractive -> "interactive"
                },
            )
            val silentlyLoggedIn = when (plan) {
                is SilentLoginPlan.UseAccessToken ->
                    trySilentAccessToken(fullUsername, voxUsername, plan.tokens) ||
                        tryRefreshThenAccessToken(fullUsername, voxUsername, plan.tokens)
                is SilentLoginPlan.UseRefreshToken ->
                    tryRefreshThenAccessToken(fullUsername, voxUsername, plan.tokens)
                SilentLoginPlan.AlreadyLoggedIn, SilentLoginPlan.FallBackToInteractive -> false
            }

            if (!silentlyLoggedIn) {
                loginInteractively(fullUsername, voxUsername) // may throw -> real failure
            }
            _loginState.value = VoxLoginState.LOGGED_IN
            Telemetry.emit(TelemetryEvents.VOX_LOGIN_OK, "silent" to silentlyLoggedIn.toString())
        }.onFailure { e ->
            _loginState.value = VoxLoginState.FAILED
            Telemetry.emit(
                TelemetryEvents.VOX_LOGIN_FAIL,
                "err" to (e.message ?: e::class.simpleName ?: "unknown"),
            )
        }
    }

    /**
     * Persist AuthParams — and never let the persistence decide whether auth worked.
     *
     * The one place a token write happens, because the alternative was three places
     * and all three were wrong the same way. A bare `tokenStore.save(...)` inside a
     * `runCatching` whose failure means "the platform rejected us" conflates two
     * unrelated facts: what the SDK said, and whether a DataStore write landed. The
     * SDK is authoritative about the first and knows nothing about the second.
     *
     * It had already been fixed once, inline, in [loginInteractively] — a full disk
     * there made `ensureLoggedIn` report failure after `loginWithOneTimeKey` had
     * succeeded, and PresenceActions published "המכשיר לא נרשם לקבלת שיחות" on a
     * device that was genuinely logged in. Twice in one file means the SHAPE is the
     * defect, not the instance, so the guard now lives in one function that every
     * caller goes through instead of a pattern each caller has to remember.
     *
     * What is actually lost when a write fails is the NEXT login's silent path:
     * `planSilentLogin` sees no usable stored pair and returns `FallBackToInteractive`,
     * so the following login pays a one-time-key round trip. A degradation, reported
     * as one.
     *
     * CancellationException is rethrown rather than logged: it IS an Exception in
     * Kotlin, so a bare `catch (e: Exception)` would swallow the cancellation from a
     * caller's timeout and let a timed-out attempt look like a successful login with a
     * bad cache write.
     */
    private suspend fun cacheTokens(voxUsername: String, authParams: AuthParams) {
        try {
            tokenStore.save(voxUsername, authParams)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                TAG,
                "logged in, but caching the Voximplant tokens failed — the next login " +
                    "will use the interactive path: ${e.message ?: e::class.simpleName}",
            )
        }
    }

    private suspend fun trySilentAccessToken(
        fullUsername: String,
        voxUsername: String,
        tokens: StoredVoxTokens,
    ): Boolean = runCatching {
        val authParams = loginWithAccessTokenSuspend(fullUsername, tokens.accessToken)
        // cacheTokens, not tokenStore.save. A failed write here used to make this
        // function return FALSE on a device the platform had just accepted, which sent
        // ensureLoggedIn on to tryRefreshThenAccessToken — rotating a perfectly good
        // refresh token, and spending a second and third round trip, inside a 9-15s
        // wake budget, all because a preferences file could not be written.
        if (authParams != null) cacheTokens(voxUsername, authParams)
    }.isSuccess

    private suspend fun tryRefreshThenAccessToken(
        fullUsername: String,
        voxUsername: String,
        tokens: StoredVoxTokens,
    ): Boolean = runCatching {
        val refreshed = refreshTokenSuspend(fullUsername, tokens.refreshToken)
        // Cached immediately, because the refresh has ALREADY rotated the pair
        // server-side: the tokens this function was handed are dead from this line
        // onward whether or not the write lands. Through cacheTokens so that a write
        // failure cannot be mistaken for the platform refusing us — which is what
        // reached the handler below and discarded credentials the platform had just
        // issued.
        cacheTokens(voxUsername, refreshed)
        val authParams = loginWithAccessTokenSuspend(fullUsername, refreshed.accessToken)
        if (authParams != null) cacheTokens(voxUsername, authParams)
    }.onFailure {
        // Both tokens are apparently unusable (refresh rejected, or the freshly
        // refreshed access token was itself rejected) — discard rather than leave a
        // dead pair that would just be re-tried and re-fail on every future call.
        //
        // clearTokens(), NOT clear(). This used to call clear(), which wipes the whole
        // DataStore including the vox username — and that key has exactly one writer,
        // ConsoleViewModel, on a path that needs the app open. A refresh the platform
        // turned down therefore cost the device its IDENTITY, after which
        // PresenceActions.loginAndRegisterForPush gets null from loadUsername() and
        // reports `no_device_identity` forever instead of logging in. A dead session is
        // a reason to log in again; it is not a reason to forget who you are.
        //
        // Guarded on top of that, because `onFailure` fires for a cancellation too and
        // a bounded caller that ran out of time has learned nothing about the tokens.
        // See refreshFailureProvesTokensDead — including the measurement showing that
        // this guard is belt-and-braces TODAY and why it is still worth writing.
        //
        // Now that both writes go through cacheTokens, the ONLY things that can reach
        // this handler are the two SDK calls and a cancellation — which is what makes
        // "the platform rejected us" a true reading of it rather than an assumed one.
        // It was not true before: a DataStore write failure after a SUCCESSFUL refresh
        // landed here too, and threw away tokens the platform had just issued.
        if (refreshFailureProvesTokensDead(it)) tokenStore.clearTokens()
    }.isSuccess

    private suspend fun loginInteractively(fullUsername: String, voxUsername: String) {
        val oneTimeKey = requestOneTimeKeySuspend(fullUsername)
        val hash = authClient.fetchHash(oneTimeKey) // server-computed; may throw VoxAuthException
        val authParams = loginWithOneTimeKeySuspend(fullUsername, hash)
        // THE SDK IS LOGGED IN FROM HERE. Everything below is caching, and a caching
        // failure must not be reported as a failed login — see cacheTokens, which is
        // where the try/catch that used to be written out here now lives, so that the
        // other two token writes in this file get the same protection instead of each
        // caller having to remember it.
        if (authParams != null) cacheTokens(voxUsername, authParams)
    }

    fun logout() {
        runCatching { Client.disconnect() }
        _loginState.value = VoxLoginState.LOGGED_OUT
    }

    // Explicit sign-out only (ConsoleViewModel.logout) — NOT called on every
    // disconnect. Drops the persisted Voximplant session so a signed-out device
    // cannot silently log back in and cannot remain in the push-wake audience for
    // an agent who is no longer using it.
    suspend fun forgetPersistedSession() {
        tokenStore.clear()
    }

    // Fetches the CURRENT FCM token fresh from Firebase rather than trusting a
    // cached value from a past onNewToken (a device that already had a stable
    // token before this feature shipped never fires onNewToken again), then
    // registers it. Safe to call in any Client state per the SDK docs; the token is
    // only actually bound to an identity once logged in — call after ensureLoggedIn
    // succeeds.
    //
    // `.token` is @Deprecated in firebase-messaging 25.1.1 (confirmed via javap:
    // `Deprecated: true` on FirebaseMessaging.getToken(), in favour of
    // FirebaseMessagingService.onRegistered(installationId) / Firebase Installation
    // ID-based registration). DELIBERATELY kept anyway: Voximplant's PushConfig
    // (and, upstream of it, Voximplant's own server sending via the Firebase Admin
    // SDK against the service-account credential already uploaded to their
    // control panel) is built around a genuine FCM registration TOKEN, not a FID —
    // and as of this change, Firebase's own "send to specific devices" guide and
    // Admin SDK are still token-only; whether a FID is even accepted there is an
    // open gap Google has not documented (firebase-android-sdk#8316). Google
    // states the token "keeps working" with no removal date set. Switching to FID
    // now would risk silently breaking delivery to a third party (Voximplant) with
    // no way to verify from this repo. Re-evaluate if Voximplant ever documents FID
    // support, or if Google sets a removal date for the token.
    // Two SEPARATE try/catch blocks, not one blanket runCatching — a live incident
    // (docs/android-presence-and-call-ux.md's "Update 2026-08-14 (later)": Voximplant
    // itself reported `push_results: []`/"No push notifications has been sent" for a
    // device this app never got a token registered for) needed to know WHICH of two
    // very different failure domains it was — a local Google Play Services/FCM
    // problem fetching the token, or Voximplant's own registerForPushNotifications
    // call failing — and a single try/catch around both steps cannot distinguish
    // them. Both branches throw the SAME VoxAuthException.Sdk type (already used for
    // every other SDK-boundary failure in this class) so callers don't need a new
    // exception type, but the message tags which step it was.
    suspend fun registerCurrentPushToken(): Result<Unit> = runCatching {
        Telemetry.emit(TelemetryEvents.VOX_PUSH_REGISTER_START)
        val token = try {
            FirebaseMessaging.getInstance().token.awaitTask()
        } catch (e: CancellationException) {
            // MUST precede catch (e: Exception): CancellationException IS an Exception in
            // Kotlin, so without this a cancellation -- in practice the timeout
            // PresenceActions.ensurePushRegistration wraps this call in -- would be
            // re-tagged "fcm_token:" and reported as a Google Play services problem. A
            // timed-out attempt is not an FCM failure, and mislabelling it would send
            // whoever reads the banner to the wrong device subsystem entirely.
            throw e
        } catch (e: Exception) {
            throw VoxAuthException.Sdk("fcm_token: ${e.message ?: e::class.simpleName}")
        }
        try {
            registerPushTokenSuspend(token)
        } catch (e: CancellationException) {
            throw e // same reason as above -- must not become "vox_register:"
        } catch (e: VoxAuthException) {
            throw e // already tagged "registerForPushNotifications: ..." at the source
        } catch (e: Exception) {
            throw VoxAuthException.Sdk("vox_register: ${e.message ?: e::class.simpleName}")
        }
        // Emitted HERE, inside the block, because `token` is in scope here and the
        // two fields below are the entire point of this event.
        //
        // A success on this line is NOT proof the device is reachable, and that is
        // the trap this records against. The platform can accept a registration and
        // still hold nothing it can send to — which is exactly the bundle-id story
        // AGENTS.md recorded, where registration succeeded, the token was filed
        // under a bundle no certificate matched, and all 76 ring attempts reported
        // `push_results: []` while looking like a registered device. From inside
        // this process that state is UNOBSERVABLE: platform-side, `CallUser` gives
        // up 287ms later having never contacted the device at all (measured by
        // `analyst` on session 7666179052), so there is no callback, no throw and
        // no FCM message to notice the absence of.
        //
        // What these two fields buy is not detection but a JOIN. When a
        // `Call.PushSent` shows `push_results: []` at time T, the device log can
        // now say "I registered token a1b2c3d4 under bundle null at T−n" — a fact
        // neither system can state alone. `tok` is a truncated SHA-256, never a
        // prefix: a prefix of a credential is a piece of the credential, in a file
        // whose premise is that someone who should not see secrets will read it.
        Telemetry.emit(
            TelemetryEvents.VOX_PUSH_REGISTER_OK,
            "tok" to telemetryFingerprint(token),
            "bundle" to (PUSH_BUNDLE_ID ?: "null"),
        )
    }.onFailure { e ->
        // `stage` splits the two failure domains this method already separates in
        // its message — a local Google Play services / FCM problem fetching the
        // token, versus Voximplant rejecting the token we did get.
        //
        // AGENTS.md says reading this ONE string decides which branch of the push
        // investigation is live and that no more code should be written before it
        // is read. **That guidance is stale and should not be acted on**: the
        // branch was settled from bytecode, not from a device. The app never
        // called `VICore.initialize(Context)`, so `VICalls.initialize()` threw on
        // the first statement of every login attempt, forever — which is why all
        // 76 ring attempts showed `push_results: []` and no Android client
        // appeared in 2724 Voximplant sessions. `81788b3` fixed it.
        //
        // The string matters MORE now, for the opposite reason. Until that fix,
        // this method was UNREACHABLE — login never succeeded, so neither
        // `fcm_token` nor `vox_register` could ever have been the answer.
        // Registration is about to execute for the first time in this app's
        // existence, and the two branches have genuinely different owners: a
        // device-local Play Services problem, or Voximplant rejecting a token we
        // did obtain. Keep the event; do not hold work waiting on it.
        // (Correction owed to `analyst`, who caught me repeating the stale
        // premise from AGENTS.md.)
        val message = e.message.orEmpty()
        val stage = when {
            // MUST come first, for the same reason the two catch blocks above
            // check CancellationException before Exception: runCatching wraps a
            // cancellation as a failure like any other, and the cancellation here
            // is in practice ensurePushRegistration's own timeout. Reporting a
            // timed-out attempt as an FCM or a Voximplant fault would send whoever
            // reads this line to the wrong subsystem entirely — which is exactly
            // the mislabelling this method's two-branch design exists to prevent.
            e is CancellationException -> "cancelled"
            message.startsWith("fcm_token:") -> "fcm_token"
            message.startsWith("vox_register:") -> "vox_register"
            message.startsWith("registerForPushNotifications:") -> "vox_register"
            else -> "unknown"
        }
        Telemetry.emit(
            TelemetryEvents.VOX_PUSH_REGISTER_FAIL,
            "stage" to stage,
            "err" to (message.ifEmpty { e::class.simpleName ?: "unknown" }),
        )
    }

    // Best-effort, called on explicit sign-out alongside forgetPersistedSession.
    suspend fun unregisterCurrentPushToken(): Result<Unit> = runCatching {
        val token = FirebaseMessaging.getInstance().token.awaitTask()
        unregisterPushTokenSuspend(token)
    }

    /**
     * Creates an outbound leg from a one-time dial token — THE ONLY WAY THIS APP MAY
     * TOUCH `VICalls.createCall`.
     *
     * It lives here, and not at the call site, because the call site got it wrong and
     * a comment had already warned about exactly how. Measured from device telemetry
     * 2026-08-17T21:21:41, after tapping "חזור" on a missed call:
     *
     *     app.crash err=ExceptionInInitializerError_<-_IllegalStateException:
     *     _Voximplant at=SupabaseCallEngineImpl$dialViaIntent$2 thread=main
     *
     * `VICalls` is an object: merely REFERENCING it runs a static initialiser that
     * throws when `VICore.initialize` has never been called. The data layer called it
     * directly, without the login gate, inside `catch (e: Exception)` — and
     * ExceptionInInitializerError is an Error, so nothing caught it and the process
     * died on the main thread.
     *
     * ensureLoggedIn's own kdoc, twenty lines up, had described that trap precisely.
     * Documenting a hazard does not remove it; the second caller reproduced it
     * anyway. So the hazard is removed instead: `VICalls` is now referenced in this
     * file alone, every path reaches it through ensureInitialized, and a future
     * caller cannot skip the gate because there is nothing outside to skip it with.
     *
     * The username is resolved HERE from the token store rather than asked of the
     * caller — a caller that has to look it up first is a caller that can forget to.
     */
    suspend fun placeCall(dialToken: String): Result<Call> {
        val voxUsername = tokenStore.loadUsername()
            ?: return Result.failure(IllegalStateException("no_device_identity"))
        ensureLoggedIn(voxUsername).getOrElse { return Result.failure(it) }

        // Main thread: the SDK requires it for call creation, and the previous call
        // site already knew that. Errors as well as exceptions are caught, for the
        // reason spelled out above.
        return withContext(Dispatchers.Main) {
            runCatching {
                // NULLABLE, and a real outcome rather than something to assert away:
                // the SDK returns null when it cannot create a call at all. A `!!`
                // would turn "not connected" into a second crash.
                VICalls.createCall(dialToken, CallSettings())
                    ?: throw IllegalStateException("telephony_unavailable")
            }
        }
    }

    // The one non-suspend SDK call in the wake path (fire-and-forget void method,
    // no callback in the SDK signature). Safe to call in any Client state per the
    // guide ("may be called in any state, however you only receive the incoming
    // call if you are connected AND logged in") — ensureInitialized() is re-run
    // defensively in case ensureLoggedIn failed before reaching it.
    fun handleRawPushNotification(data: Map<String, String>) {
        ensureInitialized()
        Client.handlePushNotification(data)
    }

    /**
     * Does this [LoginError] mean the server looked at the credential we sent and
     * refused IT — as opposed to never answering, or refusing for a reason that has
     * nothing to do with the token?
     *
     * The classification has to happen here and only here. `VoxSilentLogin.kt` holds
     * the decision that consumes it and is deliberately free of Android and SDK
     * imports so it stays unit-testable with no device (its header, :3-6), and by the
     * time the error reaches it the enum has been stringified into a message. Parsing
     * that string back out would be a second source of truth for something the type
     * system already knows here.
     *
     * Exhaustive on purpose, with NO `else` branch: if Voximplant adds an eleventh
     * value, this stops compiling and someone classifies it deliberately. An `else`
     * would silently assign the new case to whichever side it was written on, which
     * is how a credential-destroying default gets introduced by an SDK bump nobody
     * read the changelog for.
     *
     * See refreshFailureProvesTokensDead for the per-value reasoning and the live-doc
     * quotes behind this split — in particular why MauAccessDenied is false.
     */
    private fun LoginError.rejectsStoredCredential(): Boolean = when (this) {
        LoginError.TokenExpired,
        LoginError.InvalidPassword,
        -> true

        LoginError.AccountFrozen,
        LoginError.InvalidUsername,
        LoginError.MauAccessDenied,
        LoginError.NetworkIssues,
        LoginError.Timeout,
        LoginError.Interrupted,
        LoginError.InternalError,
        LoginError.InvalidState,
        -> false
    }

    private suspend fun connectSuspend(): Unit = suspendCancellableCoroutine { cont ->
        Client.connect(ConnectOptions(VoxConfig.node), object : ConnectionCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onFailure(error: ConnectionError) {
                if (cont.isActive) cont.resumeWithException(VoxAuthException.Sdk("connect: $error"))
            }
        })
    }

    private suspend fun requestOneTimeKeySuspend(fullUsername: String): String =
        suspendCancellableCoroutine { cont ->
            Client.requestOneTimeKey(fullUsername, object : GenerateOneTimeKeyCallback {
                override fun onSuccess(key: String) { if (cont.isActive) cont.resume(key) }
                override fun onFailure(error: LoginError) {
                    if (cont.isActive) cont.resumeWithException(VoxAuthException.Sdk("requestOneTimeKey: $error"))
                }
            })
        }

    // authParams is @Nullable in the SDK signature for EVERY LoginCallback use
    // (one-time-key and access-token login alike) — must match here; a null means
    // login succeeded but the SDK gave us nothing new to persist (the previously
    // stored tokens, if any, are left as-is).
    private suspend fun loginWithOneTimeKeySuspend(fullUsername: String, hash: String): AuthParams? =
        suspendCancellableCoroutine { cont ->
            Client.loginWithOneTimeKey(fullUsername, hash, object : LoginCallback {
                override fun onSuccess(displayName: String, authParams: AuthParams?) {
                    if (cont.isActive) cont.resume(authParams)
                }
                override fun onFailure(error: LoginError) {
                    if (cont.isActive) cont.resumeWithException(VoxAuthException.Sdk("loginWithOneTimeKey: $error"))
                }
            })
        }

    private suspend fun loginWithAccessTokenSuspend(fullUsername: String, accessToken: String): AuthParams? =
        suspendCancellableCoroutine { cont ->
            Client.loginWithAccessToken(fullUsername, accessToken, object : LoginCallback {
                override fun onSuccess(displayName: String, authParams: AuthParams?) {
                    if (cont.isActive) cont.resume(authParams)
                }
                // Tagged for the same reason as refreshTokenSuspend below: this is the
                // OTHER call inside tryRefreshThenAccessToken's runCatching, so an
                // untagged failure here reached the same discard.
                override fun onFailure(error: LoginError) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            VoxAuthException.Sdk(
                                "loginWithAccessToken: $error",
                                credentialRejected = error.rejectsStoredCredential(),
                            ),
                        )
                    }
                }
            })
        }

    // Parameter order (fullUsername, refreshToken, callback) matches every other
    // Client.login*/refreshToken overload in this SDK (byte-verified via javap: all
    // take (String, String, Callback)) — NOT independently confirmed against a
    // rendered docs page (voximplant.com's docs app returned an empty client-side
    // search shell to automated fetches during this change; see the handoff
    // report). If this ever fails at the SDK boundary with an argument-shaped
    // error, re-check this ordering first.
    private suspend fun refreshTokenSuspend(fullUsername: String, refreshToken: String): AuthParams =
        suspendCancellableCoroutine { cont ->
            Client.refreshToken(fullUsername, refreshToken, object : RefreshTokenCallback {
                override fun onSuccess(authParams: AuthParams) {
                    if (cont.isActive) cont.resume(authParams)
                }
                // The classification travels WITH the exception. Stringifying the enum
                // into the message and stopping there is what made
                // refreshFailureProvesTokensDead unable to tell "your token is expired"
                // from "the network did not answer" — a phone waking on push with bad
                // signal gets NetworkIssues or Timeout, which unwind through a LIVE
                // coroutine, so unlike a cancellation nothing masks the wipe.
                override fun onFailure(error: LoginError) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            VoxAuthException.Sdk(
                                "refreshToken: $error",
                                credentialRejected = error.rejectsStoredCredential(),
                            ),
                        )
                    }
                }
            })
        }

    /**
     * `bundleId` is deliberately NULL, and that is the fix for the empty `push_results`.
     *
     * This used to pass `BuildConfig.APPLICATION_ID` unconditionally, which reads like
     * harmless extra precision and is not. Voximplant's own SDK v3 reference for
     * `PushConfig` (fetched live via `voximplant.com/api/v2/getDoc`,
     * `references.androidsdk3.android.sdk.core.pushconfig`) documents the parameter as
     * nullable and says, verbatim:
     *
     *   "Set **only** if push notifications are going to be sent across several Android
     *    apps via a single Voximplant application or if you add several push certificates."
     *
     * Neither condition holds here: one Voximplant application (`kalfa-rsvp`, 11107202),
     * one Android app, and exactly one push certificate — `npm run voximplant --
     * push-credentials` returns a single GOOGLE entry (#9108) whose `content` carries a
     * `sender_id` and **no bundle id at all**.
     *
     * The platform uses the bundle id to pick which certificate to send with. Registering
     * a token under a bundle that no certificate declares leaves the platform holding
     * nothing it can use — which is exactly the observed signature: `push_results: []`
     * with `"No push notifications has been sent"`, and Voximplant's own push
     * troubleshooting guide listing "no tokens found — push token was not registered for
     * this user/device" as the first cause of that shape. A rejected send would have
     * produced a POPULATED array carrying an error instead.
     *
     * If a second Android app or a second certificate is ever added to this Voximplant
     * application, this becomes required rather than harmful — and the certificate must
     * be uploaded WITH the matching package name at the same time. The two settings are
     * one decision, not two, and changing either alone silently breaks push.
     */
    internal companion object {
        private const val TAG = "VoxClientManager"

        /**
         * The bundle id sent with every push-token registration. NULL on purpose — the
         * reasoning, and the live-doc quote it rests on, are in registerPushTokenSuspend's
         * kdoc below. In a companion so VoxPushConfigTest can pin it without constructing
         * a manager (and therefore without a mocking library this project does not use).
         */
        internal val PUSH_BUNDLE_ID: String? = null

        /**
         * The ONE place a [PushConfig] is built, so a test can assert on the object that
         * actually reaches the SDK rather than on a constant sitting near it.
         *
         * The first version of this guard pinned [PUSH_BUNDLE_ID] directly, which sounds
         * equivalent and is not. The regression to fear is someone restoring
         * `PushConfig(token, BuildConfig.APPLICATION_ID)` at a call site in good faith —
         * and they have no reason to also touch a constant three lines away, which would
         * simply become dead. It is `internal` and still referenced by the test, so it
         * would not even raise an unused-declaration warning. The test would have kept
         * passing with the bug fully restored.
         *
         * Routing both call sites through here means reintroducing it requires either
         * editing this function — which the test exercises directly — or visibly deleting
         * a call to it and inlining `PushConfig` raw again, which is a far louder change
         * to read in review than swapping one argument.
         */
        internal fun buildPushConfig(token: String): PushConfig = PushConfig(token, PUSH_BUNDLE_ID)
    }

    private suspend fun registerPushTokenSuspend(token: String): Unit =
        suspendCancellableCoroutine { cont ->
            Client.registerForPushNotifications(
                buildPushConfig(token),
                object : RegisterPushTokenCallback {
                    override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                    override fun onFailure(error: PushTokenError) {
                        if (cont.isActive) {
                            cont.resumeWithException(VoxAuthException.Sdk("registerForPushNotifications: $error"))
                        }
                    }
                },
            )
        }

    private suspend fun unregisterPushTokenSuspend(token: String): Unit =
        suspendCancellableCoroutine { cont ->
            // Must mirror registerPushTokenSuspend exactly — see its kdoc. An unregister
            // that names a different bundle than the register did would not match the
            // token it is trying to remove, leaving a stale token registered for an agent
            // who has signed out.
            Client.unregisterFromPushNotifications(
                buildPushConfig(token),
                object : RegisterPushTokenCallback {
                    override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                    override fun onFailure(error: PushTokenError) {
                        if (cont.isActive) {
                            cont.resumeWithException(VoxAuthException.Sdk("unregisterFromPushNotifications: $error"))
                        }
                    }
                },
            )
        }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
}
