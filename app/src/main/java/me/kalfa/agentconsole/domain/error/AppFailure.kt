package me.kalfa.agentconsole.domain.error

sealed interface AppFailure {
    data object NetworkUnavailable : AppFailure
    data object Unauthorized : AppFailure
    data object Forbidden : AppFailure
    data object NotFound : AppFailure
    data object Conflict : AppFailure
    data object Validation : AppFailure
    data object RealtimeDisconnected : AppFailure
    data object Unknown : AppFailure
}
