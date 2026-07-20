package me.kalfa.agentconsole.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.telephony.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import me.kalfa.agentconsole.di.ErrorBus

sealed class Screen {
    object Dashboard : Screen()
    object LiveCalls : Screen()
    object Campaigns : Screen()
    object History : Screen()
    object Events : Screen()
    data class EventDetail(val eventId: String) : Screen()
    data class CallDetail(val call: Call, val returnTo: Screen = History) : Screen()
}

@Serializable
private data class MeRow(
    val user_id: String,
    val display_name: String? = null,
    val platform_role: String? = null,
    val platform_rank: Int = 0,
    val permissions: List<String> = emptyList()
)

data class ConsoleUiState(
    val currentScreen: Screen = Screen.Dashboard,
    val agentStatus: AgentStatus = AgentStatus.NOT_READY,
    val agentName: String = "נציג KALFA",
    val agentEmail: String = "",
    val me: ConsoleMe? = null,
    val events: List<ConsoleEvent> = emptyList(),
    val eventSummaries: List<EventSummary> = emptyList(),
    val selectedEventFilter: String? = null,
    val connectionError: String? = null,
    val selectedAnalysis: CallAnalysis? = null,
    val liveTranscripts: Map<String, List<TranscriptLine>> = emptyMap(),
    val analysisLoading: Boolean = false,
    val activeAiCallsCount: Int = 0,
    val queueDepth: Int = 2,
    val liveCalls: List<Call> = emptyList(),
    val callHistory: List<Call> = emptyList(),
    val campaigns: List<Campaign> = emptyList(),
    val rsvpResults: List<RsvpResult> = emptyList(),
    val currentSession: CallSession? = null,
    val currentSessionState: CallState = CallState.DISCONNECTED,
    val currentSessionMuted: Boolean = false,
    val currentSessionHeld: Boolean = false,
    val currentSessionDuration: Int = 0,
    // Form inputs for In-Call screen
    val inCallNotes: String = "",
    val inCallRsvpAnswer: RsvpAnswer = RsvpAnswer.ATTENDING,
    val inCallGuestsCount: Int = 1
)

class ConsoleViewModel : ViewModel() {
    private val callRepo = DependencyContainer.callRepository
    private val campaignRepo = DependencyContainer.campaignRepository
    private val rsvpRepo = DependencyContainer.rsvpRepository
    private val callEngine = DependencyContainer.callEngine
    private val agentPresence = DependencyContainer.agentPresence

    private val _uiState = MutableStateFlow(ConsoleUiState())
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    init {
        // Collect from all dependencies to compile the single, unified UI state!
        viewModelScope.launch {
            combine(
                agentPresence.currentStatus,
                callEngine.activeAiCallsCount,
                callEngine.queueDepth,
                callRepo.liveCalls,
                callRepo.callHistory,
                campaignRepo.campaigns,
                rsvpRepo.rsvpResults,
                callEngine.currentSession
            ) { array ->
                val status = array[0] as AgentStatus
                val activeCount = array[1] as Int
                val queue = array[2] as Int
                @Suppress("UNCHECKED_CAST")
                val live = array[3] as List<Call>
                @Suppress("UNCHECKED_CAST")
                val history = array[4] as List<Call>
                @Suppress("UNCHECKED_CAST")
                val camps = array[5] as List<Campaign>
                @Suppress("UNCHECKED_CAST")
                val rsvps = array[6] as List<RsvpResult>
                val session = array[7] as CallSession?

                val isReal = DependencyContainer.isSupabaseConfigured
                val realActive = if (isReal) live.count { it.handledBy == "ai" && it.state != CallState.DISCONNECTED } else activeCount
                val realQueue = if (isReal) camps.filter { it.state == CampaignState.ACTIVE }
                    .sumOf { (it.totalTargets - it.completedTargets).coerceAtLeast(0) } else queue
                _uiState.update { state ->
                    state.copy(
                        agentStatus = status,
                        activeAiCallsCount = realActive,
                        queueDepth = realQueue,
                        liveCalls = live,
                        callHistory = history,
                        campaigns = camps,
                        rsvpResults = rsvps,
                        currentSession = session
                    )
                }
            }.collect()
        }

        // Collect from active session if present
        viewModelScope.launch {
            _uiState.map { it.currentSession }.distinctUntilChanged().collectLatest { session ->
                if (session != null) {
                    // Reset form inputs for a new call
                    _uiState.update { 
                        it.copy(
                            inCallNotes = "", 
                            inCallRsvpAnswer = RsvpAnswer.ATTENDING, 
                            inCallGuestsCount = 1
                        ) 
                    }
                    
                    combine(
                        session.state,
                        session.isMuted,
                        session.isHeld,
                        session.durationSec
                    ) { state, muted, held, duration ->
                        _uiState.update { ui ->
                            ui.copy(
                                currentSessionState = state,
                                currentSessionMuted = muted,
                                currentSessionHeld = held,
                                currentSessionDuration = duration
                            )
                        }
                    }.collect()
                } else {
                    _uiState.update { ui ->
                        ui.copy(
                            currentSessionState = CallState.DISCONNECTED,
                            currentSessionMuted = false,
                            currentSessionHeld = false,
                            currentSessionDuration = 0
                        )
                    }
                }
            }
        }

        viewModelScope.launch { ErrorBus.lastError.collect { err -> _uiState.update { it.copy(connectionError = err) } } }

        // Live captions: keep Broadcast subscriptions in sync with active AI calls
        DependencyContainer.liveTranscriptManager?.let { mgr ->
            viewModelScope.launch {
                callRepo.liveCalls.collect { calls ->
                    mgr.sync(calls.filter { it.handledBy == "ai" }.map { it.id }.toSet())
                }
            }
            viewModelScope.launch {
                mgr.transcripts.collect { m -> _uiState.update { it.copy(liveTranscripts = m) } }
            }
        }
        viewModelScope.launch { callRepo.events.collect { ev -> _uiState.update { it.copy(events = ev) }; recomputeSummaries() } }
        viewModelScope.launch { callRepo.liveCalls.collect { recomputeSummaries() } }
        viewModelScope.launch { rsvpRepo.rsvpResults.collect { recomputeSummaries() } }
        viewModelScope.launch { campaignRepo.campaigns.collect { recomputeSummaries() } }
        loadIdentity()
    }

    private fun recomputeSummaries() {
        val st = _uiState.value
        val live = callEngineLive()
        val summaries = st.events.map { ev ->
            val camps = campaignRepo.campaigns.value.filter { it.eventId == ev.id }
            val rsvps = rsvpRepo.rsvpResults.value.filter { it.eventId == ev.id }
            EventSummary(
                event = ev,
                campaignState = when {
                    camps.any { it.state == CampaignState.ACTIVE } -> CampaignState.ACTIVE
                    camps.any { it.state == CampaignState.PAUSED } -> CampaignState.PAUSED
                    camps.isNotEmpty() -> CampaignState.COMPLETED
                    else -> null
                },
                targetsTotal = camps.sumOf { it.totalTargets },
                targetsDone = camps.sumOf { it.completedTargets },
                liveNow = live.count { it.eventId == ev.id },
                attending = rsvps.count { it.answer == RsvpAnswer.ATTENDING },
                declined = rsvps.count { it.answer == RsvpAnswer.DECLINED },
                maybe = rsvps.count { it.answer == RsvpAnswer.MAYBE },
                callback = rsvps.count { it.answer == RsvpAnswer.CALLBACK },
                totalGuests = rsvps.filter { it.answer == RsvpAnswer.ATTENDING }.sumOf { it.guestsCount }
            )
        }.sortedWith(compareByDescending<EventSummary> { it.campaignState == CampaignState.ACTIVE }
            .thenByDescending { it.event.date ?: "" })
        _uiState.update { it.copy(eventSummaries = summaries) }
    }

    private fun callEngineLive() = callRepo.liveCalls.value

    fun targetsFor(campaignId: String): List<CampaignTarget> =
        campaignRepo.getTargets(campaignId).value

    fun setEventFilter(eventId: String?) {
        _uiState.update { it.copy(selectedEventFilter = eventId) }
    }

    private fun loadIdentity() {
        val client = DependencyContainer.supabaseClient ?: return
        viewModelScope.launch {
            try {
                val user = client.auth.currentUserOrNull() ?: return@launch
                val row = client.postgrest["console_me"].select()
                    .decodeList<MeRow>().firstOrNull()
                val metaName = user.userMetadata?.get("full_name")?.toString()?.trim('"')
                val me = row?.let {
                    ConsoleMe(
                        displayName = it.display_name ?: metaName ?: user.email ?: "נציג",
                        platformRole = it.platform_role ?: "",
                        platformRank = it.platform_rank,
                        permissions = it.permissions.toSet()
                    )
                }
                _uiState.update {
                    it.copy(
                        me = me,
                        agentName = me?.displayName ?: metaName ?: user.email ?: "נציג",
                        agentEmail = user.email ?: "",
                        // Managers land on the events overview; agents on the ops dashboard
                        currentScreen = if (me?.canManageVoice == true && it.currentScreen == Screen.Dashboard)
                            Screen.Events else it.currentScreen
                    )
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun selectCall(call: Call, returnTo: Screen = Screen.History) {
        _uiState.update { it.copy(currentScreen = Screen.CallDetail(call, returnTo), selectedAnalysis = null, analysisLoading = true) }
        viewModelScope.launch {
            val analysis = callRepo.getCallAnalysis(call.id)
            _uiState.update { it.copy(selectedAnalysis = analysis, analysisLoading = false) }
        }
    }

    fun refreshAll() {
        callRepo.refresh(); campaignRepo.refresh(); rsvpRepo.refresh()
    }

    fun dismissError() = ErrorBus.clear()

    fun logout() {
        val client = DependencyContainer.supabaseClient ?: return
        viewModelScope.launch {
            try { client.auth.signOut() } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /** 05X-XXXXXXX / 05XXXXXXXX -> +9725XXXXXXXX ; returns null if invalid. */
    fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+972") && digits.length == 13 -> digits
            digits.startsWith("972") && digits.length == 12 -> "+$digits"
            digits.startsWith("05") && digits.length == 10 -> "+972" + digits.drop(1)
            digits.startsWith("0") && digits.length == 9 -> "+972" + digits.drop(1)
            else -> null
        }
    }

    fun whisperToAi(callId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            callEngine.sendAgentCommand(callId, "contextual_update", mapOf("text" to text.trim()))
        }
    }

    fun muteAiOnce(callId: String) {
        viewModelScope.launch { callEngine.sendAgentCommand(callId, "clear_buffer") }
    }

    fun closeAiAgent(callId: String) {
        viewModelScope.launch { callEngine.sendAgentCommand(callId, "close_agent") }
    }

    fun setScreen(screen: Screen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun setAgentStatus(status: AgentStatus) {
        agentPresence.setStatus(status)
    }

    fun monitorCall(callId: String) {
        viewModelScope.launch {
            callEngine.monitorCall(callId)
        }
    }

    fun takeoverCall(callId: String) {
        viewModelScope.launch {
            callEngine.takeoverCall(callId)
        }
    }

    fun makeOutboundCall(phone: String, name: String) {
        val normalized = normalizePhone(phone)
        if (normalized == null) {
            ErrorBus.post("מספר טלפון לא תקין: $phone")
            return
        }
        viewModelScope.launch {
            callEngine.startOutboundCall(normalized, name.ifBlank { "לקוח" })
        }
    }

    fun toggleCampaign(campaignId: String) {
        campaignRepo.toggleCampaign(campaignId)
    }

    // In call form inputs
    fun updateInCallNotes(notes: String) {
        _uiState.update { it.copy(inCallNotes = notes) }
    }

    fun updateInCallRsvpAnswer(answer: RsvpAnswer) {
        _uiState.update { it.copy(inCallRsvpAnswer = answer) }
    }

    fun updateInCallGuestsCount(count: Int) {
        _uiState.update { it.copy(inCallGuestsCount = count.coerceAtLeast(0)) }
    }

    fun toggleMute() {
        _uiState.value.currentSession?.let {
            it.mute(!_uiState.value.currentSessionMuted)
        }
    }

    fun toggleHold() {
        _uiState.value.currentSession?.let {
            it.hold(!_uiState.value.currentSessionHeld)
        }
    }

    fun sendDtmf(digit: String) {
        _uiState.value.currentSession?.sendDtmf(digit)
    }

    fun submitRsvpAndHangup() {
        val state = _uiState.value
        val session = state.currentSession
        if (session != null) {
            val result = RsvpResult(
                id = "rsvp-res-${UUID.randomUUID().toString().take(6)}",
                callId = session.id,
                guestId = "guest-${UUID.randomUUID().toString().take(4)}",
                guestName = session.customerName,
                answer = state.inCallRsvpAnswer,
                guestsCount = if (state.inCallRsvpAnswer == RsvpAnswer.ATTENDING) state.inCallGuestsCount else 0,
                notes = state.inCallNotes
            )
            rsvpRepo.saveRsvpResult(result)
            session.hangup()
        }
    }

    fun hangupDirectly() {
        _uiState.value.currentSession?.hangup()
    }
}
