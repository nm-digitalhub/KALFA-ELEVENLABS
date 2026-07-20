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

@Serializable
data class DbAgentStatus(
    val agent_id: String,
    val status: String,
    val updated_at: String? = null
)

@Serializable
data class DbCall(
    val id: String,
    val direction: String,
    val kind: String,
    val customer_phone: String,
    val handled_by: String,
    val agent_id: String? = null,
    val state: String,
    val started_at: String,
    val answered_at: String? = null,
    val ended_at: String? = null,
    val duration_sec: Int = 0,
    val recording_url: String? = null
)

@Serializable
data class DbCampaign(
    val id: String,
    val name: String,
    val event_id: String,
    val state: String
)

@Serializable
data class DbCampaignTarget(
    val id: String,
    val campaign_id: String,
    val guest_id: String,
    val phone: String,
    val attempts: Int,
    val last_result: String? = null
)

@Serializable
data class DbRsvpResult(
    val call_id: String,
    val guest_id: String,
    val answer: String,
    val guests_count: Int,
    val notes: String
)

fun DbCall.toDomain(): Call {
    val callKind = when (kind.lowercase()) {
        "inbound" -> CallKind.INBOUND
        "outbound" -> CallKind.OUTBOUND
        else -> CallKind.AI_RSVP
    }
    val callState = when (state.lowercase()) {
        "ringing" -> CallState.RINGING
        "active" -> CallState.ACTIVE
        "monitored" -> CallState.MONITORED
        "taken_over" -> CallState.TAKEN_OVER
        else -> CallState.DISCONNECTED
    }
    return Call(
        id = id,
        direction = direction,
        kind = callKind,
        voxSessionId = "vox-${id.take(8)}",
        customerPhone = customer_phone,
        customerName = "אורח $id",
        eventName = "אירוע",
        handledBy = handled_by,
        agentId = agent_id,
        state = callState,
        startedAt = started_at,
        answeredAt = answered_at,
        endedAt = ended_at,
        durationSec = duration_sec,
        recordingUrl = recording_url,
        transcript = emptyList()
    )
}

fun DbCampaign.toDomain(): Campaign {
    val campState = when (state.lowercase()) {
        "active" -> CampaignState.ACTIVE
        "completed" -> CampaignState.COMPLETED
        else -> CampaignState.PAUSED
    }
    return Campaign(
        id = id,
        name = name,
        eventId = event_id,
        eventName = "אירוע $event_id",
        state = campState,
        totalTargets = 100,
        completedTargets = 45
    )
}

fun DbCampaignTarget.toDomain(): CampaignTarget {
    return CampaignTarget(
        id = id,
        campaignId = campaign_id,
        guestId = guest_id,
        guestName = "אורח $guest_id",
        phone = phone,
        attempts = attempts,
        lastResult = last_result,
        callId = null
    )
}

fun DbRsvpResult.toDomain(): RsvpResult {
    val ans = when (answer.lowercase()) {
        "attending" -> RsvpAnswer.ATTENDING
        "declined" -> RsvpAnswer.DECLINED
        "maybe" -> RsvpAnswer.MAYBE
        else -> RsvpAnswer.CALLBACK
    }
    return RsvpResult(
        id = "rsvp-$call_id",
        callId = call_id,
        guestId = guest_id,
        guestName = "אורח $guest_id",
        answer = ans,
        guestsCount = guests_count,
        notes = notes
    )
}

class SupabaseCallRepository(private val client: SupabaseClient) : CallRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _liveCalls = MutableStateFlow<List<Call>>(emptyList())
    override val liveCalls: StateFlow<List<Call>> = _liveCalls.asStateFlow()

    private val _callHistory = MutableStateFlow<List<Call>>(emptyList())
    override val callHistory: StateFlow<List<Call>> = _callHistory.asStateFlow()

    init {
        fetchCalls()
        
        scope.launch {
            try {
                val channel = client.realtime.channel("calls_channel")
                val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "calls"
                }
                
                launch {
                    changeFlow.collect {
                        fetchCalls()
                    }
                }
                
                channel.subscribe()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchCalls() {
        scope.launch {
            try {
                val dbCalls = client.postgrest["calls"].select().decodeList<DbCall>()
                val domainCalls = dbCalls.map { it.toDomain() }
                _liveCalls.value = domainCalls.filter { it.state != CallState.DISCONNECTED }
                _callHistory.value = domainCalls.filter { it.state == CallState.DISCONNECTED }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun updateCall(call: Call) {
        scope.launch {
            try {
                client.postgrest["calls"].update({
                    set("state", call.state.name.lowercase())
                    set("duration_sec", call.durationSec)
                    set("ended_at", call.endedAt)
                }) {
                    filter {
                        eq("id", call.id)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun addCall(call: Call) {
        scope.launch {
            try {
                val dbCall = DbCall(
                    id = call.id,
                    direction = call.direction,
                    kind = call.kind.name.lowercase(),
                    customer_phone = call.customerPhone,
                    handled_by = call.handledBy,
                    agent_id = call.agentId,
                    state = call.state.name.lowercase(),
                    started_at = call.startedAt,
                    answered_at = call.answeredAt,
                    ended_at = call.endedAt,
                    duration_sec = call.durationSec,
                    recording_url = call.recordingUrl
                )
                client.postgrest["calls"].insert(dbCall)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

class SupabaseCampaignRepository(private val client: SupabaseClient) : CampaignRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _campaigns = MutableStateFlow<List<Campaign>>(emptyList())
    override val campaigns: StateFlow<List<Campaign>> = _campaigns.asStateFlow()

    private val targetsMap = mutableMapOf<String, MutableStateFlow<List<CampaignTarget>>>()

    init {
        fetchCampaigns()
        
        scope.launch {
            try {
                val channel = client.realtime.channel("campaigns_channel")
                val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "campaigns"
                }
                
                launch {
                    changeFlow.collect {
                        fetchCampaigns()
                    }
                }
                
                channel.subscribe()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchCampaigns() {
        scope.launch {
            try {
                val dbCamps = client.postgrest["campaigns"].select().decodeList<DbCampaign>()
                _campaigns.value = dbCamps.map { it.toDomain() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun getTargets(campaignId: String): StateFlow<List<CampaignTarget>> {
        val flow = targetsMap.getOrPut(campaignId) { MutableStateFlow(emptyList()) }
        fetchTargets(campaignId, flow)
        return flow.asStateFlow()
    }

    private fun fetchTargets(campaignId: String, flow: MutableStateFlow<List<CampaignTarget>>) {
        scope.launch {
            try {
                val dbTargets = client.postgrest["campaign_targets"].select {
                    filter {
                        eq("campaign_id", campaignId)
                    }
                }.decodeList<DbCampaignTarget>()
                flow.value = dbTargets.map { it.toDomain() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun toggleCampaign(campaignId: String) {
        scope.launch {
            try {
                val current = _campaigns.value.find { it.id == campaignId } ?: return@launch
                val nextState = if (current.state == CampaignState.ACTIVE) "paused" else "active"
                
                client.postgrest["campaigns"].update({
                    set("state", nextState)
                }) {
                    filter {
                        eq("id", campaignId)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun updateTargetResult(targetId: String, result: String) {
        scope.launch {
            try {
                client.postgrest["campaign_targets"].update({
                    set("last_result", result)
                }) {
                    filter {
                        eq("id", targetId)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

class SupabaseRsvpRepository(private val client: SupabaseClient) : RsvpRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _rsvpResults = MutableStateFlow<List<RsvpResult>>(emptyList())
    override val rsvpResults: StateFlow<List<RsvpResult>> = _rsvpResults.asStateFlow()

    init {
        fetchRsvpResults()
        
        scope.launch {
            try {
                val channel = client.realtime.channel("rsvp_channel")
                val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "rsvp_results"
                }
                
                launch {
                    changeFlow.collect {
                        fetchRsvpResults()
                    }
                }
                
                channel.subscribe()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchRsvpResults() {
        scope.launch {
            try {
                val dbRsvps = client.postgrest["rsvp_results"].select().decodeList<DbRsvpResult>()
                _rsvpResults.value = dbRsvps.map { it.toDomain() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun saveRsvpResult(result: RsvpResult) {
        scope.launch {
            try {
                val dbRsvp = DbRsvpResult(
                    call_id = result.callId,
                    guest_id = result.guestId,
                    answer = result.answer.name.lowercase(),
                    guests_count = result.guestsCount,
                    notes = result.notes
                )
                client.postgrest["rsvp_results"].insert(dbRsvp)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
