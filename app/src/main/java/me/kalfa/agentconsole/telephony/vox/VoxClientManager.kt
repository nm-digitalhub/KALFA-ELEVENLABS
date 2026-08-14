package me.kalfa.agentconsole.telephony.vox

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.voximplant.android.sdk.calls.Call
import com.voximplant.android.sdk.calls.IncomingCallListener
import com.voximplant.android.sdk.calls.VICalls
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

    // The human agent's incoming SDK leg (set by the future monitor/takeover flow).
    // Kept here so it survives across logins; null until that feature is wired —
    // which means a push-delivered incoming call is currently NOT answered end to
    // end (it reaches this null listener and is dropped). See the push-wake handoff
    // report: this class gets the SDK logged in and registered for push; it does
    // not yet complete the call.
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
                onIncomingCall?.invoke(call, headers ?: emptyMap())
            }
        })
        Client.setClientSessionListener(object : ClientSessionListener {
            override fun onConnectionClosed(reason: DisconnectReason) {
                _loginState.value = VoxLoginState.LOGGED_OUT
            }
            override fun onReconnecting() { _loginState.value = VoxLoginState.CONNECTING }
            override fun onReconnected() { _loginState.value = VoxLoginState.LOGGED_IN }
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
                throw VoxAuthException.Sdk("sdk_init: ${e.message ?: e::class.simpleName}")
            }
            if (Client.clientState == ClientState.LoggedIn) return@runCatching
            val fullUsername = VoxConfig.fullUsername(voxUsername)

            if (Client.clientState != ClientState.Connected) {
                _loginState.value = VoxLoginState.CONNECTING
                connectSuspend()
            }
            _loginState.value = VoxLoginState.LOGGING_IN

            val stored = tokenStore.load()
            val plan = planSilentLogin(
                nowMs = System.currentTimeMillis(),
                isLoggedIn = false, // already returned above if true
                stored = stored,
                voxUsername = voxUsername,
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
        }.onFailure { _loginState.value = VoxLoginState.FAILED }
    }

    private suspend fun trySilentAccessToken(
        fullUsername: String,
        voxUsername: String,
        tokens: StoredVoxTokens,
    ): Boolean = runCatching {
        val authParams = loginWithAccessTokenSuspend(fullUsername, tokens.accessToken)
        if (authParams != null) tokenStore.save(voxUsername, authParams)
    }.isSuccess

    private suspend fun tryRefreshThenAccessToken(
        fullUsername: String,
        voxUsername: String,
        tokens: StoredVoxTokens,
    ): Boolean = runCatching {
        val refreshed = refreshTokenSuspend(fullUsername, tokens.refreshToken)
        tokenStore.save(voxUsername, refreshed) // save immediately: the refresh itself already rotated tokens
        val authParams = loginWithAccessTokenSuspend(fullUsername, refreshed.accessToken)
        if (authParams != null) tokenStore.save(voxUsername, authParams)
    }.onFailure {
        // Both tokens are apparently unusable (refresh rejected, or the freshly
        // refreshed access token was itself rejected) — clear rather than leave a
        // dead pair that would just be re-tried and re-fail on every future call.
        tokenStore.clear()
    }.isSuccess

    private suspend fun loginInteractively(fullUsername: String, voxUsername: String) {
        val oneTimeKey = requestOneTimeKeySuspend(fullUsername)
        val hash = authClient.fetchHash(oneTimeKey) // server-computed; may throw VoxAuthException
        val authParams = loginWithOneTimeKeySuspend(fullUsername, hash)
        // THE SDK IS LOGGED IN FROM HERE. Everything below is caching, and a caching
        // failure must not be reported as a failed login.
        //
        // This line used to be a bare `tokenStore.save(...)` inside ensureLoggedIn's
        // runCatching, so a DataStore write failure — a full disk, an IOException — made
        // ensureLoggedIn return failure AFTER loginWithOneTimeKey had already succeeded.
        // PresenceActions then skipped registerCurrentPushToken and published
        // "המכשיר לא נרשם לקבלת שיחות" on a device that was genuinely logged in. The
        // login was fine; only the cache was not.
        //
        // What is actually lost is the NEXT login's silent path: with no stored tokens,
        // planSilentLogin returns FallBackToInteractive and the following login pays the
        // one-time-key round trip again. That is a degradation, not a failure, and it is
        // the honest thing to report as such.
        //
        // CancellationException is re-thrown rather than logged: it is an Exception in
        // Kotlin, so a bare `catch (e: Exception)` here would swallow the cancellation
        // from ensurePushRegistration's withTimeoutOrNull and let a timed-out attempt
        // look like a successful login with a bad cache write. Same discipline as
        // PresenceActions.persistPushRegistrationOutcome.
        if (authParams != null) {
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
    }

    // Best-effort, called on explicit sign-out alongside forgetPersistedSession.
    suspend fun unregisterCurrentPushToken(): Result<Unit> = runCatching {
        val token = FirebaseMessaging.getInstance().token.awaitTask()
        unregisterPushTokenSuspend(token)
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
                override fun onFailure(error: LoginError) {
                    if (cont.isActive) cont.resumeWithException(VoxAuthException.Sdk("loginWithAccessToken: $error"))
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
                override fun onFailure(error: LoginError) {
                    if (cont.isActive) cont.resumeWithException(VoxAuthException.Sdk("refreshToken: $error"))
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
