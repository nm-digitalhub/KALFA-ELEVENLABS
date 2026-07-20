package com.example.data.mock

import com.example.domain.model.*
import com.example.domain.repository.CallRepository
import com.example.domain.repository.CampaignRepository
import com.example.domain.repository.RsvpRepository
import com.example.domain.telephony.AgentPresence
import com.example.domain.telephony.CallEngine
import com.example.domain.telephony.CallSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class MockCallSession(
    override val id: String,
    override val customerPhone: String,
    override val customerName: String,
    initialState: CallState,
    private val onHangup: () -> Unit,
    private val onStateChange: (CallState) -> Unit
) : CallSession {
    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isHeld = MutableStateFlow(false)
    override val isHeld: StateFlow<Boolean> = _isHeld.asStateFlow()

    private val _durationSec = MutableStateFlow(0)
    override val durationSec: StateFlow<Int> = _durationSec.asStateFlow()

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        startTimer()
    }

    private fun startTimer() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                if (_state.value == CallState.ACTIVE || _state.value == CallState.MONITORED || _state.value == CallState.TAKEN_OVER) {
                    _durationSec.value += 1
                }
                delay(1000)
            }
        }
    }

    fun updateState(newState: CallState) {
        _state.value = newState
        onStateChange(newState)
    }

    override fun mute(muted: Boolean) {
        _isMuted.value = muted
    }

    override fun hold(held: Boolean) {
        _isHeld.value = held
    }

    override fun sendDtmf(digit: String) {
        // Mock DTMF tone simulation
    }

    override fun hangup() {
        job?.cancel()
        _state.value = CallState.DISCONNECTED
        onStateChange(CallState.DISCONNECTED)
        onHangup()
    }
}

class MockCallRepositoryImpl : CallRepository {
    private val _liveCalls = MutableStateFlow<List<Call>>(emptyList())
    override val liveCalls: StateFlow<List<Call>> = _liveCalls.asStateFlow()

    private val _callHistory = MutableStateFlow<List<Call>>(emptyList())
    override val callHistory: StateFlow<List<Call>> = _callHistory.asStateFlow()

    init {
        prepopulateData()
    }

    private fun prepopulateData() {
        val initialLive = listOf(
            Call(
                id = "live-1",
                direction = "outbound",
                kind = CallKind.AI_RSVP,
                voxSessionId = "vox-sess-1",
                customerPhone = "052-345-6789",
                customerName = "יוסי כהן",
                eventName = "חתונה של דני ורוני",
                handledBy = "ai",
                agentId = null,
                state = CallState.ACTIVE,
                startedAt = "17:55:00",
                answeredAt = "17:55:10",
                endedAt = null,
                durationSec = 42,
                recordingUrl = null,
                transcript = listOf(
                    TranscriptTurn("ai", "שלום, הגעתי ליוסי כהן בנוגע לחתונה של דני ורוני?", "17:55:12"),
                    TranscriptTurn("customer", "כן, זה יוסי. שלום.", "17:55:16"),
                    TranscriptTurn("ai", "מעולה! אנחנו עושים אישורי הגעה. רציתי לשאול אם אתה מתכנן להגיע לאירוע ב-25 לחודש?", "17:55:20"),
                    TranscriptTurn("customer", "כן, אנחנו נגיע בהחלט.", "17:55:25"),
                    TranscriptTurn("ai", "איזה יופי! כמה אנשים תהיו כולל אתה?", "17:55:28")
                )
            ),
            Call(
                id = "live-2",
                direction = "outbound",
                kind = CallKind.AI_RSVP,
                voxSessionId = "vox-sess-2",
                customerPhone = "054-987-6543",
                customerName = "מיכל לוי",
                eventName = "בר מצווה של איתי",
                handledBy = "ai",
                agentId = null,
                state = CallState.ACTIVE,
                startedAt = "17:56:30",
                answeredAt = "17:56:40",
                endedAt = null,
                durationSec = 15,
                recordingUrl = null,
                transcript = listOf(
                    TranscriptTurn("ai", "שלום, מדברת איה העוזרת הווירטואלית של משפחת לוי. אני מתקשרת לאשר הגעה לבר המצווה של איתי.", "17:56:42"),
                    TranscriptTurn("customer", "היי, כן. מיכל פה.", "17:56:47")
                )
            ),
            Call(
                id = "live-3",
                direction = "outbound",
                kind = CallKind.AI_RSVP,
                voxSessionId = "vox-sess-3",
                customerPhone = "050-111-2222",
                customerName = "אבי אברהם",
                eventName = "חינה של ספיר ועומר",
                handledBy = "ai",
                agentId = null,
                state = CallState.RINGING,
                startedAt = "17:57:10",
                answeredAt = null,
                endedAt = null,
                durationSec = 0,
                recordingUrl = null,
                transcript = emptyList()
            )
        )

        val initialHistory = listOf(
            Call(
                id = "hist-1",
                direction = "outbound",
                kind = CallKind.AI_RSVP,
                voxSessionId = "vox-sess-hist1",
                customerPhone = "052-123-4567",
                customerName = "שירה מזרחי",
                eventName = "חתונה של דני ורוני",
                handledBy = "ai",
                agentId = null,
                state = CallState.DISCONNECTED,
                startedAt = "15:30:00",
                answeredAt = "15:30:15",
                endedAt = "15:31:45",
                durationSec = 90,
                recordingUrl = "https://example.com/record1.mp3",
                transcript = listOf(
                    TranscriptTurn("ai", "שלום, הגעתי לשירה מזרחי?", "15:30:17"),
                    TranscriptTurn("customer", "כן, זאת שירה.", "15:30:20"),
                    TranscriptTurn("ai", "נהדר, אני מתקשרת לאשר הגעה לחתונה של דני ורוני ב-25 לחודש. מגיעים?", "15:30:25"),
                    TranscriptTurn("customer", "לצערי אנחנו לא נוכל להגיע, יש לנו אירוע אחר באותו יום.", "15:30:35"),
                    TranscriptTurn("ai", "מבינה לגמרי. תודה רבה שחזרת אליי והמשך יום מקסים!", "15:30:42")
                )
            ),
            Call(
                id = "hist-2",
                direction = "outbound",
                kind = CallKind.AI_RSVP,
                voxSessionId = "vox-sess-hist2",
                customerPhone = "054-444-5555",
                customerName = "רוני דניאל",
                eventName = "בר מצווה של איתי",
                handledBy = "agent",
                agentId = "agent-123",
                state = CallState.DISCONNECTED,
                startedAt = "14:12:00",
                answeredAt = "14:12:08",
                endedAt = "14:13:30",
                durationSec = 82,
                recordingUrl = "https://example.com/record2.mp3",
                transcript = listOf(
                    TranscriptTurn("agent", "שלום רוני, מדבר עידן ממרכז השירות של kalfa. רציתי לאשר הגעה לבר המצווה של איתי לוי.", "14:12:12"),
                    TranscriptTurn("customer", "היי עידן, כן, אנחנו נגיע! שני מבוגרים וילד אחד.", "14:12:25"),
                    TranscriptTurn("agent", "מצוין, רשמתי 3 מגיעים סך הכל. שיהיה המון מזל טוב ונתראה באירוע!", "14:12:35")
                )
            ),
            Call(
                id = "hist-3",
                direction = "inbound",
                kind = CallKind.INBOUND,
                voxSessionId = "vox-sess-hist3",
                customerPhone = "050-777-8888",
                customerName = "נועה פרץ",
                eventName = "חינה של ספיר ועומר",
                handledBy = "agent",
                agentId = "agent-123",
                state = CallState.DISCONNECTED,
                startedAt = "11:05:00",
                answeredAt = "11:05:12",
                endedAt = "11:07:30",
                durationSec = 138,
                recordingUrl = null,
                transcript = emptyList()
            )
        )

        _liveCalls.value = initialLive
        _callHistory.value = initialHistory
    }

    override fun updateCall(call: Call) {
        _liveCalls.update { list ->
            list.map { if (it.id == call.id) call else it }
        }
        _callHistory.update { list ->
            list.map { if (it.id == call.id) call else it }
        }
    }

    override fun addCall(call: Call) {
        if (call.state == CallState.DISCONNECTED) {
            _callHistory.update { listOf(call) + it }
        } else {
            _liveCalls.update { listOf(call) + it }
        }
    }

    fun removeLiveCall(callId: String) {
        _liveCalls.update { list -> list.filter { it.id != callId } }
    }
}

class MockCampaignRepositoryImpl : CampaignRepository {
    private val _campaigns = MutableStateFlow<List<Campaign>>(emptyList())
    override val campaigns: StateFlow<List<Campaign>> = _campaigns.asStateFlow()

    private val targetsMap = mutableMapOf<String, MutableStateFlow<List<CampaignTarget>>>()

    init {
        prepopulateData()
    }

    private fun prepopulateData() {
        val camp1 = Campaign(
            id = "camp-1",
            name = "קמפיין אישורי הגעה - חתונה דני ורוני",
            eventId = "event-1",
            eventName = "חתונה של דני ורוני",
            state = CampaignState.ACTIVE,
            totalTargets = 120,
            completedTargets = 84
        )
        val camp2 = Campaign(
            id = "camp-2",
            name = "קמפיין אישורי הגעה - בר מצווה איתי",
            eventId = "event-2",
            eventName = "בר מצווה של איתי",
            state = CampaignState.PAUSED,
            totalTargets = 80,
            completedTargets = 30
        )
        val camp3 = Campaign(
            id = "camp-3",
            name = "קמפיין אישורי הגעה - חינה ספיר ועומר",
            eventId = "event-3",
            eventName = "חינה של ספיר ועומר",
            state = CampaignState.COMPLETED,
            totalTargets = 50,
            completedTargets = 50
        )

        _campaigns.value = listOf(camp1, camp2, camp3)

        val targets1 = listOf(
            CampaignTarget("t1-1", "camp-1", "g1", "יובל אשכנזי", "052-555-0101", 1, null, null),
            CampaignTarget("t1-2", "camp-1", "g2", "גילעד שרון", "054-555-0102", 2, "לא ענה", null),
            CampaignTarget("t1-3", "camp-1", "g3", "דניאל קליין", "050-555-0103", 0, null, null),
            CampaignTarget("t1-4", "camp-1", "g4", "אורית ברק", "053-555-0104", 1, "חזר אליי", null)
        )
        val targets2 = listOf(
            CampaignTarget("t2-1", "camp-2", "g5", "גיא גולן", "052-555-0201", 1, "מגיע", null),
            CampaignTarget("t2-2", "camp-2", "g6", "טל שלום", "054-555-0202", 0, null, null)
        )

        targetsMap["camp-1"] = MutableStateFlow(targets1)
        targetsMap["camp-2"] = MutableStateFlow(targets2)
        targetsMap["camp-3"] = MutableStateFlow(emptyList())
    }

    override fun getTargets(campaignId: String): StateFlow<List<CampaignTarget>> {
        return targetsMap.getOrPut(campaignId) { MutableStateFlow(emptyList()) }.asStateFlow()
    }

    override fun toggleCampaign(campaignId: String) {
        _campaigns.update { list ->
            list.map { camp ->
                if (camp.id == campaignId) {
                    val nextState = when (camp.state) {
                        CampaignState.ACTIVE -> CampaignState.PAUSED
                        CampaignState.PAUSED -> CampaignState.ACTIVE
                        CampaignState.COMPLETED -> CampaignState.COMPLETED
                    }
                    camp.copy(state = nextState)
                } else camp
            }
        }
    }

    override fun updateTargetResult(targetId: String, result: String) {
        targetsMap.values.forEach { flow ->
            flow.update { list ->
                list.map { target ->
                    if (target.id == targetId) {
                        target.copy(lastResult = result, attempts = target.attempts + 1)
                    } else target
                }
            }
        }
    }
}

class MockRsvpRepositoryImpl : RsvpRepository {
    private val _rsvpResults = MutableStateFlow<List<RsvpResult>>(emptyList())
    override val rsvpResults: StateFlow<List<RsvpResult>> = _rsvpResults.asStateFlow()

    init {
        prepopulateData()
    }

    private fun prepopulateData() {
        val initial = listOf(
            RsvpResult("rsvp-1", "hist-1", "g100", "שירה מזרחי", RsvpAnswer.DECLINED, 0, "אירוע משפחתי אחר"),
            RsvpResult("rsvp-2", "hist-2", "g101", "רוני דניאל", RsvpAnswer.ATTENDING, 3, "שני מבוגרים וילד אחד, צמחוני אחד"),
            RsvpResult("rsvp-3", "hist-3", "g102", "נועה פרץ", RsvpAnswer.MAYBE, 2, "תחזיר תשובה סופית ביומיים הקרובים")
        )
        _rsvpResults.value = initial
    }

    override fun saveRsvpResult(result: RsvpResult) {
        _rsvpResults.update { listOf(result) + it }
    }
}

class MockCallEngineImpl(
    private val callRepository: CallRepository,
    private val rsvpRepository: RsvpRepository
) : CallEngine, AgentPresence {

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
        // Run a simulation loop to change active AI calls or queue depth slightly over time
        scope.launch {
            while (isActive) {
                delay(12000)
                // Random variation for realism
                _activeAiCallsCount.update { (it + (-1..1).random()).coerceIn(1, 8) }
                _queueDepth.update { (it + (-1..1).random()).coerceIn(0, 5) }
            }
        }
    }

    override fun startOutboundCall(phone: String, customerName: String): CallSession {
        // End current if exists
        _currentSession.value?.hangup()

        val callId = "out-${UUID.randomUUID().toString().take(6)}"
        val newCall = Call(
            id = callId,
            direction = "outbound",
            kind = CallKind.OUTBOUND,
            voxSessionId = "vox-${UUID.randomUUID().toString().take(8)}",
            customerPhone = phone,
            customerName = customerName,
            eventName = "קמפיין יוצא",
            handledBy = "agent",
            agentId = "agent-current",
            state = CallState.RINGING,
            startedAt = "עכשיו",
            answeredAt = null,
            endedAt = null,
            durationSec = 0,
            recordingUrl = null
        )

        callRepository.addCall(newCall)
        _currentStatus.value = AgentStatus.IN_CALL

        val session = MockCallSession(
            id = callId,
            customerPhone = phone,
            customerName = customerName,
            initialState = CallState.RINGING,
            onHangup = {
                _currentSession.value = null
                _currentStatus.value = AgentStatus.READY
                val updatedCall = newCall.copy(
                    state = CallState.DISCONNECTED,
                    endedAt = "עכשיו",
                    durationSec = 20 // Simulated duration
                )
                callRepository.updateCall(updatedCall)
            },
            onStateChange = { s ->
                val answeredTime = if (s == CallState.ACTIVE) "עכשיו" else null
                val updatedCall = newCall.copy(state = s, answeredAt = answeredTime)
                callRepository.updateCall(updatedCall)
            }
        )

        _currentSession.value = session

        // Simulate customer answering after 3 seconds
        scope.launch {
            delay(3000)
            if (session.state.value == CallState.RINGING) {
                session.updateState(CallState.ACTIVE)
            }
        }

        return session
    }

    override fun monitorCall(callId: String): CallSession {
        _currentSession.value?.hangup()

        val liveCallsList = callRepository.liveCalls.value
        val targetCall = liveCallsList.find { it.id == callId } ?: Call(
            id = callId,
            direction = "outbound",
            kind = CallKind.AI_RSVP,
            voxSessionId = "vox-monitor",
            customerPhone = "050-000-0000",
            customerName = "אורח זמני",
            eventName = "אירוע כללי",
            handledBy = "ai",
            agentId = null,
            state = CallState.ACTIVE,
            startedAt = "לפני דקה",
            answeredAt = "לפני דקה",
            endedAt = null,
            durationSec = 30,
            recordingUrl = null
        )

        val updatedCall = targetCall.copy(state = CallState.MONITORED)
        callRepository.updateCall(updatedCall)
        _currentStatus.value = AgentStatus.READY // Still ready to receive other things or monitoring

        val session = MockCallSession(
            id = callId,
            customerPhone = targetCall.customerPhone,
            customerName = targetCall.customerName,
            initialState = CallState.MONITORED,
            onHangup = {
                _currentSession.value = null
                val originalCall = targetCall.copy(state = CallState.ACTIVE)
                callRepository.updateCall(originalCall)
            },
            onStateChange = { s ->
                if (s == CallState.DISCONNECTED) {
                    _currentSession.value = null
                    val discCall = targetCall.copy(state = CallState.DISCONNECTED, endedAt = "עכשיו")
                    callRepository.updateCall(discCall)
                    if (callRepository is MockCallRepositoryImpl) {
                        callRepository.removeLiveCall(callId)
                    }
                }
            }
        )

        _currentSession.value = session
        return session
    }

    override fun takeoverCall(callId: String): CallSession {
        // If we are currently monitoring, we takeover that same session, or we can takeover a fresh session
        val current = _currentSession.value
        if (current != null && current.id == callId) {
            val mockSess = current as MockCallSession
            mockSess.updateState(CallState.TAKEN_OVER)
            _currentStatus.value = AgentStatus.IN_CALL

            val liveCallsList = callRepository.liveCalls.value
            val targetCall = liveCallsList.find { it.id == callId }
            if (targetCall != null) {
                val updatedCall = targetCall.copy(
                    state = CallState.TAKEN_OVER,
                    handledBy = "agent",
                    agentId = "agent-current",
                    transcript = targetCall.transcript + TranscriptTurn("ai", "[מערכת: הסוכן השתלט על השיחה]", "עכשיו")
                )
                callRepository.updateCall(updatedCall)
            }
            return current
        }

        _currentSession.value?.hangup()

        val liveCallsList = callRepository.liveCalls.value
        val targetCall = liveCallsList.find { it.id == callId } ?: Call(
            id = callId,
            direction = "outbound",
            kind = CallKind.AI_RSVP,
            voxSessionId = "vox-takeover",
            customerPhone = "050-000-0000",
            customerName = "אורח זמני",
            eventName = "אירוע כללי",
            handledBy = "ai",
            agentId = null,
            state = CallState.ACTIVE,
            startedAt = "לפני דקה",
            answeredAt = "לפני דקה",
            endedAt = null,
            durationSec = 30,
            recordingUrl = null
        )

        val updatedCall = targetCall.copy(
            state = CallState.TAKEN_OVER,
            handledBy = "agent",
            agentId = "agent-current"
        )
        callRepository.updateCall(updatedCall)
        _currentStatus.value = AgentStatus.IN_CALL

        val session = MockCallSession(
            id = callId,
            customerPhone = targetCall.customerPhone,
            customerName = targetCall.customerName,
            initialState = CallState.TAKEN_OVER,
            onHangup = {
                _currentSession.value = null
                _currentStatus.value = AgentStatus.READY
                val discCall = updatedCall.copy(state = CallState.DISCONNECTED, endedAt = "עכשיו")
                callRepository.updateCall(discCall)
                if (callRepository is MockCallRepositoryImpl) {
                    callRepository.removeLiveCall(callId)
                }
            },
            onStateChange = { s ->
                if (s == CallState.DISCONNECTED) {
                    _currentSession.value = null
                    _currentStatus.value = AgentStatus.READY
                    val discCall = updatedCall.copy(state = CallState.DISCONNECTED, endedAt = "עכשיו")
                    callRepository.updateCall(discCall)
                    if (callRepository is MockCallRepositoryImpl) {
                        callRepository.removeLiveCall(callId)
                    }
                }
            }
        )

        _currentSession.value = session
        return session
    }

    override fun setStatus(status: AgentStatus) {
        if (_currentStatus.value != AgentStatus.IN_CALL) {
            _currentStatus.value = status
        }
    }
}
