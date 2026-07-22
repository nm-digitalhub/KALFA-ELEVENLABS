package me.kalfa.agentconsole.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.telephony.*
import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.error.AppResult
import me.kalfa.agentconsole.domain.error.RepositoryHealth
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import me.kalfa.agentconsole.ui.message.AppMessageCenter
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.MessageAction
import me.kalfa.agentconsole.ui.message.MessageSeverity
import me.kalfa.agentconsole.ui.message.UiMessage
import me.kalfa.agentconsole.ui.message.UiEffect
import me.kalfa.agentconsole.ui.message.toHebrewMessage
import me.kalfa.agentconsole.ui.state.LoadState


@Serializable
private data class MeRow(
    val user_id: String,
    val display_name: String? = null,
    val platform_role: String? = null,
    val platform_rank: Int = 0,
    val permissions: List<String> = emptyList(),
    val vox_username: String? = null
)

data class ConsoleUiState(
    val agentStatus: AgentStatus = AgentStatus.NOT_READY,
    val agentName: String = "נציג KALFA",
    val agentEmail: String = "",
    val me: ConsoleMe? = null,
    val events: List<ConsoleEvent> = emptyList(),
    val eventSummaries: List<EventSummary> = emptyList(),
    val selectedEventFilter: String? = null,
    val globalMessages: List<UiMessage> = emptyList(),
    val callHealth: RepositoryHealth = RepositoryHealth.Loading,
    val campaignHealth: RepositoryHealth = RepositoryHealth.Loading,
    val rsvpHealth: RepositoryHealth = RepositoryHealth.Loading,
    val analysisState: LoadState<CallAnalysis?> = LoadState.Initial,
    val guestCallFailures: Map<String, AppFailure> = emptyMap(),
    val guestDispatchStatuses: Map<String, CallDispatchStatus> = emptyMap(),
    val campaignFailures: Map<String, AppFailure> = emptyMap(),
    val liveTranscripts: Map<String, List<TranscriptLine>> = emptyMap(),
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
    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()
    private val dispatchGuests = mutableMapOf<String, String>()

    private fun observeRepositoryHealth(
        health: StateFlow<RepositoryHealth>,
        messageId: String,
        title: String,
        retryActionId: String
    ) {
        viewModelScope.launch {
            health.collect { status ->
                when (status) {
                    RepositoryHealth.Loading -> Unit
                    RepositoryHealth.Fresh -> AppMessageCenter.resolve(messageId)
                    is RepositoryHealth.Stale -> {
                        if (status.hasCachedData) {
                            AppMessageCenter.publish(
                                UiMessage(
                                    id = messageId,
                                    severity = MessageSeverity.WARNING,
                                    title = title,
                                    body = "מוצגים הנתונים האחרונים שנקלטו.",
                                    primaryAction = MessageAction("נסה שוב", retryActionId),
                                    dismissible = true,
                                    deduplicationKey = messageId
                                )
                            )
                        } else {
                            AppMessageCenter.resolve(messageId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleCallOperation(result: AppResult<Unit>) {
        if (result is AppResult.Failure) {
            _effects.emit(
                UiEffect.ShowSnackbar(
                    result.reason.toHebrewMessage(FailureContext.LIVE_CALL)
                )
            )
        }
    }

    init {
        viewModelScope.launch {
            callEngine.dispatchStatuses.collect { statuses ->
                val byGuest = statuses.mapNotNull { (dispatchId, status) ->
                    dispatchGuests[dispatchId]?.let { it to status }
                }.toMap()
                _uiState.update { it.copy(guestDispatchStatuses = byGuest) }
            }
        }
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

        viewModelScope.launch {
            AppMessageCenter.messages.collect { messages ->
                _uiState.update { it.copy(globalMessages = messages) }
            }
        }
        viewModelScope.launch {
            callRepo.health.collect { health ->
                _uiState.update { it.copy(callHealth = health) }
            }
        }
        viewModelScope.launch {
            campaignRepo.health.collect { health ->
                _uiState.update { it.copy(campaignHealth = health) }
            }
        }
        viewModelScope.launch {
            rsvpRepo.health.collect { health ->
                _uiState.update { it.copy(rsvpHealth = health) }
            }
        }
        observeRepositoryHealth(
            health = callRepo.health,
            messageId = "calls-stale",
            title = "נתוני השיחות אינם מעודכנים",
            retryActionId = "retry_calls"
        )
        observeRepositoryHealth(
            health = campaignRepo.health,
            messageId = "campaigns-stale",
            title = "נתוני הקמפיינים אינם מעודכנים",
            retryActionId = "retry_campaigns"
        )
        observeRepositoryHealth(
            health = rsvpRepo.health,
            messageId = "rsvp-stale",
            title = "תוצאות האישורים אינן מעודכנות",
            retryActionId = "retry_rsvp"
        )

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

    // The event's guests for the manual-dial list (console_event_guests). Returns a
    // StateFlow the detail screen collects, so the list fills in when the fetch lands.
    fun eventGuests(eventId: String): StateFlow<List<EventGuest>> =
        campaignRepo.getEventGuests(eventId)

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
                        permissions = it.permissions.toSet(),
                        voxUsername = it.vox_username
                    )
                }
                _uiState.update {
                    it.copy(
                        me = me,
                        agentName = me?.displayName ?: metaName ?: user.email ?: "נציג",
                        agentEmail = user.email ?: ""
                    )
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun selectCall(call: Call) {
        _uiState.update { it.copy(analysisState = LoadState.Loading) }
        viewModelScope.launch {
            when (val result = callRepo.getCallAnalysis(call.id)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(analysisState = LoadState.Content(result.value))
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        analysisState = LoadState.Failure(
                            failure = result.reason,
                            staleContentAvailable = false
                        )
                    )
                }
            }
        }
    }

    fun dismissMessage(messageId: String) = AppMessageCenter.dismiss(messageId)

    fun handleGlobalMessageAction(actionId: String) {
        when (actionId) {
            "retry_calls" -> callRepo.refresh()
            "retry_campaigns" -> campaignRepo.refresh()
            "retry_rsvp" -> rsvpRepo.refresh()
        }
    }

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
            handleCallOperation(
                callEngine.sendAgentCommand(callId, "contextual_update", mapOf("text" to text.trim()))
            )
        }
    }

    fun muteAiOnce(callId: String) {
        viewModelScope.launch { handleCallOperation(callEngine.sendAgentCommand(callId, "clear_buffer")) }
    }

    fun closeAiAgent(callId: String) {
        viewModelScope.launch { handleCallOperation(callEngine.sendAgentCommand(callId, "close_agent")) }
    }

    // Hang up the live call (guest conversation). Real, wired to POST
    // /api/calls/{id}/end. On success the row goes terminal and the live-calls list
    // drops it; failures are surfaced by the engine on the error banner.
    fun endCall(callId: String) {
        viewModelScope.launch { handleCallOperation(callEngine.endCall(callId)) }
    }

    fun setAgentStatus(status: AgentStatus) {
        agentPresence.setStatus(status)
    }

    // Live-listen (monitor), takeover, and app-initiated outbound dialing are NOT
    // wired end-to-end: each needs a real human-agent Voximplant SDK leg, a backend
    // route (monitor / takeover / outreach-call — none exist yet), and for
    // monitor/takeover the VoxEngine named-Conference redesign. Per the UI-honesty
    // rule we must never open a fake in-call session or show fake success — so these
    // surface an honest notice and do nothing else (the mock engine is never
    // invoked, so currentSession stays null and no fake in-call screen appears). The
    // matching buttons are also disabled + labelled "בקרוב". The AI-supervision
    // commands (whisper / clear / close) below ARE wired and stay live. Re-enable
    // each of these when its backend route + SDK path ships.
    private fun notifyNotWired(feature: String) {
        _effects.tryEmit(
            UiEffect.ShowSnackbar("$feature עדיין אינו זמין באפליקציה.")
        )
    }

    fun monitorCall(callId: String) = notifyNotWired("האזנה שקטה לשיחה")

    fun takeoverCall(callId: String) = notifyNotWired("השתלטות על שיחה")

    // Free-form dialing (arbitrary phone, no event/guest) does NOT fit the backend
    // enqueue route and stays gated. The real path is enqueueOutboundCall below —
    // per an EXISTING guest within an event.
    fun makeOutboundCall(phone: String, name: String) = notifyNotWired("חיוג יזום חופשי")

    // Real outbound: enqueue an AI call to an existing guest via the gated worker
    // (POST /api/events/{eventId}/outreach-call). On success the call surfaces in
    // the feed once the worker dials; failures are shown by the engine.
    fun enqueueOutboundCall(eventId: String, guestId: String) {
        viewModelScope.launch {
            when (val result = callEngine.enqueueOutboundCall(eventId, guestId)) {
                is AppResult.Success -> {
                    dispatchGuests[result.value.dispatchId] = guestId
                    val accepted = callEngine.dispatchStatuses.value[result.value.dispatchId]
                        ?: CallDispatchStatus(
                            dispatchId = result.value.dispatchId,
                            eventId = result.value.eventId,
                            status = "accepted",
                            reason = null,
                            callAttemptId = null,
                            updatedAt = null
                        )
                    _uiState.update {
                        it.copy(
                            guestCallFailures = it.guestCallFailures - guestId,
                            guestDispatchStatuses = it.guestDispatchStatuses + (guestId to accepted)
                        )
                    }
                    _effects.emit(
                        UiEffect.ShowSnackbar("הבקשה נקלטה.")
                    )
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(guestCallFailures = it.guestCallFailures + (guestId to result.reason))
                }
            }
        }
    }

    fun toggleCampaign(campaignId: String) {
        viewModelScope.launch {
            when (val result = campaignRepo.toggleCampaign(campaignId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(campaignFailures = it.campaignFailures - campaignId)
                    }
                    _effects.emit(UiEffect.ShowSnackbar("מצב הקמפיין עודכן."))
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(campaignFailures = it.campaignFailures + (campaignId to result.reason))
                }
            }
        }
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
