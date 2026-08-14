package me.kalfa.agentconsole.telephony.vox

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.telemetry.Telemetry
import me.kalfa.agentconsole.telemetry.TelemetryEvents

// The device-side end of Voximplant's push wake-up (guides.sdk.android-push;
// AGENTS.md "Push wake-up"). This is the reason `firebase-messaging` was a
// dependency since the project was scaffolded with zero lines of code touching it.
//
// FirebaseMessagingService is a Service, NOT a BroadcastReceiver: there is no
// goAsync() here. The framework already holds the process up WHILE onMessageReceived
// is executing — returning early is what tears it down mid-work, not what keeps it
// alive. So the app-side steps of the six-step sequence run BLOCKING inside
// onMessageReceived, under a hard timeout: for a killed app, this call is the
// process's entire lifeline until VoxClientManager.onIncomingCall fires (which, as
// of this change, nothing has wired yet — see the push-wake handoff report).
class VoxFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        // A push can cold-start the process straight into THIS service, with
        // MainActivity.onCreate never having run — exactly the case push wake-up
        // exists for. attach() is idempotent, so calling it again here is safe.
        DependencyContainer.attach(applicationContext, via = "fcm")
        Telemetry.emit(TelemetryEvents.FCM_SERVICE_CREATED)
    }

    // Fires only on token create/rotate — NOT on every push, and NOT for a device
    // that already has a stable token before this feature shipped (guide point 2:
    // re-register "when Firebase issues a new token"). If we're already connected,
    // re-register immediately; otherwise the next login (interactive or
    // push-triggered) registers whatever FirebaseMessaging.getInstance().token
    // returns AT THAT TIME, which is already this new one — registerCurrentPushToken
    // always fetches fresh rather than trusting a cached value.
    //
    // Deprecated by Firebase (confirmed via javap on firebase-messaging 25.1.1) in
    // favour of onRegistered(installationId) / FID-based registration — overridden
    // anyway, for the same reason VoxClientManager.registerCurrentPushToken keeps
    // `.token`: see that method's kdoc. This service does NOT override
    // onRegistered — a real FCM token, not a FID, is what Voximplant's PushConfig
    // (and Voximplant's own send path) is built around.
    @Deprecated(
        "Firebase deprecated onNewToken for FID-based onRegistered, but Voximplant's " +
            "PushConfig needs a real FCM token — see this method's body comment.",
    )
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Length only. The token itself is a credential and never reaches a log.
        Telemetry.emit(TelemetryEvents.FCM_TOKEN_REFRESHED, "len" to token.length.toString())
        val vcm = DependencyContainer.voxClientManager ?: return
        if (!vcm.isLoggedIn) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { vcm.registerCurrentPushToken() }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val startedAtMs = System.currentTimeMillis()

        // A Voximplant push IS a call attempt beginning, so this is where the
        // trace opens: every line from here until the leg ends shares one `sid`.
        // Only the KEY COUNT of the payload is recorded — the Voximplant push
        // data is opaque and may carry routing detail, so neither its keys nor
        // its values are ever logged.
        val isVoxPush = isVoximplantPush(message.data)
        if (isVoxPush) Telemetry.openCallSession("push")
        Telemetry.emit(
            TelemetryEvents.FCM_MESSAGE_RECEIVED,
            "vox" to isVoxPush.toString(),
            "keys" to message.data.size.toString(),
        )

        val vcm = DependencyContainer.voxClientManager
        val tokenStore = DependencyContainer.voxTokenStore
        if (vcm == null || tokenStore == null) {
            // Both are null when Supabase is unconfigured or attach() has not run.
            // Previously a bare `?: return` — the push simply vanished with nothing
            // recorded anywhere, which is indistinguishable from never arriving.
            Telemetry.emit(
                TelemetryEvents.FCM_NO_DEPENDENCY,
                "what" to if (vcm == null) "client_manager" else "token_store",
            )
            finishWake(startedAtMs, timedOut = false)
            return
        }

        val completed = runBlocking(Dispatchers.IO) {
            // No persisted identity ⇒ this device never completed a login ⇒ it
            // never registered for push ⇒ Voximplant would have had no token to
            // send to in the first place. Unreachable in practice; guarded anyway.
            val voxUsername = tokenStore.load()?.voxUsername ?: run {
                Telemetry.emit(TelemetryEvents.FCM_NO_IDENTITY)
                return@runBlocking true
            }

            val handler = VoxWakePushHandler(
                ensureLoggedIn = { vcm.ensureLoggedIn(voxUsername).getOrThrow() },
                handlePushNotification = { data -> vcm.handleRawPushNotification(data) },
                registerPushToken = { vcm.registerCurrentPushToken().getOrThrow() },
            )

            // Caps ONLY the app's own work (connect/login + handlePushNotification +
            // re-register) — it does NOT include FCM transit time before this method
            // started, nor the subsequent onIncomingCall/answer handshake, both
            // outside this service's control. 9s is a judgment call sized to sit
            // under Android's historical ~10s background-execution budget for FCM
            // data messages while leaving headroom inside the server's 15s
            // RING_RETRY_WINDOW_MS for those uncounted stages — it is NOT a measured
            // figure. Live-device timing across all three login paths (access-token
            // happy path / refresh path / interactive fallback) is still open; see
            // the push-wake handoff report.
            withTimeoutOrNull(WAKE_PUSH_TIMEOUT_MS) { handler.handle(message.data) } != null
        }

        finishWake(startedAtMs, timedOut = !completed)
    }

    /**
     * THE headline reading, and the last moment this app controls the process.
     *
     * A push-woken process can be torn down the instant `onMessageReceived`
     * returns, so this is the only place a verdict is guaranteed to be written.
     * `incoming=false` here says, in one line, that the push arrived and every
     * app-side step ran and the SDK still never produced a call — which is
     * precisely the question this whole channel was built to answer, and which
     * has until now had to be inferred from an absence of evidence.
     *
     * Both flushes cost nothing when telemetry is off (the default): they return
     * immediately. When it is on, the local one is bounded at 200ms and the
     * upload at 300ms — and **the upload is skipped entirely when the 9s budget
     * was already consumed.**
     *
     * That skip is not a micro-optimisation, it decouples two worst cases that
     * otherwise arrive together. `WAKE_PUSH_TIMEOUT_MS` is 9s, sized to sit under
     * Android's ~10s background-execution budget "leaving headroom", and AGENTS.md
     * flags that it already races the SDK's own 10s internal registration timeout.
     * 500ms is most of that headroom. Worse, `timedOut == true` means the full 9s
     * was spent, which in practice means the network was bad — which is exactly
     * when `flushUploadsBestEffort` burns its entire 300ms achieving nothing. So
     * the slow path and the slow flush correlated, and the sum landed at ~9.5s
     * against ~10s precisely on the runs least able to afford it.
     *
     * The local flush is kept unconditionally, because it is the one the whole
     * `tail -f` story rests on: the file is the record of truth (see
     * TelemetryLogFile's kdoc) and the upload is a convenience on top. Nothing is
     * lost by skipping the upload — the lines are on disk, and "שלח יומן" or the
     * next pump ships them. (Raised by `fixer`, whose correlation argument is what
     * makes this worth doing rather than shrugging at.)
     */
    private fun finishWake(startedAtMs: Long, timedOut: Boolean) {
        Telemetry.emit(
            TelemetryEvents.FCM_WAKE_DONE,
            "ms" to (System.currentTimeMillis() - startedAtMs).toString(),
            "timedout" to timedOut.toString(),
            "incoming" to Telemetry.incomingCallSeen().toString(),
        )
        Telemetry.flushLocalBlocking(LOCAL_FLUSH_MS)
        if (!timedOut) {
            runBlocking { Telemetry.flushUploadsBestEffort(UPLOAD_FLUSH_MS) }
        }
    }

    companion object {
        private const val WAKE_PUSH_TIMEOUT_MS = 9_000L
        private const val LOCAL_FLUSH_MS = 200L
        private const val UPLOAD_FLUSH_MS = 300L
    }
}
