package me.kalfa.agentconsole.telephony.presence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kalfa.agentconsole.domain.error.AppFailure

// failure is the agent-facing coarse category (drives the consequence-focused Hebrew
// text); detail is the WHICH-STEP-FAILED diagnostic tag from
// VoxClientManager.registerCurrentPushToken ("fcm_token: ..." vs
// "registerForPushNotifications: ...") — see PersistedPushRegistrationFailure's kdoc
// for why it travels separately from AppFailure.
data class PushRegistrationFailureInfo(val failure: AppFailure, val detail: String?)

// In-process reactive mirror of PresenceStateStore's durable push-registration
// record — plain global object, same pattern as AppMessageCenter/AppVisibility
// (di/AppState.kt), so PresenceForegroundService's notification can react to it
// immediately without polling DataStore on every tick. PresenceForegroundService
// seeds this from the persisted record on startup (see its onCreate) so a value
// survives a process restart even before any new registration attempt runs.
object PushRegistrationState {
    private val _lastFailure = MutableStateFlow<PushRegistrationFailureInfo?>(null)
    val lastFailure: StateFlow<PushRegistrationFailureInfo?> = _lastFailure.asStateFlow()

    fun recordSuccess() {
        _lastFailure.value = null
    }

    fun recordFailure(failure: AppFailure, detail: String?) {
        _lastFailure.value = PushRegistrationFailureInfo(failure, detail)
    }

    fun seed(info: PushRegistrationFailureInfo?) {
        _lastFailure.value = info
    }
}
