package me.kalfa.agentconsole.telephony.vox

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.kalfa.agentconsole.di.DependencyContainer

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
        DependencyContainer.attach(applicationContext)
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
        val vcm = DependencyContainer.voxClientManager ?: return
        if (!vcm.isLoggedIn) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { vcm.registerCurrentPushToken() }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val vcm = DependencyContainer.voxClientManager ?: return
        val tokenStore = DependencyContainer.voxTokenStore ?: return

        runBlocking(Dispatchers.IO) {
            // No persisted identity ⇒ this device never completed a login ⇒ it
            // never registered for push ⇒ Voximplant would have had no token to
            // send to in the first place. Unreachable in practice; guarded anyway.
            val voxUsername = tokenStore.load()?.voxUsername ?: return@runBlocking

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
            withTimeoutOrNull(WAKE_PUSH_TIMEOUT_MS) { handler.handle(message.data) }
        }
    }

    companion object {
        private const val WAKE_PUSH_TIMEOUT_MS = 9_000L
    }
}
