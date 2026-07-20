package me.kalfa.agentconsole.data

import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.repository.CallRepository
import me.kalfa.agentconsole.domain.repository.CampaignRepository
import me.kalfa.agentconsole.domain.repository.RsvpRepository
import me.kalfa.agentconsole.domain.telephony.AgentPresence
import me.kalfa.agentconsole.domain.telephony.CallEngine
import me.kalfa.agentconsole.domain.telephony.CallSession
import me.kalfa.agentconsole.data.mock.MockCallSession
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
import java.util.UUID
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
    val updated_at: String? = null
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
    val stop_reason: String? = null
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
    val event_date: String? = null
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
    val campState = when {
        status?.lowercase() == "closed" -> CampaignState.COMPLETED
        !enabled -> CampaignState.PAUSED
        status?.lowercase() == "approved" -> CampaignState.ACTIVE
        else -> CampaignState.PAUSED
    }
    return Campaign(
        id = id,
        name = eventName?.let { "אישורי הגעה — $it" } ?: "קמפיין ${id.take(8)}",
        eventId = event_id ?: "",
        eventName = eventName ?: (event_id?.let { "אירוע ${it.take(8)}" } ?: ""),
        state = campState,
        totalTargets = total,
        completedTargets = done
    )
}

fun DbConsoleTarget.toDomain(): CampaignTarget = CampaignTarget(
    id = id,
    campaignId = campaign_id ?: "",
    guestId = contact_id ?: "",
    guestName = "איש קשר",
    phone = "", // PII not exposed in the console view
    attempts = current_step_index ?: 0,
    lastResult = stop_reason ?: status,
    callId = null
)

fun DbRsvpRow.toDomain(): RsvpResult = RsvpResult(
    id = id,
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

    private val _liveCalls = MutableStateFlow<List<Call>>(emptyList())
    override val liveCalls: StateFlow<List<Call>> = _liveCalls.asStateFlow()

    private val _callHistory = MutableStateFlow<List<Call>>(emptyList())
    override val callHistory: StateFlow<List<Call>> = _callHistory.asStateFlow()

    init {
        fetchCalls()
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

    private fun fetchCalls() {
        scope.launch {
            try {
                if (_eventNames.value.isEmpty()) {
                    val evs = client.postgrest["console_events"].select()
                        .decodeList<DbConsoleEvent>()
                    _eventNames.value = evs.associate { it.event_id to (it.event_name ?: "") }
                }
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
            try {
                val evs = client.postgrest["console_events"].select()
                    .decodeList<DbConsoleEvent>()
                val names = evs.associate { it.event_id to (it.event_name ?: "") }
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

    override fun toggleCampaign(campaignId: String) {
        // Campaign state is billing-coupled (SUMIT) and must NOT be flipped from the
        // client. Wire to POST beta.kalfa.me/api/campaigns/{id}/start|pause in Phase 2.
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

    override fun startOutboundCall(phone: String, customerName: String): CallSession {
        _currentSession.value?.hangup()
        
        val session = MockCallSession(
            id = "out-${UUID.randomUUID().toString().take(6)}",
            customerPhone = phone,
            customerName = customerName,
            initialState = CallState.RINGING,
            onHangup = {
                _currentSession.value = null
                setStatus(AgentStatus.READY)
            },
            onStateChange = {}
        )
        _currentSession.value = session
        setStatus(AgentStatus.IN_CALL)

        scope.launch(Dispatchers.IO) {
            try {
                val jwt = getJwt()
                httpClient.post("https://beta.kalfa.me/api/calls/outbound") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("{\"phone\":\"$phone\",\"event_id\":\"default-event\"}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return session
    }

    override fun monitorCall(callId: String): CallSession {
        _currentSession.value?.hangup()
        
        val session = MockCallSession(
            id = callId,
            customerPhone = "050-000-0000",
            customerName = "אורח משיחה $callId",
            initialState = CallState.MONITORED,
            onHangup = {
                _currentSession.value = null
            },
            onStateChange = {}
        )
        _currentSession.value = session

        scope.launch(Dispatchers.IO) {
            try {
                val jwt = getJwt()
                httpClient.post("https://beta.kalfa.me/api/calls/$callId/monitor") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("{\"mode\":\"monitor\"}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return session
    }

    override fun takeoverCall(callId: String): CallSession {
        val current = _currentSession.value
        if (current != null && current.id == callId) {
            val mockSess = current as MockCallSession
            mockSess.updateState(CallState.TAKEN_OVER)
            setStatus(AgentStatus.IN_CALL)
            
            scope.launch(Dispatchers.IO) {
                try {
                    val jwt = getJwt()
                    httpClient.post("https://beta.kalfa.me/api/calls/$callId/monitor") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("{\"mode\":\"takeover\"}")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return current
        }

        _currentSession.value?.hangup()
        
        val session = MockCallSession(
            id = callId,
            customerPhone = "050-000-0000",
            customerName = "אורח משיחה $callId",
            initialState = CallState.TAKEN_OVER,
            onHangup = {
                _currentSession.value = null
                setStatus(AgentStatus.READY)
            },
            onStateChange = {}
        )
        _currentSession.value = session
        setStatus(AgentStatus.IN_CALL)

        scope.launch(Dispatchers.IO) {
            try {
                val jwt = getJwt()
                httpClient.post("https://beta.kalfa.me/api/calls/$callId/monitor") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("{\"mode\":\"takeover\"}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return session
    }
}
