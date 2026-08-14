package me.kalfa.agentconsole.telephony.vox

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import me.kalfa.agentconsole.BuildConfig

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
            ensureInitialized()
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
        if (authParams != null) tokenStore.save(voxUsername, authParams)
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
    suspend fun registerCurrentPushToken(): Result<Unit> = runCatching {
        val token = FirebaseMessaging.getInstance().token.awaitTask()
        registerPushTokenSuspend(token)
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

    private suspend fun registerPushTokenSuspend(token: String): Unit =
        suspendCancellableCoroutine { cont ->
            Client.registerForPushNotifications(
                PushConfig(token, BuildConfig.APPLICATION_ID),
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
            Client.unregisterFromPushNotifications(
                PushConfig(token, BuildConfig.APPLICATION_ID),
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
