package me.kalfa.agentconsole.data

import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.repository.CallRepository
import me.kalfa.agentconsole.domain.repository.CampaignRepository
import me.kalfa.agentconsole.domain.repository.RsvpRepository
import me.kalfa.agentconsole.domain.telephony.AgentPresence
import me.kalfa.agentconsole.domain.telephony.CallEngine
import me.kalfa.agentconsole.domain.telephony.CallSession
import me.kalfa.agentconsole.domain.telephony.PresenceSyncState
import me.kalfa.agentconsole.domain.error.AppResult
import me.kalfa.agentconsole.domain.error.RepositoryHealth
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.statement.*
import kotlinx.serialization.json.jsonPrimitive
import me.kalfa.agentconsole.di.AppVisibility

// ─────────────────────────────────────────────────────────────────────────────
// DTOs — wired to the REAL production console schema (kalfa-event-magic):
//   Realtime tables : agent_status, console_call_feed (trigger-fed from call_attempts, no PII)
//   Read-only views : console_campaigns, console_rsvp_results, console_campaign_targets
// Writes to campaigns/targets/rsvp go through beta.kalfa.me API routes or the
// ElevenLabs client-tools pipeline — NEVER directly from this app.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class DbAgentStatus(
    val agent_id: String,
    val status: String,
    val updated_at: String? = null
)

@Serializable
data class DbConsoleCall(
    val call_attempt_id: String,
    val event_id: String? = null,
    val campaign_id: String? = null,
    val direction: String = "outbound",
    val kind: String = "ai_rsvp",
    val status: String? = null,
    val handled_by: String = "ai",
    val agent_id: String? = null,
    val rsvp_digit: String? = null,
    val finish_reason: String? = null,
    val call_duration_sec: Int? = null,
    val callback_iso: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    // Takeover-coordination columns (added 20.7) — read so a monitor/takeover
    // implementation can tell who owns a live call (spec §3/§8.5). Nullable +
    // defaulted, so they decode safely even before any row populates them.
    val takeover_claimed_at: String? = null,
    val takeover_request_id: String? = null,
    val participation_state: String? = null
)

@Serializable
data class DbConsoleCampaign(
    val id: String,
    val event_id: String? = null,
    val status: String? = null,
    val enabled: Boolean = false,
    val start_at: String? = null,
    val close_at: String? = null,
    val max_contacts: Int? = null
)

@Serializable
data class DbConsoleTarget(
    val id: String,
    val event_id: String? = null,
    val campaign_id: String? = null,
    val contact_id: String? = null,
    val status: String? = null,
    val current_step_index: Int? = null,
    val next_run_at: String? = null,
    val reached_at: String? = null,
    val reached_channel: String? = null,
    val stop_reason: String? = null,
    val guest_name: String? = null,
    val phone: String? = null
)

@Serializable
data class DbRsvpRow(
    val id: String,
    val event_id: String? = null,
    val guest_id: String? = null,
    val guest_name: String? = null,
    val attending: Boolean? = null,
    val adults: Int? = null,
    val kids: Int? = null,
    val note: String? = null,
    val created_at: String? = null
)

@Serializable
data class DbConsoleEvent(
    val event_id: String,
    val event_name: String? = null,
    val event_type: String? = null,
    val event_date: String? = null,
    val has_campaign: Boolean = false
)

fun DbConsoleEvent.toDomain(): ConsoleEvent = ConsoleEvent(
    id = event_id,
    name = event_name ?: "אירוע",
    type = event_type,
    date = event_date,
    hasCampaign = has_campaign
)

@Serializable
data class DbCallAnalysis(
    val call_attempt_id: String,
    val event_id: String? = null,
    val call_successful: String? = null,
    val status: String? = null,
    val score: Double? = null,
    val call_duration_secs: Int? = null,
    val termination_reason: String? = null,
    val el_eval: JsonObject? = null,
    val rsvp_status: String? = null,
    val adults: Int? = null,
    val children: Int? = null,
    val analysis_at: String? = null
)

fun DbCallAnalysis.toDomain(): CallAnalysis = CallAnalysis(
    callAttemptId = call_attempt_id,
    eventId = event_id,
    callSuccessful = call_successful,
    score = score,
    durationSec = call_duration_secs,
    terminationReason = termination_reason,
    rsvpStatus = rsvp_status,
    adults = adults,
    children = children,
    evalCriteria = el_eval?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
    analysisAt = analysis_at
)

// Live production statuses on call_attempts (verified 20.07.2026):
// in_progress | completed | no_answer | cancelled | no_response
private val TERMINAL_CALL_STATUSES =
    setOf("completed", "no_answer", "cancelled", "no_response", "failed", "voicemail")

fun DbConsoleCall.toDomain(eventName: String? = null): Call {
    val callKind = when (kind.lowercase()) {
        "inbound" -> CallKind.INBOUND
        "outbound" -> CallKind.OUTBOUND
        else -> CallKind.AI_RSVP
    }
    val st = status?.lowercase().orEmpty()
    val callState = when {
        st in TERMINAL_CALL_STATUSES -> CallState.DISCONNECTED
        handled_by == "agent" -> CallState.TAKEN_OVER
        st == "in_progress" -> CallState.ACTIVE
        st.isEmpty() || st == "created" || st == "queued" || st == "ringing" -> CallState.RINGING
        else -> CallState.ACTIVE
    }
    return Call(
        id = call_attempt_id,
        eventId = event_id ?: "",
        direction = direction,
        kind = callKind,
        voxSessionId = "vox-${call_attempt_id.take(8)}",
        customerPhone = "", // PII stays server-side by design; feed carries no phone
        customerName = "אורח",
        eventName = eventName ?: event_id?.let { "אירוע ${it.take(8)}" } ?: "אירוע",
        handledBy = handled_by,
        agentId = agent_id,
        state = callState,
        startedAt = created_at ?: "",
        answeredAt = null,
        endedAt = if (callState == CallState.DISCONNECTED) updated_at else null,
        durationSec = call_duration_sec ?: 0,
        recordingUrl = null, // recordings are service-role only; exposed later via API
        transcript = emptyList()
    )
}

fun DbConsoleCampaign.toDomain(total: Int, done: Int, eventName: String? = null): Campaign {
    // campaign_status enum: draft, pending_approval, approved, scheduled, active,
    // paused, closed, awaiting_invoice, billed, paid, cancelled. The ONLY sending
    // state is 'active' (console_campaigns.enabled is literally `status = 'active'`);
    // the earlier 'approved' value is pre-send. A finished campaign is closed/billed/
    // paid/cancelled; anything else is not-yet-sending → paused. (Before: only
    // 'approved' mapped to ACTIVE, so a genuinely active campaign showed "מושהה".)
    val raw = status?.lowercase()
    val campState = when (raw) {
        "active" -> CampaignState.ACTIVE
        "closed", "billed", "paid", "cancelled" -> CampaignState.COMPLETED
        else -> CampaignState.PAUSED
    }
    // Togglable from the console ONLY when the raw status is 'active'/'paused' —
    // the only states POST /api/campaigns/{id}/status accepts (spec §6.5). Other
    // pre-send states (approved/scheduled/…) 409 there; the UI shows no button.
    return Campaign(
        id = id,
        name = eventName?.let { "אישורי הגעה — $it" } ?: "קמפיין ${id.take(8)}",
        eventId = event_id ?: "",
        eventName = eventName ?: (event_id?.let { "אירוע ${it.take(8)}" } ?: ""),
        state = campState,
        totalTargets = total,
        completedTargets = done,
        runControllable = raw == "active" || raw == "paused"
    )
}

fun DbConsoleTarget.toDomain(): CampaignTarget = CampaignTarget(
    id = id,
    campaignId = campaign_id ?: "",
    guestId = contact_id ?: "",
    guestName = guest_name ?: "איש קשר",
    phone = phone ?: "", // empty unless view_customer_data (DB-gated)
    attempts = current_step_index ?: 0,
    lastResult = stop_reason ?: status,
    callId = null
)

@Serializable
data class DbConsoleEventGuest(
    val guest_id: String,
    val event_id: String,
    val guest_name: String? = null,
    val dialable: Boolean = false,
    val phone: String? = null, // null unless view_customer_data (DB-gated)
    val rsvp_status: String? = null,
    val has_active_campaign: Boolean = false,
    val reached_at: String? = null,
    val callback_scheduled_at: String? = null,
    val can_start_outreach_call: Boolean? = null,
    val call_block_reason: String? = null
)

fun DbConsoleEventGuest.toDomain(): EventGuest = EventGuest(
    guestId = guest_id,
    eventId = event_id,
    guestName = guest_name ?: "אורח",
    dialable = dialable,
    phone = phone ?: "",
    rsvpStatus = rsvp_status,
    hasActiveCampaign = has_active_campaign,
    reachedAt = reached_at,
    callbackScheduledAt = callback_scheduled_at,
    canStartOutreachCall = can_start_outreach_call,
    callBlockReason = call_block_reason
)

@Serializable
data class DbCallDispatchStatus(
    val dispatch_id: String,
    val event_id: String,
    val status: String,
    val reason: String? = null,
    val call_attempt_id: String? = null,
    val updated_at: String? = null
)

fun DbCallDispatchStatus.toDomain(): CallDispatchStatus = CallDispatchStatus(
    dispatchId = dispatch_id,
    eventId = event_id,
    status = status,
    reason = reason,
    callAttemptId = call_attempt_id,
    updatedAt = updated_at
)

@Serializable
private data class OutreachCallResponse(
    val status: String,
    val dispatch_id: String,
    val event_id: String
)

@Serializable
private data class ApiErrorResponse(val code: String? = null)

fun DbRsvpRow.toDomain(): RsvpResult = RsvpResult(
    id = id,
    eventId = event_id ?: "",
    callId = "",
    guestId = guest_id ?: "",
    guestName = guest_name ?: "אורח",
    answer = when (attending) {
        true -> RsvpAnswer.ATTENDING
        false -> RsvpAnswer.DECLINED
        null -> RsvpAnswer.CALLBACK
    },
    guestsCount = (adults ?: 0) + (kids ?: 0),
    notes = note ?: ""
)

class SupabaseCallRepository(private val client: SupabaseClient) : CallRepository {
    private val _health = MutableStateFlow<RepositoryHealth>(RepositoryHealth.Loading)
    override val health: StateFlow<RepositoryHealth> = _health.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _eventNames = MutableStateFlow<Map<String, String>>(emptyMap())
    override val eventNames: StateFlow<Map<String, String>> = _eventNames.asStateFlow()

    private val _events = MutableStateFlow<List<ConsoleEvent>>(emptyList())
    override val events: StateFlow<List<ConsoleEvent>> = _events.asStateFlow()

    private val _liveCalls = MutableStateFlow<List<Call>>(emptyList())
    override val liveCalls: StateFlow<List<Call>> = _liveCalls.asStateFlow()

    private val _callHistory = MutableStateFlow<List<Call>>(emptyList())
    override val callHistory: StateFlow<List<Call>> = _callHistory.asStateFlow()

    // Declared BEFORE init on purpose: property initializers and init blocks run in
    // declaration order, so a `= null` sitting below the block that starts the coroutine
    // which assigns it is a loaded gun.
    private var callFeedChannel: RealtimeChannel? = null

    init {
        // Nothing is read and no channel is joined until the session is signed in — see
        // SessionGate.kt for the measured reason. The wait cannot live in the UI layer:
        // this repository is constructed during MainActivity's FIRST composition
        // (`remember { DependencyContainer.incomingCallCoordinator }` -> callEngine ->
        // callRepository), i.e. while AuthGate is still showing its spinner, and again
        // from a bare PresenceForegroundService START_STICKY restart with no Activity at
        // all.
        scope.launch {
            client.signedInSessions().collect {
                fetchCalls()
                subscribeToCallFeed()
            }
        }
        // console_events is a VIEW (no realtime, §4) — poll lightly in the foreground
        // so a deleted/renamed event disappears without restarting the app (spec §8.4).
        scope.launch {
            while (isActive) {
                if (AppVisibility.isForeground.value && client.hasUserSession()) fetchEvents()
                delay(60_000)
            }
        }
    }

    /**
     * Joins the feed channel, creating it at most once and re-joining on every later
     * sign-in. Both halves are deliberate.
     *
     * The channel and its change flow are created ONCE because `postgresChangeFlow`
     * registers the binding that `subscribe()` sends inside its JOIN payload, and
     * because it throws outright ("You cannot call postgresChangeFlow after joining the
     * channel") once the channel is joined. `realtime.channel(id)` returns the existing
     * instance for a topic, so the guard here is about the flow, not the channel.
     *
     * `subscribe()` runs again on each later sign-in because supabase-kt drops the socket
     * on session loss (`Realtime.Config.disconnectOnSessionLoss` defaults to true) and
     * never reconnects by itself: `RealtimeImpl.init`'s status collector only acts while
     * the socket is CONNECTED. Without the re-join, signing out and back in inside one
     * process left the live feed permanently dead.
     */
    private suspend fun subscribeToCallFeed() {
        try {
            val channel = callFeedChannel
                ?: client.realtime.channel("db-console-call-feed").also { fresh ->
                    val changeFlow = fresh.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "console_call_feed"
                    }
                    // CRITICAL: collect BEFORE subscribe(), or events are lost.
                    scope.launch { changeFlow.collect { fetchCalls() } }
                    callFeedChannel = fresh
                }
            channel.subscribe()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Best-effort event-name lookup. Decoupled from the feed read on purpose:
     * console_events is only used to label calls, so a missing/failing view must
     * NOT block the realtime feed (which was the load-bearing bug — a single
     * failing console_events read left calls/history/campaigns permanently empty).
     */
    private suspend fun refreshEventNames() {
        if (_eventNames.value.isNotEmpty()) return
        fetchEvents()
    }

    // Full (unconditional) read of console_events → the event list + the name cache.
    // console_events is a VIEW, so Supabase Realtime never emits for it (§4 publishes
    // only agent_status / console_call_feed / human_agent_call_legs); the foreground
    // poll in init is what makes a DELETED or renamed event disappear without
    // restarting the app (spec §8.4). REPLACE semantics — _events is overwritten, so
    // a row that is gone upstream is dropped here too.
    private suspend fun fetchEvents() {
        if (!client.hasUserSession()) return
        try {
            val evs = client.postgrest["console_events"].select()
                .decodeList<DbConsoleEvent>()
            _eventNames.value = evs.associate { it.event_id to (it.event_name ?: "") }
            _events.value = evs.map { it.toDomain() }
        } catch (e: Exception) {
            e.printStackTrace() // names stay empty; calls still render with a fallback label
        }
    }

    private fun fetchCalls() {
        scope.launch {
            // Belt-and-braces against a caller that is not the signed-in gate:
            // refresh() (the "נסה שוב" button) and the realtime change flow both land
            // here. An anon read would not merely return nothing — it errors, and the
            // error is what the agent is shown.
            if (!client.hasUserSession()) return@launch
            // Event names are a display nicety — fetch them independently so a
            // failure here can never block the feed below.
            refreshEventNames()
            try {
                val names = _eventNames.value
                val rows = client.postgrest["console_call_feed"].select()
                    .decodeList<DbConsoleCall>()
                val calls = rows.map { it.toDomain(names[it.event_id]) }
                    .sortedByDescending { it.startedAt }
                _liveCalls.value = calls.filter { it.state != CallState.DISCONNECTED }
                _callHistory.value = calls.filter { it.state == CallState.DISCONNECTED }
                _health.value = RepositoryHealth.Fresh
            } catch (e: Exception) {
                e.printStackTrace()
                _health.value = RepositoryHealth.Stale(
                    reason = e.toAppFailure(),
                    hasCachedData = _liveCalls.value.isNotEmpty() || _callHistory.value.isNotEmpty()
                )
            }
        }
    }

    override fun refresh() { _eventNames.value = emptyMap(); fetchCalls() }

    override suspend fun getCallAnalysis(callId: String): AppResult<CallAnalysis?> = try {
        AppResult.Success(client.postgrest["console_call_analysis"].select {
            filter { eq("call_attempt_id", callId) }
        }.decodeList<DbCallAnalysis>().firstOrNull()?.toDomain())
    } catch (e: Exception) {
        e.printStackTrace()
        AppResult.Failure(e.toAppFailure())
    }

    override fun updateCall(call: Call) {
        // Agents may only flag takeover ownership on the feed row; everything else
        // is trigger-synced from call_attempts by the backend.
        scope.launch {
            try {
                client.postgrest["console_call_feed"].update({
                    set("handled_by", call.handledBy)
                    set("agent_id", call.agentId)
                }) {
                    filter { eq("call_attempt_id", call.id) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun addCall(call: Call) {
        // No-op by design: rows are created by the call_attempts trigger.
        // Outbound calls are started via POST beta.kalfa.me/api/calls/outbound.
    }
}

class SupabaseCampaignRepository(private val client: SupabaseClient) : CampaignRepository {
    private val _health = MutableStateFlow<RepositoryHealth>(RepositoryHealth.Loading)
    override val health: StateFlow<RepositoryHealth> = _health.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _campaigns = MutableStateFlow<List<Campaign>>(emptyList())
    override val campaigns: StateFlow<List<Campaign>> = _campaigns.asStateFlow()

    private val targetsMap = mutableMapOf<String, MutableStateFlow<List<CampaignTarget>>>()
    private val eventGuestsMap = mutableMapOf<String, MutableStateFlow<List<EventGuest>>>()
    private val httpClient = HttpClient(OkHttp)

    init {
        // First read waits for a signed-in session — see SessionGate.kt. This
        // repository happens to be constructed after sign-in today (ConsoleViewModel
        // builds it, and that only exists inside AuthGate), and the gate is a no-op in
        // that case: a new collector is replayed sessionStatus's current value. It is
        // here so the invariant holds for every repository rather than for the two that
        // are provably raced today.
        scope.launch { client.signedInSessions().collect { fetchCampaigns() } }
        // console_campaigns / console_campaign_targets are VIEWS — Supabase Realtime
        // does not emit postgres_changes for views, so refresh on a light poll.
        scope.launch {
            while (isActive) {
                if (AppVisibility.isForeground.value && client.hasUserSession()) fetchCampaigns()
                delay(60_000)
            }
        }
    }

    override fun refresh() = fetchCampaigns()

    private fun fetchCampaigns() {
        scope.launch {
            if (!client.hasUserSession()) return@launch
            // Event names label campaigns only — read them best-effort so a missing/
            // failing console_events view never blocks campaign+target population.
            val names: Map<String, String> = try {
                client.postgrest["console_events"].select()
                    .decodeList<DbConsoleEvent>()
                    .associate { it.event_id to (it.event_name ?: "") }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyMap()
            }
            try {
                val camps = client.postgrest["console_campaigns"].select()
                    .decodeList<DbConsoleCampaign>()
                val targets = client.postgrest["console_campaign_targets"].select()
                    .decodeList<DbConsoleTarget>()
                val byCampaign = targets.groupBy { it.campaign_id }
                _campaigns.value = camps.map { c ->
                    val t = byCampaign[c.id].orEmpty()
                    c.toDomain(total = t.size, done = t.count { it.reached_at != null }, eventName = names[c.event_id])
                }
                _health.value = RepositoryHealth.Fresh
                targetsMap.forEach { (cid, flow) ->
                    flow.value = byCampaign[cid].orEmpty().map { it.toDomain() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _health.value = RepositoryHealth.Stale(
                    reason = e.toAppFailure(),
                    hasCachedData = _campaigns.value.isNotEmpty()
                )
            }
        }
    }

    override fun getTargets(campaignId: String): StateFlow<List<CampaignTarget>> {
        val flow = targetsMap.getOrPut(campaignId) { MutableStateFlow(emptyList()) }
        fetchCampaigns()
        return flow.asStateFlow()
    }

    // The event's actual guests (console_event_guests), server-filtered by event.
    // Carries the REAL guests.id the manual-dial route resolves by, plus dialable +
    // has_active_campaign so the UI offers a dial only when the route would accept
    // it. Resilient: if the view is absent (migration not yet pushed) the read fails
    // quietly and the list stays empty rather than blocking the screen.
    override fun getEventGuests(eventId: String): StateFlow<List<EventGuest>> {
        val flow = eventGuestsMap.getOrPut(eventId) { MutableStateFlow(emptyList()) }
        scope.launch {
            if (!client.hasUserSession()) return@launch
            try {
                val rows = client.postgrest["console_event_guests"].select {
                    filter { eq("event_id", eventId) }
                }.decodeList<DbConsoleEventGuest>()
                flow.value = rows.map { it.toDomain() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return flow.asStateFlow()
    }

    // Flip a campaign active<->paused via the console route. Active -> pause;
    // anything else -> activate. Activation is billing-guarded server-side (the route
    // requires an authorized J5 hold), so a paused-with-no-hold campaign returns 409
    // and we surface the reason. On success we re-read so the badge/button update.
    override suspend fun toggleCampaign(campaignId: String): AppResult<Unit> {
        return try {
                val current = _campaigns.value.firstOrNull { it.id == campaignId }
                    ?: return AppResult.Failure(me.kalfa.agentconsole.domain.error.AppFailure.NotFound)
                val action = if (current.state == CampaignState.ACTIVE) "pause" else "activate"
                val jwt = client.auth.currentAccessTokenOrNull()
                    ?: return AppResult.Failure(me.kalfa.agentconsole.domain.error.AppFailure.Unauthorized)
                val body = buildJsonObject { put("action", action) }.toString()
                val resp = httpClient.post("https://beta.kalfa.me/api/campaigns/$campaignId/status") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                if (resp.status.value in 200..299) {
                    fetchCampaigns()
                    AppResult.Success(Unit)
                } else AppResult.Failure(
                    if (resp.status.value == 409) {
                        me.kalfa.agentconsole.domain.error.AppFailure.CampaignHoldRequired
                    } else {
                        failureForStatus(resp.status.value)
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                AppResult.Failure(e.toAppFailure())
            }
    }

    override fun updateTargetResult(targetId: String, result: String) {
        // outreach_state is orchestrator-owned; the console reads it only.
    }
}

class SupabaseRsvpRepository(private val client: SupabaseClient) : RsvpRepository {
    private val _health = MutableStateFlow<RepositoryHealth>(RepositoryHealth.Loading)
    override val health: StateFlow<RepositoryHealth> = _health.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _rsvpResults = MutableStateFlow<List<RsvpResult>>(emptyList())
    override val rsvpResults: StateFlow<List<RsvpResult>> = _rsvpResults.asStateFlow()

    init {
        // First read waits for a signed-in session — see SessionGate.kt. Unlike the
        // campaign repository this one IS raced today: it is constructed alongside the
        // call repository, during MainActivity's first composition, before AuthGate has
        // resolved anything.
        scope.launch { client.signedInSessions().collect { fetchRsvpResults() } }
        // console_rsvp_results is a VIEW (no realtime) — poll lightly; the dashboard's
        // freshness driver is console_call_feed realtime anyway.
        scope.launch {
            while (isActive) {
                if (AppVisibility.isForeground.value && client.hasUserSession()) fetchRsvpResults()
                delay(60_000)
            }
        }
    }

    override fun refresh() = fetchRsvpResults()

    private fun fetchRsvpResults() {
        scope.launch {
            if (!client.hasUserSession()) return@launch
            try {
                val rows = client.postgrest["console_rsvp_results"].select()
                    .decodeList<DbRsvpRow>()
                _rsvpResults.value = rows.map { it.toDomain() }
                _health.value = RepositoryHealth.Fresh
            } catch (e: Exception) {
                e.printStackTrace()
                _health.value = RepositoryHealth.Stale(
                    reason = e.toAppFailure(),
                    hasCachedData = _rsvpResults.value.isNotEmpty()
                )
            }
        }
    }

    override fun saveRsvpResult(result: RsvpResult) {
        // Read-only by design: RSVP outcomes are written by the ElevenLabs agent
        // client-tools pipeline (save_rsvp -> /api/voximplant/rsvp/:tok). The console
        // must never write them on the AI's behalf (AGENTS.md domain rule).
    }
}

class SupabaseCallEngineImpl(
    private val client: SupabaseClient,
    private val callRepository: CallRepository,
    private val rsvpRepository: RsvpRepository
) : CallEngine, AgentPresence {

    private val httpClient = HttpClient(OkHttp)
    
    private val _currentSession = MutableStateFlow<CallSession?>(null)
    override val currentSession: StateFlow<CallSession?> = _currentSession.asStateFlow()

    private val _activeAiCallsCount = MutableStateFlow(3)
    override val activeAiCallsCount: StateFlow<Int> = _activeAiCallsCount.asStateFlow()

    private val _queueDepth = MutableStateFlow(2)
    override val queueDepth: StateFlow<Int> = _queueDepth.asStateFlow()

    private val _dispatchStatuses = MutableStateFlow<Map<String, CallDispatchStatus>>(emptyMap())
    override val dispatchStatuses: StateFlow<Map<String, CallDispatchStatus>> =
        _dispatchStatuses.asStateFlow()
    private val trackedDispatchIds = MutableStateFlow<Set<String>>(emptySet())
    private val wireJson = Json { ignoreUnknownKeys = true }

    private val _currentStatus = MutableStateFlow(AgentStatus.NOT_READY)
    override val currentStatus: StateFlow<AgentStatus> = _currentStatus.asStateFlow()

    private val _shiftActive = MutableStateFlow(false)
    override val shiftActive: StateFlow<Boolean> = _shiftActive.asStateFlow()

    // See PresenceSyncState's kdoc (domain/telephony/Telephony.kt) for the measured
    // incident this exists to prevent. Shared by setStatus AND setShiftActive
    // deliberately (v1 scope, not an oversight): the two calls are always fired
    // together for the case that matters most (declaring READY — PresenceActions),
    // and tracking "is presence in a known-good, server-confirmed state" as one
    // signal is simpler and more honest for a UI to show than reconciling two
    // independent ones that could disagree.
    private val _syncState = MutableStateFlow<PresenceSyncState>(PresenceSyncState.Synced)
    override val syncState: StateFlow<PresenceSyncState> = _syncState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Declared before init — see the same fields on SupabaseCallRepository for why.
    private var agentStatusChannel: RealtimeChannel? = null
    private var dispatchStatusChannel: RealtimeChannel? = null

    init {
        // Every read and both channel joins wait for a signed-in session — see
        // SessionGate.kt. This engine is on the same cold-start path as the call
        // repository (DependencyContainer.incomingCallCoordinator constructs it during
        // MainActivity's first composition, and PresenceForegroundService.
        // startForegroundCompat constructs it with no Activity at all), so both its
        // channels were joining as anon on the very run where it matters most.
        //
        // fetchAgentStatus in particular was worse than a failed read: it returns early
        // when there is no user id, so on a cold start the first status read silently did
        // nothing at all, and was then only ever re-driven by the agent_status channel
        // that had itself just joined unauthenticated.
        scope.launch(Dispatchers.IO) {
            client.signedInSessions().collect {
                fetchAgentStatus()
                subscribeToAgentStatus()
                subscribeToDispatchStatus()
                fetchTrackedDispatchStatuses()
            }
        }
        // Reconciliation poll is intentional: views and Realtime connections can
        // be interrupted while the app is backgrounded. On foreground/reconnect,
        // every dispatch_id still being tracked is read from the source of truth.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (AppVisibility.isForeground.value &&
                    client.hasUserSession() &&
                    trackedDispatchIds.value.isNotEmpty()
                ) {
                    fetchTrackedDispatchStatuses()
                }
                delay(15_000)
            }
        }
    }

    // Created once, re-joined on each later sign-in — see
    // SupabaseCallRepository.subscribeToCallFeed for the full reasoning behind that
    // split; it applies verbatim to both channels here.
    private suspend fun subscribeToAgentStatus() {
        try {
            val channel = agentStatusChannel
                ?: client.realtime.channel("agent_status_channel").also { fresh ->
                    val changeFlow = fresh.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "agent_status"
                    }
                    scope.launch(Dispatchers.IO) { changeFlow.collect { fetchAgentStatus() } }
                    agentStatusChannel = fresh
                }
            channel.subscribe()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun subscribeToDispatchStatus() {
        try {
            val channel = dispatchStatusChannel
                ?: client.realtime.channel("call_dispatch_status_channel").also { fresh ->
                    val changes = fresh.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "call_dispatch_status"
                    }
                    // Supabase can emit immediately after subscribe; collect first.
                    scope.launch(Dispatchers.IO) { changes.collect { fetchTrackedDispatchStatuses() } }
                    dispatchStatusChannel = fresh
                }
            channel.subscribe()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun fetchTrackedDispatchStatuses() {
        if (!client.hasUserSession()) return
        val ids = trackedDispatchIds.value
        if (ids.isEmpty()) return
        try {
            val rows = ids.flatMap { dispatchId ->
                client.postgrest["call_dispatch_status"].select {
                    filter { eq("dispatch_id", dispatchId) }
                }.decodeList<DbCallDispatchStatus>()
            }
            if (rows.isNotEmpty()) {
                _dispatchStatuses.update { current ->
                    current + rows.associate { it.dispatch_id to it.toDomain() }
                }
                val finalIds = rows.filter { it.status != "accepted" }
                    .mapTo(mutableSetOf()) { it.dispatch_id }
                if (finalIds.isNotEmpty()) {
                    trackedDispatchIds.update { it - finalIds }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun fetchAgentStatus() {
        try {
            val agentId = client.auth.currentSessionOrNull()?.user?.id ?: return
            val statusList = client.postgrest["agent_status"].select {
                filter {
                    eq("agent_id", agentId)
                }
            }.decodeList<DbAgentStatus>()
            
            if (statusList.isNotEmpty()) {
                val dbStatus = statusList.first()
                val mapped = when (dbStatus.status.lowercase()) {
                    "ready" -> AgentStatus.READY
                    "dnd" -> AgentStatus.DND
                    "in_call" -> AgentStatus.IN_CALL
                    else -> AgentStatus.NOT_READY
                }
                _currentStatus.value = mapped
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getJwt(): String {
        return client.auth.currentAccessTokenOrNull() ?: ""
    }

    /**
     * Why the presence writes no longer test `getJwt().isEmpty()`.
     *
     * `currentAccessTokenOrNull()` returns null in THREE unrelated situations, and
     * the caller cannot tell them apart from the null alone. Verified against
     * supabase-kt's own source and against the installed auth-kt 3.1.4 artifact,
     * which ships all four `SessionStatus` subclasses and exposes
     * `Auth.getSessionStatus(): StateFlow<SessionStatus>`:
     *
     *  - `NotAuthenticated` — genuinely signed out. Only here is "יש להתחבר" true.
     *  - `Initializing`     — the session is still being loaded from storage. Normal
     *                         for a second or so after process start, which is
     *                         exactly when PresenceForegroundService takes its first
     *                         heartbeat.
     *  - `RefreshFailure`   — a session EXISTS but could not be refreshed (the
     *                         library's own note: "for an expired session with a
     *                         network error, sessionStatus becomes RefreshFailure,
     *                         so these both return null").
     *
     * Treating all three as "not signed in" is what produced the message the owner
     * photographed: "לא מחובר. שיחות לא יגיעו עד ההתחברות." one minute after the same
     * notification read "סטטוס: זמין". Telling an agent who IS signed in to sign in
     * sends them to fix the one thing that is not broken, and the real cause —
     * a session still loading, or a refresh that failed — goes unnamed.
     *
     * `Initializing` is waited out rather than reported, because it resolves by
     * itself; that is the whole difference between a transient and a fault.
     */
    // Long enough to cover a normal cold-start session load, short enough that a
    // heartbeat never blocks meaningfully — the next one is 30s behind it anyway.
    private val AUTH_SETTLE_TIMEOUT_MS = 3_000L

    private suspend fun awaitAuthToken(): AuthToken {
        // awaitInitialization() is the library's OWN wait, and preferable to the
        // hand-rolled `first { it !is Initializing }` this replaces. Per supabase-kt's
        // AuthImpl, it returns once the status has settled — including when
        // auto-loading an expired session fails on a network error and the status
        // becomes RefreshFailure. A filter on Initializing alone gets that case right
        // by accident; using the library's own signal means it stays right if the
        // status set ever grows.
        withTimeoutOrNull(AUTH_SETTLE_TIMEOUT_MS) { client.auth.awaitInitialization() }
        val settled = client.auth.sessionStatus.value

        return when (settled) {
            is SessionStatus.Authenticated -> {
                val token = client.auth.currentAccessTokenOrNull()
                if (token.isNullOrEmpty()) AuthToken.Unsettled else AuthToken.Ready(token)
            }
            is SessionStatus.NotAuthenticated -> AuthToken.SignedOut
            is SessionStatus.RefreshFailure -> AuthToken.Expired
            SessionStatus.Initializing -> AuthToken.Unsettled
        }
    }

    private sealed interface AuthToken {
        data class Ready(val jwt: String) : AuthToken

        /** Genuinely signed out — the only state where asking the agent to sign in is honest. */
        data object SignedOut : AuthToken

        /** A session exists but could not be refreshed. Expired, not absent. */
        data object Expired : AuthToken

        /**
         * Still initializing after the wait, or authenticated with no token yet.
         * Retryable by nature: the next heartbeat is 30s away and will re-read it.
         */
        data object Unsettled : AuthToken
    }

    private fun AuthToken.toFailure(): me.kalfa.agentconsole.domain.error.AppFailure = when (this) {
        is AuthToken.Ready -> me.kalfa.agentconsole.domain.error.AppFailure.Unknown // unreachable; callers branch on Ready first
        AuthToken.SignedOut -> me.kalfa.agentconsole.domain.error.AppFailure.NotSignedIn
        AuthToken.Expired -> me.kalfa.agentconsole.domain.error.AppFailure.Unauthorized
        AuthToken.Unsettled -> me.kalfa.agentconsole.domain.error.AppFailure.Unknown
    }

    // Was fire-and-forget (Unit, bare catch { printStackTrace() }) — converted to
    // suspend/AppResult after a measured live incident: an agent reinstalled the app
    // (clearing the Supabase session), tapped "זמין", and the app showed Ready while
    // an empty-JWT POST 401'd and was silently discarded — nothing on screen
    // contradicted the false claim, and the server never saw the write. currentStatus
    // still updates optimistically FIRST (immediate UI responsiveness, e.g. the
    // Dashboard's status buttons), but syncState is what a caller — in particular the
    // persistent presence notification — must consult before claiming this is real.
    override suspend fun setStatus(status: AgentStatus): AppResult<Unit> {
        _currentStatus.value = status
        _syncState.value = PresenceSyncState.Pending
        return withContext(Dispatchers.IO) {
            try {
                val auth = awaitAuthToken()
                if (auth !is AuthToken.Ready) {
                    return@withContext failStatus(auth.toFailure())
                }
                val jwt = auth.jwt
                val statusStr = when (status) {
                    AgentStatus.READY -> "ready"
                    AgentStatus.DND -> "dnd"
                    AgentStatus.IN_CALL -> "in_call"
                    AgentStatus.NOT_READY -> "not_ready"
                }
                val resp = httpClient.post("https://beta.kalfa.me/api/agents/status") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("{\"status\":\"$statusStr\"}")
                }
                if (resp.status.value !in 200..299) {
                    return@withContext failStatus(failureForStatus(resp.status.value))
                }

                val agentId = client.auth.currentSessionOrNull()?.user?.id
                if (agentId != null) {
                    client.postgrest["agent_status"].upsert(DbAgentStatus(agent_id = agentId, status = statusStr))
                }
                _syncState.value = PresenceSyncState.Synced
                AppResult.Success(Unit)
            } catch (e: Exception) {
                e.printStackTrace()
                failStatus(e.toAppFailure())
            }
        }
    }

    private fun failStatus(failure: me.kalfa.agentconsole.domain.error.AppFailure): AppResult<Unit> {
        _syncState.value = PresenceSyncState.Failed(failure)
        return AppResult.Failure(failure)
    }

    // Standing "on shift" intent, POSTed to /api/agents/shift — separate write from
    // setStatus/agent_status above (see AgentPresence.setShiftActive kdoc). Same
    // suspend/AppResult conversion and same reason as setStatus above.
    override suspend fun setShiftActive(active: Boolean): AppResult<Unit> {
        _shiftActive.value = active
        _syncState.value = PresenceSyncState.Pending
        return withContext(Dispatchers.IO) {
            try {
                val auth = awaitAuthToken()
                if (auth !is AuthToken.Ready) {
                    return@withContext failStatus(auth.toFailure())
                }
                val jwt = auth.jwt
                val resp = httpClient.post("https://beta.kalfa.me/api/agents/shift") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("{\"active\":$active}")
                }
                if (resp.status.value !in 200..299) {
                    return@withContext failStatus(failureForStatus(resp.status.value))
                }
                _syncState.value = PresenceSyncState.Synced
                AppResult.Success(Unit)
            } catch (e: Exception) {
                e.printStackTrace()
                failStatus(e.toAppFailure())
            }
        }
    }

    // See docs/android-presence-and-call-ux.md §3. Both are UI-thread-cheap
    // StateFlow writes; the FGS/UI observers of currentSession pick these up through
    // the existing ConsoleViewModel wiring, unchanged.
    override fun attachIncomingSession(session: CallSession) {
        _currentSession.value = session
    }

    override fun clearAttachedSession() {
        _currentSession.value = null
    }

    override suspend fun sendAgentCommand(
        callId: String,
        command: String,
        payload: Map<String, String>
    ): AppResult<Unit> = try {
        val jwt = getJwt()
        val body = buildJsonObject {
            put("command", command)
            payload.forEach { (k, v) -> put(k, v) }
        }.toString()
        val resp = httpClient.post("https://beta.kalfa.me/api/calls/$callId/agent-command") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (resp.status.value in 200..299) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(
                if (resp.status.value == 409) {
                    me.kalfa.agentconsole.domain.error.AppFailure.CallNoLongerActive
                } else {
                    failureForStatus(resp.status.value)
                }
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        AppResult.Failure(e.toAppFailure())
    }

    // Hang up the live call (the guest conversation) via the dedicated /end route —
    // NOT an agent-command. Reuses the same JWT + client as sendAgentCommand; the
    // route reads no body, so none is sent. 2xx = delivered (async teardown); the
    // call row records the real outcome, and the live-calls list drops it once the
    // terminal callback fires.
    override suspend fun endCall(callId: String): AppResult<Unit> = try {
        val jwt = getJwt()
        val resp = httpClient.post("https://beta.kalfa.me/api/calls/$callId/end") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
        }
        if (resp.status.value in 200..299) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(
                if (resp.status.value == 409) {
                    me.kalfa.agentconsole.domain.error.AppFailure.CallNoLongerActive
                } else {
                    failureForStatus(resp.status.value)
                }
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        AppResult.Failure(e.toAppFailure())
    }

    // Enqueue a real outbound AI call to an existing guest. ENQUEUE-ONLY: this
    // POSTs {guest_id} to /api/events/{eventId}/outreach-call and the worker owns
    // the gate chain + StartScenarios. Never dials from here. Same JWT + client as
    // the command routes.
    override suspend fun enqueueOutboundCall(
        eventId: String,
        guestId: String
    ): AppResult<OutboundDispatchReceipt> = try {
        val jwt = getJwt()
        val body = buildJsonObject { put("guest_id", guestId) }.toString()
        val resp = httpClient.post("https://beta.kalfa.me/api/events/$eventId/outreach-call") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val responseText = resp.bodyAsText()
        if (resp.status.value == 202) {
            val accepted = wireJson.decodeFromString<OutreachCallResponse>(responseText)
            val receipt = OutboundDispatchReceipt(
                dispatchId = accepted.dispatch_id,
                eventId = accepted.event_id
            )
            trackedDispatchIds.update { it + receipt.dispatchId }
            _dispatchStatuses.update {
                it + (receipt.dispatchId to CallDispatchStatus(
                    dispatchId = receipt.dispatchId,
                    eventId = receipt.eventId,
                    status = "accepted",
                    reason = null,
                    callAttemptId = null,
                    updatedAt = null
                ))
            }
            scope.launch(Dispatchers.IO) { fetchTrackedDispatchStatuses() }
            AppResult.Success(receipt)
        } else {
            val code = runCatching {
                wireJson.decodeFromString<ApiErrorResponse>(responseText).code
            }.getOrNull()
            AppResult.Failure(
                when (resp.status.value) {
                    409 -> if (code == "already_reached") {
                        me.kalfa.agentconsole.domain.error.AppFailure.AlreadyReached
                    } else {
                        me.kalfa.agentconsole.domain.error.AppFailure.NoActiveCampaign
                    }
                    422 -> me.kalfa.agentconsole.domain.error.AppFailure.GuestMissingPhone
                    else -> failureForStatus(resp.status.value)
                }
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        AppResult.Failure(e.toAppFailure())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live-agent telephony (app-initiated outbound leg / monitor / takeover) is NOT
    // wired yet. It requires a real Voximplant SDK leg answered ON the device plus
    // the backend attach handshake — that ships in the telephony-wiring step.
    //
    // These previously fabricated a `MockCallSession` and fired speculative POSTs to
    // routes that do not match the real backend contract (`/api/calls/outbound`,
    // `/api/calls/{id}/monitor` with an ad-hoc body). That was the UI-honesty
    // landmine: a fake in-call surface presented as a real call. They now fail loudly
    // instead of faking. The ViewModel never calls these today (monitor / takeover /
    // free outbound dial are gated to an honest "בקרוב" notice), so this is
    // unreachable in production, not a regression — the throws are replaced by the
    // real leg in the telephony-wiring step.
    // ─────────────────────────────────────────────────────────────────────────
    override fun startOutboundCall(phone: String, customerName: String): CallSession =
        throw UnsupportedOperationException(
            "startOutboundCall is not wired: app-initiated dialing is enqueue-only via " +
                "enqueueOutboundCall(eventId, guestId); a live agent leg ships in the telephony-wiring step.",
        )

    override fun monitorCall(callId: String): CallSession =
        throw UnsupportedOperationException(
            "monitorCall is not wired: the real receive-only SDK leg ships in the telephony-wiring step.",
        )

    override fun takeoverCall(callId: String): CallSession =
        throw UnsupportedOperationException(
            "takeoverCall is not wired: the real takeover SDK leg + atomic claim ship in the telephony-wiring step.",
        )
}
