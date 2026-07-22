package me.kalfa.agentconsole.data

import java.io.IOException
import me.kalfa.agentconsole.domain.error.AppFailure

internal fun Throwable.toAppFailure(): AppFailure =
    when (this) {
        is IOException -> AppFailure.NetworkUnavailable
        else -> AppFailure.Unknown
    }

internal fun failureForStatus(status: Int): AppFailure =
    when (status) {
        401 -> AppFailure.Unauthorized
        403 -> AppFailure.Forbidden
        404 -> AppFailure.NotFound
        409 -> AppFailure.Conflict
        400, 422 -> AppFailure.Validation
        in 500..599 -> AppFailure.NetworkUnavailable
        else -> AppFailure.Unknown
    }
