package me.kalfa.agentconsole.domain.repository

import me.kalfa.agentconsole.domain.model.Call
import me.kalfa.agentconsole.domain.model.Campaign
import me.kalfa.agentconsole.domain.model.CampaignTarget
import me.kalfa.agentconsole.domain.model.EventGuest
import me.kalfa.agentconsole.domain.model.RsvpResult
import me.kalfa.agentconsole.domain.error.AppResult
import me.kalfa.agentconsole.domain.error.RepositoryHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Constant flows the interface defaults below hand back.
 *
 * An interface has no backing field, so an expression-bodied default getter re-runs its
 * right-hand side on EVERY read. These defaults were `get() = MutableStateFlow(...)`,
 * which returned a brand-new flow each time — a value that can never change and is never
 * the same object twice, so any `collect`/`combine` built on it observes one emission and
 * then nothing, forever. Same defect found in domain/telephony/Telephony.kt by the crash
 * audit (2026-08-14); swept here rather than left for the next reader to rediscover.
 *
 * Every real implementation overrides these, so no production surface is known to be
 * affected — but that is exactly what made it survive, and the mock/debug path in the
 * telephony copy WAS silently dead because of it.
 *
 * Note `CampaignRepository.getEventGuests(eventId)` is deliberately NOT changed: it takes
 * a parameter, so a per-call flow is the correct shape for a factory. It shares the
 * never-updates property, which is fine for a default meaning "this repo has none".
 */
private object DefaultRepositoryFlows {
    val fresh: StateFlow<RepositoryHealth> = MutableStateFlow(RepositoryHealth.Fresh)
    val noEventNames: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    val noEvents: StateFlow<List<me.kalfa.agentconsole.domain.model.ConsoleEvent>> =
        MutableStateFlow(emptyList())
}

interface CallRepository {
    fun refresh() {}
    val health: StateFlow<RepositoryHealth>
        get() = DefaultRepositoryFlows.fresh
    suspend fun getCallAnalysis(callId: String): AppResult<me.kalfa.agentconsole.domain.model.CallAnalysis?> =
        AppResult.Success(null)
    val eventNames: kotlinx.coroutines.flow.StateFlow<Map<String, String>>
        get() = DefaultRepositoryFlows.noEventNames
    val events: kotlinx.coroutines.flow.StateFlow<List<me.kalfa.agentconsole.domain.model.ConsoleEvent>>
        get() = DefaultRepositoryFlows.noEvents
    val liveCalls: StateFlow<List<Call>>
    val callHistory: StateFlow<List<Call>>
    
    fun updateCall(call: Call)
    fun addCall(call: Call)
}

interface CampaignRepository {
    fun refresh() {}
    val health: StateFlow<RepositoryHealth>
        get() = DefaultRepositoryFlows.fresh
    val campaigns: StateFlow<List<Campaign>>
    fun getTargets(campaignId: String): StateFlow<List<CampaignTarget>>
    // The event's actual guests (console_event_guests), used by the manual-dial UI.
    // Default is empty so the mock/debug repo needs no change.
    fun getEventGuests(eventId: String): StateFlow<List<EventGuest>> =
        MutableStateFlow(emptyList())
    suspend fun toggleCampaign(campaignId: String): AppResult<Unit> = AppResult.Success(Unit)
    fun updateTargetResult(targetId: String, result: String)
}

interface RsvpRepository {
    fun refresh() {}
    val health: StateFlow<RepositoryHealth>
        get() = DefaultRepositoryFlows.fresh
    val rsvpResults: StateFlow<List<RsvpResult>>
    fun saveRsvpResult(result: RsvpResult)
}
