package me.kalfa.agentconsole.domain.error

sealed interface AppFailure {
    data object NetworkUnavailable : AppFailure
    data object Unauthorized : AppFailure
    data object Forbidden : AppFailure
    data object NotFound : AppFailure
    data object Conflict : AppFailure
    data object Validation : AppFailure
    data object RealtimeDisconnected : AppFailure
    data object CallNoLongerActive : AppFailure
    data object CampaignHoldRequired : AppFailure
    data object NoActiveCampaign : AppFailure
    data object GuestMissingPhone : AppFailure
    data object AlreadyReached : AppFailure
    data object Unknown : AppFailure
}

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val reason: AppFailure) : AppResult<Nothing>
}

sealed interface RepositoryHealth {
    data object Loading : RepositoryHealth
    data object Fresh : RepositoryHealth
    data class Stale(
        val reason: AppFailure,
        val hasCachedData: Boolean
    ) : RepositoryHealth
}
