package me.kalfa.agentconsole.telephony.vox

// Executes the app-side portion of Voximplant's documented six-step wake sequence
// (guides.sdk.android-push, mirrored in AGENTS.md "Push wake-up"): receive push ->
// connect/log in if necessary -> handlePushNotification -> re-register the push
// token -> wait for onIncomingCall -> show call UI. This class owns steps 2-4; the
// predicate (step 1) gates entry via isVoximplantPush, and steps 5-6 belong to the
// CallEngine wiring phase (VoxClientManager.onIncomingCall), which is NOT built yet
// — see the push-wake handoff report before assuming a push results in an answered
// call end to end.
//
// Every SDK call is injected as a suspend closure instead of touching
// com.voximplant...Client directly, so the ORDER of operations — the one thing a
// push-woken app cannot get wrong without silently losing calls — is unit-testable
// with no Android or Voximplant SDK on the classpath (see VoxWakePushHandlerTest).
class VoxWakePushHandler(
    private val ensureLoggedIn: suspend () -> Unit,
    private val handlePushNotification: suspend (Map<String, String>) -> Unit,
    private val registerPushToken: suspend () -> Unit,
) {
    suspend fun handle(data: Map<String, String>) {
        if (!isVoximplantPush(data)) return

        // Every step is best-effort and ALWAYS runs, regardless of whether an
        // earlier step failed: a failed silent login must not also throw away a
        // push the platform already sent (handlePushNotification is documented as
        // safe "in any state"), and a failed handlePushNotification must not skip
        // re-registering the token for every FUTURE push too.
        runCatching { ensureLoggedIn() }
        runCatching { handlePushNotification(data) }
        runCatching { registerPushToken() }
    }
}
