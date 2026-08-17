package me.kalfa.agentconsole.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import me.kalfa.agentconsole.telemetry.Telemetry
import me.kalfa.agentconsole.telemetry.TelemetryEvents
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.telephony.vox.IncomingCallNotificationBuilder
import me.kalfa.agentconsole.telephony.vox.RingCapabilityChecker
import me.kalfa.agentconsole.telephony.presence.PresenceActions
import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.telephony.*
import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.error.AppResult
import me.kalfa.agentconsole.domain.error.RepositoryHealth
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import me.kalfa.agentconsole.ui.message.AppMessageCenter
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.MessageAction
import me.kalfa.agentconsole.ui.message.MessageSeverity
import me.kalfa.agentconsole.ui.message.UiMessage
import me.kalfa.agentconsole.domain.telephony.TransferTarget
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
    // Readback of AgentPresence.shiftActive — drives MainActivity's
    // PresenceForegroundService start/stop LaunchedEffect (see
    // docs/android-presence-and-call-ux.md §1). NOT the same thing as agentStatus:
    // shift persists through NOT_READY/DND breaks and only clears on logout.
    val shiftActive: Boolean = false,
    val currentSessionState: CallState = CallState.DISCONNECTED,
    val currentSessionMuted: Boolean = false,
    val currentSessionHeld: Boolean = false,
    val currentSessionDuration: Int = 0,
    // Media path to the cloud is down on a leg that is otherwise still up — see
    // CallSession.isReconnecting. Drives ActiveCallScreen's banner; without it a
    // mid-call audio drop is invisible and the duration keeps reassuring.
    val currentSessionReconnecting: Boolean = false,
    // ── Live-call handoff ────────────────────────────────────────────────────
    // Colleagues this call can be handed to. Empty is a REAL answer ("nobody is
    // available"), which is why loading and failure are tracked separately —
    // rendering all three the same way would let a failed request read as an
    // empty call floor.
    val transferTargets: List<TransferTarget> = emptyList(),
    val transferTargetsLoading: Boolean = false,
    val transferTargetsFailed: Boolean = false,
    /**
     * A consult has been REQUESTED for this call and not yet ended by this agent.
     *
     * Deliberately not called `consultActive`: the device is told the request was
     * delivered to the live session, never that the target answered. The scenario
     * owns that fact and reports it server-side (consult_connected /
     * consult_failed), where this app has no subscription. So this gates the
     * cancel/complete controls — which the scenario safely ignores when no consult
     * is in progress — and nothing on screen claims the colleague is on the line.
     */
    val consultRequested: Boolean = false,
    /**
     * A conference participant has been REQUESTED for this call and not yet removed.
     *
     * Same "requested, not confirmed" honesty as [consultRequested]: the device is
     * told the command reached the live session, never that the third party
     * answered. It gates the remove control, which the scenario safely ignores when
     * there is no conference in flight.
     */
    val conferenceRequested: Boolean = false,
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
                callEngine.currentSession,
                agentPresence.shiftActive
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
                val shift = array[8] as Boolean

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
                        currentSession = session,
                        shiftActive = shift,
                        // Per-CALL state, reset the moment the call it described is
                        // no longer the current one. Without this, consultRequested
                        // set on one call survived into the NEXT inbound call —
                        // ActiveCallScreen would open showing "בטל התייעצות" /
                        // "השלם העברה" for a consult that does not exist, and
                        // tapping one POSTs to the new call's id, gets ignored by
                        // the scenario, and still reports "נשלחה". The transfer
                        // list is cleared for the same reason, though that one is
                        // cosmetic since the picker reloads on every open.
                        consultRequested = if (session === state.currentSession) state.consultRequested else false,
                        conferenceRequested = if (session === state.currentSession) state.conferenceRequested else false,
                        transferTargets = if (session === state.currentSession) state.transferTargets else emptyList(),
                        transferTargetsFailed = if (session === state.currentSession) state.transferTargetsFailed else false,
                    )
                }
            }.collect()
        }

        // Collect from active session if present
        viewModelScope.launch {
            _uiState.map { it.currentSession }.distinctUntilChanged().collectLatest { session ->
                if (session != null) {
                    combine(
                        session.state,
                        session.isMuted,
                        session.isHeld,
                        session.durationSec,
                        session.isReconnecting
                    ) { state, muted, held, duration, reconnecting ->
                        _uiState.update { ui ->
                            ui.copy(
                                currentSessionState = state,
                                currentSessionMuted = muted,
                                currentSessionHeld = held,
                                currentSessionDuration = duration,
                                currentSessionReconnecting = reconnecting
                            )
                        }
                    }.collect()
                } else {
                    _uiState.update { ui ->
                        ui.copy(
                            currentSessionState = CallState.DISCONNECTED,
                            currentSessionMuted = false,
                            currentSessionHeld = false,
                            currentSessionDuration = 0,
                            currentSessionReconnecting = false
                        )
                    }
                }
            }
        }

        // A refused hold() reported honestly — see CallSession.holdRefused's kdoc.
        // Deliberately its OWN collector rather than folded into the combine() above:
        // that one re-fires every second off durationSec, and holdRefused would still
        // read true on every one of those ticks until the agent's next attempt reset
        // it — this collector only reacts to the true EDGE (session.holdRefused
        // itself is what resets to false before each new attempt), so the snackbar
        // fires once per refusal, not once per second.
        viewModelScope.launch {
            _uiState.map { it.currentSession }.distinctUntilChanged().collectLatest { session ->
                session?.holdRefused?.collect { refused ->
                    if (refused) {
                        _effects.emit(UiEffect.ShowSnackbar("החזקת השיחה נכשלה. נסו שוב."))
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
                // Hand the device's Voximplant identity to durable storage the moment
                // the server tells us what it is. This is the ONLY place the app learns
                // it from a source that does not already require a Voximplant login:
                // VoxTokenStore.save is called only AFTER a successful login step, so
                // PresenceForegroundService.currentVoxUsername had nothing to read until
                // a login had happened, and PresenceActions.applyStatus's
                // `voxUsername != null` guard therefore skipped login, registration and
                // reporting on every service-driven path — the deadlock that kept this
                // device out of the push audience entirely. See VoxTokenStore.saveUsername.
                me?.voxUsername?.let { username ->
                    DependencyContainer.voxTokenStore?.saveUsername(username)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // NOT printStackTrace(). This read is where the app learns which
                // Voximplant identity the device is, and it failing silently is what
                // produced the live symptom on 2026-08-17: an app open for four minutes
                // reported `presence.status_set s=ready` then `s=not_ready_auto` with no
                // telephony event between them, because `me` was null and nothing
                // anywhere said why. A stack trace in logcat is not a diagnostic on a
                // phone with no ADB — the whole device-telemetry channel exists because
                // of that.
                //
                // Reported, not surfaced as a banner: PresenceActions now falls back to
                // the persisted username, so a failure here is usually survivable and a
                // second red message would describe a problem the agent already sees
                // named more usefully ("לא ניתן לעבור לזמין"). The telemetry line is for
                // whoever reads the log; the consequence, if there is one, is announced
                // by the code that actually hits it.
                Telemetry.emit(
                    TelemetryEvents.IDENTITY_LOAD_FAIL,
                    "err" to "${e.javaClass.simpleName}: ${e.message?.take(120) ?: "no message"}",
                )
            }
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
            // The ring-capability banners used to describe where to go instead of
            // taking the agent there ("ניתן לתקן דרך התראת הנוכחות הקבועה"). Neither
            // capability has a runtime dialog that could ever appear — both are
            // Settings-only toggles — so a banner without a button leaves the agent
            // to find a screen they have no reason to know the name of.
            //
            // appContext rather than an Activity: this handler is reached from a
            // banner that can be published by PresenceActions running under the
            // foreground service, with no Activity guaranteed alive. Both intents
            // already carry FLAG_ACTIVITY_NEW_TASK for exactly that reason.
            PresenceActions.ACTION_OPEN_NOTIFICATION_SETTINGS ->
                DependencyContainer.appContext?.let { ctx ->
                    runCatching { ctx.startActivity(RingCapabilityChecker.appNotificationSettingsIntent(ctx)) }
                }
            // Null below API 34, where there is nothing to grant — then this is
            // correctly a no-op rather than a crash, and the banner that offered it
            // cannot appear on such a device anyway (fullScreenIntentAllowed is
            // hard-coded true there).
            PresenceActions.ACTION_OPEN_FULL_SCREEN_INTENT_SETTINGS ->
                DependencyContainer.appContext?.let { ctx ->
                    RingCapabilityChecker.fullScreenIntentSettingsIntent(ctx)?.let { intent ->
                        runCatching { ctx.startActivity(intent) }
                    }
                }
            // The channel's own screen, not the app's. Channel behaviour is immutable
            // after creation, so when vibration is off for incoming calls this is the
            // only place on the device that can turn it back on.
            PresenceActions.ACTION_OPEN_CALL_CHANNEL_SETTINGS ->
                DependencyContainer.appContext?.let { ctx ->
                    runCatching {
                        ctx.startActivity(IncomingCallNotificationBuilder.channelSettingsIntent(ctx))
                    }
                }
        }
    }

    fun logout() {
        val client = DependencyContainer.supabaseClient ?: return
        viewModelScope.launch {
            // Withdraw shift + the Voximplant session together: a signed-out device
            // must not remain in the push-wake audience (route-inbound-retry) or stay
            // silently loggable-in to Voximplant via a leftover persisted token.
            // BEFORE signOut() — after it, getJwt() returns "" and this write would
            // just fail with NotSignedIn instead of actually reaching the server.
            // Best-effort: an intentional logout proceeds regardless of the result
            // (the 90s freshness gate self-heals once the heartbeat also stops).
            agentPresence.setShiftActive(false)
            DependencyContainer.voxClientManager?.let { vcm ->
                runCatching { vcm.unregisterCurrentPushToken() }
                runCatching { vcm.forgetPersistedSession() }
                vcm.logout()
            }
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

    // Going READY is the one human-initiated "I'm working now" signal this app has
    // today, so it is also the trigger for the push-wake-up chain (AGENTS.md "Push
    // wake-up"): declare on-shift (POST /api/agents/shift — without this the agent
    // is never in route-inbound-retry's audience and no push is ever sent, however
    // well the rest is wired), then log in to Voximplant (interactive one-time-key
    // on first use; silent on every call after, via VoxClientManager's persisted
    // tokens) and register the current FCM token. DND/NOT_READY do NOT withdraw
    // shift — a short break mid-shift should not drop push-wake coverage for the
    // rest of the day; only an explicit logout does (see logout() below).
    // ensureLoggedIn is idempotent/cheap once already logged in, so repeated taps
    // of "Ready" cost nothing extra (MAU discipline — AGENTS.md §1).
    //
    // The actual work is in PresenceActions.applyStatus — shared with
    // PresenceActionReceiver (the presence notification's shade/lock-screen actions),
    // which has no ViewModel to call this on (docs/android-presence-and-call-ux.md
    // §1). This method's only remaining job is supplying the voxUsername this
    // ViewModel already has from ConsoleUiState.me.
    fun setAgentStatus(status: AgentStatus) {
        val voxUsername = _uiState.value.me?.voxUsername
        viewModelScope.launch {
            me.kalfa.agentconsole.telephony.presence.PresenceActions.applyStatus(status, voxUsername)
        }
    }

    // Live-listen (monitor) and takeover are NOT wired end-to-end: each needs a real
    // human-agent Voximplant SDK leg plus, server-side, the VoxEngine
    // named-Conference redesign behind app_settings.monitor_enabled (see AGENTS.md
    // "Known state" #2). Per the UI-honesty rule we must never open a fake in-call
    // session or show fake success — so these surface an honest notice and do
    // nothing else (the mock engine is never invoked, so currentSession stays null
    // and no fake in-call screen appears). The matching buttons are also disabled +
    // labelled "בקרוב". The AI-supervision commands (whisper / clear / close) below
    // ARE wired and stay live. Re-enable each of these when its backend route + SDK
    // path ships. (Outbound dialing used to be a third `notifyNotWired` caller here;
    // see AGENTS.md's "Outbound dialing" section for why it was removed rather than
    // left disabled.)
    private fun notifyNotWired(feature: String) {
        _effects.tryEmit(
            UiEffect.ShowSnackbar("$feature עדיין אינו זמין באפליקציה.")
        )
    }

    fun monitorCall(callId: String) = notifyNotWired("האזנה שקטה לשיחה")

    fun takeoverCall(callId: String) = notifyNotWired("השתלטות על שיחה")

    // There is deliberately no free-form "dial any number" entry point here.
    // POST /api/console-calls/dial-intent — the only backend route that could ever
    // back one — accepts nothing but a server-verified {kind:'callback', id} or
    // {kind:'guest_service', eventId, contactId} target; its own schema comment
    // states a raw phone number has "NO representation here on purpose" (the
    // consent/DNC/quiet-hours gate has nothing to resolve a client-typed number
    // against). See AGENTS.md's "Outbound dialing" section. The real path for an
    // existing guest is enqueueOutboundCall below.

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

    fun hangupDirectly() {
        _uiState.value.currentSession?.hangup()
    }

    // ── Live-call handoff: transfer / consult / conference ────────────────────
    //
    // Every one of these goes through the SERVER, not the SDK leg, because the
    // topology change happens in the VoxEngine scenario — see CallEngine's kdoc.
    //
    // Two rules hold across all of them, and both are about not lying to the agent:
    //
    // 1. A success here means the request reached the live session. It does NOT
    //    mean the transfer happened, the colleague answered, or the conference
    //    formed — the scenario decides that and reports it server-side, where this
    //    app is not listening. Every message below is phrased as a request sent.
    // 2. A missing consoleCallId is reported, never silently swallowed. It means
    //    the call arrived from a scenario that predates the header carrying it, and
    //    an agent pressing a button that does nothing with no explanation is the
    //    exact failure mode CallSession.holdRefused exists to prevent.

    fun loadTransferTargets() {
        viewModelScope.launch {
            _uiState.update { it.copy(transferTargetsLoading = true, transferTargetsFailed = false) }
            when (val result = callEngine.loadTransferTargets()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        transferTargets = result.value,
                        transferTargetsLoading = false,
                        transferTargetsFailed = false,
                    )
                }
                is AppResult.Failure -> _uiState.update {
                    // The list is CLEARED on failure rather than left stale: a name
                    // from a minute ago may already be on another call, and offering
                    // it would produce an error the agent cannot explain.
                    it.copy(
                        transferTargets = emptyList(),
                        transferTargetsLoading = false,
                        transferTargetsFailed = true,
                    )
                }
            }
        }
    }

    fun transferTo(agentId: String) = runCallAction("העברת השיחה") { id ->
        callEngine.transferCall(id, agentId)
    }

    fun consultWith(agentId: String) = runCallAction("בקשת ההתייעצות", onSuccess = {
        _uiState.update { it.copy(consultRequested = true) }
    }) { id -> callEngine.startConsult(id, agentId) }

    fun conferenceWith(agentId: String) = runCallAction("צירוף הנציג לשיחה", onSuccess = {
        _uiState.update { it.copy(conferenceRequested = true) }
    }) { id -> callEngine.addToConference(id, agentId) }

    fun removeFromConference() = runCallAction("הסרת המשתתף", onSuccess = {
        _uiState.update { it.copy(conferenceRequested = false) }
    }) { id -> callEngine.removeFromConference(id) }

    // The phone variants. The number is passed through UNVALIDATED on purpose —
    // the server owns that policy (E.164, an Israel-only country allowlist, a
    // per-agent rate limit, DNC), and a second copy of it here would either drift
    // from the real one or teach an agent a shape the server rejects for a
    // different reason. Blank is the one thing worth catching locally, because it
    // is a mis-tap rather than a policy question.
    fun consultWithPhone(phone: String) = runCallAction("בקשת ההתייעצות", onSuccess = {
        _uiState.update { it.copy(consultRequested = true) }
    }) { id -> callEngine.startConsultWithPhone(id, phone.trim()) }

    fun conferenceWithPhone(phone: String) = runCallAction("צירוף המספר לשיחה", onSuccess = {
        _uiState.update { it.copy(conferenceRequested = true) }
    }) { id -> callEngine.addToConferenceWithPhone(id, phone.trim()) }

    fun cancelConsult() = runCallAction("ביטול ההתייעצות", onSuccess = {
        _uiState.update { it.copy(consultRequested = false) }
    }) { id -> callEngine.cancelConsult(id) }

    fun completeConsult() = runCallAction("השלמת ההעברה", onSuccess = {
        _uiState.update { it.copy(consultRequested = false) }
    }) { id -> callEngine.completeConsult(id) }

    /**
     * The shared shape of the five actions above: resolve the call id, run the
     * request, and say what happened in Hebrew either way.
     *
     * [what] is a noun phrase that reads correctly in all three sentences below
     * ("X נשלחה", "X נכשלה", "לא ניתן לבצע X"), so each caller supplies one string
     * instead of three.
     */
    private fun runCallAction(
        what: String,
        onSuccess: () -> Unit = {},
        action: suspend (consoleCallId: String) -> AppResult<Unit>,
    ) {
        val consoleCallId = _uiState.value.currentSession?.consoleCallId
        if (consoleCallId.isNullOrBlank()) {
            viewModelScope.launch {
                _effects.emit(
                    UiEffect.ShowSnackbar("לא ניתן לבצע $what — השיחה הזו אינה מזוהה בשרת."),
                )
            }
            return
        }
        viewModelScope.launch {
            when (action(consoleCallId)) {
                is AppResult.Success -> {
                    onSuccess()
                    _effects.emit(UiEffect.ShowSnackbar("$what נשלחה."))
                }
                is AppResult.Failure -> _effects.emit(UiEffect.ShowSnackbar("$what נכשלה."))
            }
        }
    }
}
