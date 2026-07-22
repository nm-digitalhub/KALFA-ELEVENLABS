package me.kalfa.agentconsole.ui.state

import me.kalfa.agentconsole.domain.error.AppFailure

sealed interface LoadState<out T> {
    data object Initial : LoadState<Nothing>
    data object Loading : LoadState<Nothing>
    data class Content<T>(val value: T) : LoadState<T>
    data class Failure(
        val failure: AppFailure,
        val staleContentAvailable: Boolean
    ) : LoadState<Nothing>
}
