package me.kalfa.agentconsole.telephony.vox

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
// NOT wired to run automatically. `ensureLoggedIn` MUST be called only when a real
// call has to be handled (monitor/takeover), never on launch or on Ready: every
// login counts against the Voximplant MAU quota (1000/month), so logging in before
// there is a feature to use it for is pure waste (and can hit MauAccessDenied).
class VoxClientManager(private val authClient: VoxSdkAuthClient) {

    private val _loginState = MutableStateFlow(VoxLoginState.LOGGED_OUT)
    val loginState: StateFlow<VoxLoginState> = _loginState.asStateFlow()

    // The human agent's incoming SDK leg (set by the future monitor/takeover flow).
    // Kept here so it survives across logins; null until that feature is wired.
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

    // connect → requestOneTimeKey → POST /api/agents/sdk-auth → loginWithOneTimeKey.
    // Serialized by a mutex so two concurrent callers run ONE login sequence. Safe to
    // call when already logged in (returns success without a second MAU-charged login).
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
            val oneTimeKey = requestOneTimeKeySuspend(fullUsername)
            val hash = authClient.fetchHash(oneTimeKey) // server-computed; may throw VoxAuthException
            loginWithOneTimeKeySuspend(fullUsername, hash)
            _loginState.value = VoxLoginState.LOGGED_IN
        }.onFailure { _loginState.value = VoxLoginState.FAILED }
    }

    fun logout() {
        runCatching { Client.disconnect() }
        _loginState.value = VoxLoginState.LOGGED_OUT
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

    private suspend fun loginWithOneTimeKeySuspend(fullUsername: String, hash: String): Unit =
        suspendCancellableCoroutine { cont ->
            Client.loginWithOneTimeKey(fullUsername, hash, object : LoginCallback {
                // authParams is @Nullable in the SDK signature — must match here.
                override fun onSuccess(displayName: String, authParams: AuthParams?) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onFailure(error: LoginError) {
                    if (cont.isActive) cont.resumeWithException(VoxAuthException.Sdk("login: $error"))
                }
            })
        }
}
