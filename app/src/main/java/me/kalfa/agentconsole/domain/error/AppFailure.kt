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

    /**
     * The dial was refused, and the server said WHY.
     *
     * dial-intent answers a refusal with `403 {error, reason}` where reason is one
     * of a small stable set — dnc, opted_out, quiet_hours, not_found, stale,
     * attempt_cap, invalid_phone, lookup_failed. The app used to map every 403 to
     * Forbidden and tell the agent "אין לך הרשאה לבצע את הפעולה", which was wrong
     * in every one of those cases: the agent's permissions were never in question.
     *
     * Measured live 2026-08-17: a missed-call return failed with reason
     * `not_found`, and the screen reported a permissions problem — sending the
     * owner looking at roles for a defect that was an id resolved against the wrong
     * table. A refusal that misnames itself costs more than one that simply says
     * "no".
     */
    data class DialRefused(val reason: String, val voxCode: Int? = null) : AppFailure

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
