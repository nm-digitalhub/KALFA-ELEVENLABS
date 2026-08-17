package me.kalfa.agentconsole.domain.error

sealed interface AppFailure {
    data object NetworkUnavailable : AppFailure

    /** A session that WAS valid has since expired or been rejected server-side. */
    data object Unauthorized : AppFailure

    /**
     * Distinct from Unauthorized: there was never a session to expire in the first
     * place (e.g. the app was reinstalled and the Supabase session cleared, or the
     * agent signed out). Measured live: conflating the two told an agent who had
     * simply never signed in on a fresh install that their session had "expired" —
     * factually wrong and confusing about what to do next.
     */
    data object NotSignedIn : AppFailure
    data object Forbidden : AppFailure
    data object NotFound : AppFailure
    data object Conflict : AppFailure
    data object Validation : AppFailure
    data object RealtimeDisconnected : AppFailure
    data object CallNoLongerActive : AppFailure

    /**
     * The dial was refused ONLY because of the hour — outside 08:00–19:00 Sun–Thu or
     * 08:00–13:00 Fri.
     *
     * Its own failure, separate from Forbidden, because it is the one refusal an
     * agent may overrule: the caller is waiting, the agent is working, and a default
     * business hour is a judgement they can make. Every other refusal on that route
     * — DNC, opt-out, Shabbat, the attempt cap — is final, and folding this in with
     * them would either hide a legitimate call or invite someone to offer an override
     * for a rule that must not have one.
     */
    data object OutsideCallHours : AppFailure
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
