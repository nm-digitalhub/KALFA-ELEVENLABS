package me.kalfa.agentconsole.telephony.vox

import me.kalfa.agentconsole.telemetry.Telemetry
import me.kalfa.agentconsole.telemetry.TelemetryEvents

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
        if (!isVoximplantPush(data)) {
            Telemetry.emit(TelemetryEvents.WAKE_NOT_VOX_PUSH)
            return
        }
        Telemetry.emit(TelemetryEvents.WAKE_START)

        // Every step is best-effort and ALWAYS runs, regardless of whether an
        // earlier step failed: a failed silent login must not also throw away a
        // push the platform already sent (handlePushNotification is documented as
        // safe "in any state"), and a failed handlePushNotification must not skip
        // re-registering the token for every FUTURE push too.
        //
        // That best-effort discipline is exactly why each step is recorded
        // separately: `runCatching` deliberately discards the failure, so
        // without these three pairs there is no way to tell "all three succeeded
        // and the SDK still produced no call" from "the login failed silently and
        // the rest ran against a logged-out client". Those have opposite fixes.
        step(TelemetryEvents.WAKE_LOGIN_OK, TelemetryEvents.WAKE_LOGIN_FAIL) { ensureLoggedIn() }
        step(TelemetryEvents.WAKE_HANDLE_PUSH_OK, TelemetryEvents.WAKE_HANDLE_PUSH_FAIL) {
            handlePushNotification(data)
        }
        step(TelemetryEvents.WAKE_REGISTER_OK, TelemetryEvents.WAKE_REGISTER_FAIL) {
            registerPushToken()
        }
    }

    // Same runCatching semantics as before — the failure is still swallowed and
    // the next step still runs — with the outcome written down on the way past.
    // `err` is the exception CLASS NAME plus its own message, which for every
    // failure on this path is a tagged SDK string (VoxAuthException.Sdk); the
    // scrub in TelemetryEvent.kt redacts it if it ever carries anything else.
    private suspend fun step(okEvent: String, failEvent: String, body: suspend () -> Unit) {
        runCatching { body() }
            .onSuccess { Telemetry.emit(okEvent) }
            .onFailure { e ->
                Telemetry.emit(failEvent, "err" to (e.message ?: e::class.simpleName ?: "unknown"))
            }
    }
}
