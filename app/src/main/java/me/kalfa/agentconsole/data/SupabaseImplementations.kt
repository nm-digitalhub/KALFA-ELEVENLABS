package me.kalfa.agentconsole.data

import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.repository.CallRepository
import me.kalfa.agentconsole.domain.repository.CampaignRepository
import me.kalfa.agentconsole.domain.repository.RsvpRepository
import me.kalfa.agentconsole.domain.telephony.AgentPresence
import me.kalfa.agentconsole.domain.telephony.CallEngine
import me.kalfa.agentconsole.domain.telephony.CallSession
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.statement.*
import kotlinx.serialization.json.jsonPrimitive
import me.kalfa.agentconsole.di.ErrorBus
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
    val has_active_campaign: Boolean = false
)

fun DbConsoleEventGuest.toDomain(): EventGuest = EventGuest(
    guestId = guest_id,
    eventId = event_id,
    guestName = guest_name ?: "אורח",
    dialable = dialable,
    phone = phone ?: "",
    rsvpStatus = rsvp_status,
    hasActiveCampaign = has_active_campaign
)

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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _eventNames = MutableStateFlow<Map<String, String>>(emptyMap())
    override val eventNames: StateFlow<Map<String, String>> = _eventNames.asStateFlow()

    private val _events = MutableStateFlow<List<ConsoleEvent>>(emptyList())
    override val events: StateFlow<List<ConsoleEvent>> = _events.asStateFlow()

    private val _liveCalls = MutableStateFlow<List<Call>>(emptyList())
    override val liveCalls: StateFlow<List<Call>> = _liveCalls.asStateFlow()

    private val _callHistory = MutableStateFlow<List<Call>>(emptyList())
    override val callHistory: StateFlow<List<Call>> = _callHistory.asStateFlow()

    init {
        fetchCalls()
        // console_events is a VIEW (no realtime, §4) — poll lightly in the foreground
        // so a deleted/renamed event disappears without restarting the app (spec §8.4).
        scope.launch {
            while (isActive) {
                if (AppVisibility.isForeground.value) fetchEvents()
                delay(60_000)
            }
        }
        scope.launch {
            try {
                val channel = client.realtime.channel("db-console-call-feed")
                val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "console_call_feed"
                }
                // CRITICAL: collect BEFORE subscribe(), or events are lost.
                launch { changeFlow.collect { fetchCalls() } }
                channel.subscribe()
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                ErrorBus.clear()
            } catch (e: Exception) {
                e.printStackTrace()
                ErrorBus.post("שגיאה בטעינת שיחות — בדוק חיבור")
            }
        }
    }

    override fun refresh() { _eventNames.value = emptyMap(); fetchCalls() }

    override suspend fun getCallAnalysis(callId: String): CallAnalysis? = try {
        client.postgrest["console_call_analysis"].select {
            filter { eq("call_attempt_id", callId) }
        }.decodeList<DbCallAnalysis>().firstOrNull()?.toDomain()
    } catch (e: Exception) {
        e.printStackTrace()
        ErrorBus.post("שגיאה בטעינת ניתוח שיחה")
        null
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _campaigns = MutableStateFlow<List<Campaign>>(emptyList())
    override val campaigns: StateFlow<List<Campaign>> = _campaigns.asStateFlow()

    private val targetsMap = mutableMapOf<String, MutableStateFlow<List<CampaignTarget>>>()
    private val eventGuestsMap = mutableMapOf<String, MutableStateFlow<List<EventGuest>>>()
    private val httpClient = HttpClient(OkHttp)

    init {
        // console_campaigns / console_campaign_targets are VIEWS — Supabase Realtime
        // does not emit postgres_changes for views, so refresh on a light poll.
        scope.launch {
            while (isActive) {
                if (AppVisibility.isForeground.value) fetchCampaigns()
                delay(60_000)
            }
        }
    }

    override fun refresh() = fetchCampaigns()

    private fun fetchCampaigns() {
        scope.launch {
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
                ErrorBus.clear()
                targetsMap.forEach { (cid, flow) ->
                    flow.value = byCampaign[cid].orEmpty().map { it.toDomain() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ErrorBus.post("שגיאה בטעינת קמפיינים — בדוק חיבור")
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
    override fun toggleCampaign(campaignId: String) {
        scope.launch {
            try {
                val current = _campaigns.value.firstOrNull { it.id == campaignId } ?: return@launch
                val action = if (current.state == CampaignState.ACTIVE) "pause" else "activate"
                val jwt = client.auth.currentAccessTokenOrNull() ?: return@launch
                val body = buildJsonObject { put("action", action) }.toString()
                val resp = httpClient.post("https://beta.kalfa.me/api/campaigns/$campaignId/status") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                if (resp.status.value in 200..299) {
                    ErrorBus.clear()
                    fetchCampaigns()
                } else ErrorBus.post(
                    when (resp.status.value) {
                        403 -> "אין הרשאה לשנות את מצב הקמפיין"
                        404 -> "הקמפיין לא נמצא"
                        409 -> if (action == "activate")
                            "להפעלת הקמפיין נדרשת תפיסת מסגרת מאושרת" else "לא ניתן לשנות את מצב הקמפיין כעת"
                        else -> "שינוי מצב הקמפיין נכשל (${resp.status.value})"
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                ErrorBus.post("שינוי מצב הקמפיין נכשל — בדוק חיבור")
            }
        }
    }

    override fun updateTargetResult(targetId: String, result: String) {
        // outreach_state is orchestrator-owned; the console reads it only.
    }
}

class SupabaseRsvpRepository(private val client: SupabaseClient) : RsvpRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _rsvpResults = MutableStateFlow<List<RsvpResult>>(emptyList())
    override val rsvpResults: StateFlow<List<RsvpResult>> = _rsvpResults.asStateFlow()

    init {
        // console_rsvp_results is a VIEW (no realtime) — poll lightly; the dashboard's
        // freshness driver is console_call_feed realtime anyway.
        scope.launch {
            while (isActive) {
                if (AppVisibility.isForeground.value) fetchRsvpResults()
                delay(60_000)
            }
        }
    }

    override fun refresh() = fetchRsvpResults()

    private fun fetchRsvpResults() {
        scope.launch {
            try {
                val rows = client.postgrest["console_rsvp_results"].select()
                    .decodeList<DbRsvpRow>()
                _rsvpResults.value = rows.map { it.toDomain() }
                ErrorBus.clear()
            } catch (e: Exception) {
                e.printStackTrace()
                ErrorBus.post("שגיאה בטעינת תוצאות RSVP")
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

    private val _currentStatus = MutableStateFlow(AgentStatus.NOT_READY)
    override val currentStatus: StateFlow<AgentStatus> = _currentStatus.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch(Dispatchers.IO) {
            try {
                fetchAgentStatus()
                
                val channel = client.realtime.channel("agent_status_channel")
                val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "agent_status"
                }
                
                launch {
                    changeFlow.collect {
                        fetchAgentStatus()
                    }
                }
                
                channel.subscribe()
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

    override fun setStatus(status: AgentStatus) {
        _currentStatus.value = status
        scope.launch(Dispatchers.IO) {
            try {
                val jwt = getJwt()
                val statusStr = when (status) {
                    AgentStatus.READY -> "ready"
                    AgentStatus.DND -> "dnd"
                    AgentStatus.IN_CALL -> "in_call"
                    AgentStatus.NOT_READY -> "not_ready"
                }
                httpClient.post("https://beta.kalfa.me/api/agents/status") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("{\"status\":\"$statusStr\"}")
                }
                
                val agentId = client.auth.currentSessionOrNull()?.user?.id
                if (agentId != null) {
                    client.postgrest["agent_status"].upsert(DbAgentStatus(agent_id = agentId, status = statusStr))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override suspend fun sendAgentCommand(
        callId: String,
        command: String,
        payload: Map<String, String>
    ): Boolean = try {
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
        val ok = resp.status.value in 200..299
        if (!ok) ErrorBus.post(
            if (resp.status.value == 409) "השיחה כבר לא פעילה" else "פקודת AI נכשלה (${resp.status.value})"
        )
        ok
    } catch (e: Exception) {
        e.printStackTrace()
        ErrorBus.post("פקודת AI נכשלה — בדוק חיבור")
        false
    }

    // Hang up the live call (the guest conversation) via the dedicated /end route —
    // NOT an agent-command. Reuses the same JWT + client as sendAgentCommand; the
    // route reads no body, so none is sent. 2xx = delivered (async teardown); the
    // call row records the real outcome, and the live-calls list drops it once the
    // terminal callback fires.
    override suspend fun endCall(callId: String): Boolean = try {
        val jwt = getJwt()
        val resp = httpClient.post("https://beta.kalfa.me/api/calls/$callId/end") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
        }
        val ok = resp.status.value in 200..299
        if (!ok) ErrorBus.post(
            if (resp.status.value == 409) "השיחה כבר לא פעילה" else "ניתוק השיחה נכשל (${resp.status.value})"
        )
        ok
    } catch (e: Exception) {
        e.printStackTrace()
        ErrorBus.post("ניתוק השיחה נכשל — בדוק חיבור")
        false
    }

    // Enqueue a real outbound AI call to an existing guest. ENQUEUE-ONLY: this
    // POSTs {guest_id} to /api/events/{eventId}/outreach-call and the worker owns
    // the gate chain + StartScenarios. Never dials from here. Same JWT + client as
    // the command routes.
    override suspend fun enqueueOutboundCall(eventId: String, guestId: String): Boolean = try {
        val jwt = getJwt()
        val body = buildJsonObject { put("guest_id", guestId) }.toString()
        val resp = httpClient.post("https://beta.kalfa.me/api/events/$eventId/outreach-call") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val ok = resp.status.value in 200..299
        if (!ok) ErrorBus.post(
            when (resp.status.value) {
                403 -> "אין הרשאה לחיוג"
                404 -> "אורח לא נמצא"
                409 -> "לאירוע אין קמפיין"
                422 -> "לאורח אין מספר חיוג"
                else -> "הוספת השיחה לתור נכשלה (${resp.status.value})"
            }
        )
        ok
    } catch (e: Exception) {
        e.printStackTrace()
        ErrorBus.post("הוספת השיחה לתור נכשלה — בדוק חיבור")
        false
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
