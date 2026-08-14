package me.kalfa.agentconsole

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.collect
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.telephony.EnsureCallAudioPermission
import me.kalfa.agentconsole.telephony.presence.PresenceForegroundService
import me.kalfa.agentconsole.telephony.vox.IncomingCallNotificationBuilder
import me.kalfa.agentconsole.telephony.vox.VoxIncomingCallCoordinator
import me.kalfa.agentconsole.ui.*
import me.kalfa.agentconsole.ui.message.AppMessageHost
import me.kalfa.agentconsole.ui.message.AppSnackbarHost
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.FullScreenErrorState
import me.kalfa.agentconsole.ui.message.UiEffect
import me.kalfa.agentconsole.ui.message.toHebrewMessage
import me.kalfa.agentconsole.domain.error.RepositoryHealth
import me.kalfa.agentconsole.ui.screens.*
import me.kalfa.agentconsole.ui.theme.MyApplicationTheme
import me.kalfa.agentconsole.ui.viewmodel.ConsoleUiState
import me.kalfa.agentconsole.ui.viewmodel.ConsoleViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: ConsoleViewModel by viewModels()

    override fun onStart() { super.onStart(); me.kalfa.agentconsole.di.AppVisibility.isForeground.value = true }
    override fun onStop() { super.onStop(); me.kalfa.agentconsole.di.AppVisibility.isForeground.value = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Gives DependencyContainer's VoxTokenStore a Context. Also called from
        // VoxFirebaseMessagingService.onCreate for the cold-start-via-push case
        // (AGENTS.md "Push wake-up"); attach() is idempotent either order.
        DependencyContainer.attach(applicationContext)
        applyIncomingCallWindowFlagsIfNeeded(intent)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AuthGate {
                    val state by viewModel.uiState.collectAsState()
                    val navController = rememberNavController()

                    // One-time landing by permission: manage_voice -> manager events overview
                    var redirected by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(state.me?.canManageVoice) {
                        if (!redirected && state.me?.canManageVoice == true) {
                            redirected = true
                            navController.navigate(EventsRoute) {
                                popUpTo(DashboardRoute) { inclusive = true }
                            }
                        }
                    }

                    // Presence that survives — starts/stops PresenceForegroundService
                    // in lockstep with AgentPresence.shiftActive (via ConsoleUiState),
                    // since starting a Service needs a Context ConsoleViewModel
                    // deliberately doesn't have. See
                    // docs/android-presence-and-call-ux.md §1.
                    LaunchedEffect(state.shiftActive) {
                        if (state.shiftActive) {
                            PresenceForegroundService.start(applicationContext)
                        } else {
                            PresenceForegroundService.stop(applicationContext)
                        }
                    }

                    // Calls can now arrive (and need answering) before the agent ever
                    // visits the live-calls screen that used to be the only place
                    // this was requested — see docs §3, "RECORD_AUDIO /
                    // POST_NOTIFICATIONS must be requested earlier".
                    //
                    // THE ONLY call site, deliberately. It used to say a
                    // `rememberSaveable` guard made a second mount a harmless no-op;
                    // that guard no longer exists (it was replaced by the durable
                    // PermissionRequestLog, which survives process death — the whole
                    // point), and the live-calls screen carried a second
                    // EnsureCallAudioPermission() until this change. Two mounts meant
                    // two independent MultiplePermissionsState objects, each with its
                    // own ActivityResultLauncher and its own LaunchedEffect, both able
                    // to fire while the other's dialog was still pending — and Android
                    // resolves that by dropping one, silently. This composable sits
                    // inside AuthGate at the root, so it covers every authenticated
                    // screen including live-calls; nothing needs a second one, and a
                    // second one can only take the dialog away.
                    EnsureCallAudioPermission()

                    // The incoming-call ring surface (docs §3) — a top-level overlay,
                    // independent of the (untouched) DEBUG-only InCallScreen branch
                    // below. incomingCallCoordinator is null only in mock mode / before
                    // DependencyContainer.attach() has run, in which case this simply
                    // never fires.
                    val incomingCallCoordinator = remember { DependencyContainer.incomingCallCoordinator }
                    val noOffer = remember {
                        kotlinx.coroutines.flow.MutableStateFlow<VoxIncomingCallCoordinator.IncomingOffer?>(null)
                    }
                    val pendingOffer by (incomingCallCoordinator?.pendingOffer ?: noOffer).collectAsState()

                    // Only the locked-device full-screen-intent launch sets these
                    // flags (applyIncomingCallWindowFlagsIfNeeded); clear them the
                    // moment the offer resolves so this activity does not keep
                    // bypassing the lock screen for ordinary future launches.
                    LaunchedEffect(pendingOffer) {
                        if (pendingOffer == null) clearIncomingCallWindowFlags()
                    }

                    if (pendingOffer != null) {
                        IncomingCallScreen(
                            displayName = pendingOffer?.displayName.orEmpty(),
                            onAnswer = { pendingOffer?.let { incomingCallCoordinator?.answer(it.callId) } },
                            onDecline = { pendingOffer?.let { incomingCallCoordinator?.decline(it.callId) } }
                        )
                    } else {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        // Adaptive nav: bottom bar on compact, rail on expanded. Hidden entirely during a call.
                        AdaptiveConsoleScaffold(
                            navController = navController,
                            // Hide nav exactly when the (DEBUG-only) in-call overlay shows.
                            // In release currentSession is always null (see below), so nav
                            // always shows.
                            showNavigation = !(state.currentSession != null && BuildConfig.DEBUG)
                        ) {
                            val snackbarHostState = remember { SnackbarHostState() }
                            LaunchedEffect(snackbarHostState) {
                                viewModel.effects.collect { effect ->
                                    when (effect) {
                                        is UiEffect.ShowSnackbar -> {
                                            val result = snackbarHostState.showSnackbar(
                                                message = effect.message,
                                                actionLabel = effect.actionLabel,
                                                withDismissAction = true
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                effect.actionId?.let(viewModel::handleGlobalMessageAction)
                                            }
                                        }
                                    }
                                }
                            }
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                snackbarHost = { AppSnackbarHost(snackbarHostState) }
                            ) { innerPadding ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding)
                                ) {
                                    AppMessageHost(
                                        messages = state.globalMessages,
                                        onAction = viewModel::handleGlobalMessageAction,
                                        onDismiss = viewModel::dismissMessage
                                    )

                                    val contentModifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)

                                    // Full-screen in-call overlay — DEBUG-only. In a release
                                    // build the real engine never fabricates a session (it
                                    // throws until the telephony-wiring step) and the ViewModel
                                    // gates monitor/takeover/outbound to an honest "בקרוב"
                                    // notice, so this fake in-call surface is structurally
                                    // unreachable in production. Kept for DEBUG demos and as the
                                    // layout the telephony-wiring step will wire for real.
                                    val session = state.currentSession
                                    if (session != null && BuildConfig.DEBUG) {
                                        InCallScreen(
                                            customerName = session.customerName,
                                            customerPhone = session.customerPhone,
                                            state = state.currentSessionState,
                                            isMuted = state.currentSessionMuted,
                                            isHeld = state.currentSessionHeld,
                                            durationSec = state.currentSessionDuration,
                                            notes = state.inCallNotes,
                                            rsvpAnswer = state.inCallRsvpAnswer,
                                            guestsCount = state.inCallGuestsCount,
                                            onNotesChange = { viewModel.updateInCallNotes(it) },
                                            onRsvpAnswerChange = { viewModel.updateInCallRsvpAnswer(it) },
                                            onGuestsCountChange = { viewModel.updateInCallGuestsCount(it) },
                                            onMuteToggle = { viewModel.toggleMute() },
                                            onHoldToggle = { viewModel.toggleHold() },
                                            onSendDtmf = { viewModel.sendDtmf(it) },
                                            onHangup = { viewModel.hangupDirectly() },
                                            onSubmitRsvpAndHangup = { viewModel.submitRsvpAndHangup() }
                                        )
                                    } else {
                                        ConsoleNavHost(navController, state, viewModel, contentModifier)
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    // Delivered when a full-screen-intent-launched call (docs §3) targets this
    // Activity while it is already running (singleTask — see AndroidManifest.xml) —
    // Android routes it here instead of a fresh onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingCallWindowFlagsIfNeeded(intent)
    }

    // Bypasses the lock screen ONLY for the locked-device incoming-call
    // full-screen-intent launch (docs/android-presence-and-call-ux.md §3) — never for
    // an ordinary app open. setShowWhenLocked/setTurnScreenOn are API 27+; 24-26 falls
    // back to the equivalent WindowManager flags (spec's version-gating table).
    private fun applyIncomingCallWindowFlagsIfNeeded(intent: Intent?) {
        if (intent?.action != IncomingCallNotificationBuilder.ACTION_INCOMING_CALL_UI) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun clearIncomingCallWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }
}

@Composable
private fun AdaptiveConsoleScaffold(
    navController: NavHostController,
    showNavigation: Boolean,
    content: @Composable () -> Unit
) {
    if (!showNavigation) {
        content()
        return
    }

    val backStack by navController.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val colorScheme = MaterialTheme.colorScheme

    val navigationColors = NavigationSuiteDefaults.colors(
        navigationBarContainerColor = colorScheme.surface,
        navigationBarContentColor = colorScheme.onSurfaceVariant,
        navigationRailContainerColor = colorScheme.surface,
        navigationRailContentColor = colorScheme.onSurfaceVariant,
        navigationDrawerContainerColor = colorScheme.surface,
        navigationDrawerContentColor = colorScheme.onSurfaceVariant
    )

    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = colorScheme.primary,
            selectedTextColor = colorScheme.primary,
            indicatorColor = colorScheme.primaryContainer,
            unselectedIconColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            unselectedTextColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = colorScheme.primary,
            selectedTextColor = colorScheme.primary,
            indicatorColor = colorScheme.primaryContainer,
            unselectedIconColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            unselectedTextColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        )
    )

    fun navigateTop(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteColors = navigationColors,
        navigationSuiteItems = {
            consoleDestinations.forEach { d: ConsoleDestination ->
                val selected = dest?.hierarchy?.any { h ->
                    d.routeClasses.any { rc -> h.hasRoute(rc) }
                } == true
                item(
                    selected = selected,
                    onClick = { navigateTop(d.route) },
                    alwaysShowLabel = false,
                    colors = itemColors,
                    icon = {
                        Icon(
                            imageVector = if (selected) d.selectedIcon else d.unselectedIcon,
                            contentDescription = d.label,
                            modifier = Modifier.size(if (selected) 25.dp else 22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = d.label,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                )
            }
        }
    ) {
        content()
    }
}

@Composable
private fun ConsoleNavHost(
    navController: NavHostController,
    state: ConsoleUiState,
    viewModel: ConsoleViewModel,
    contentModifier: Modifier
) {
    val filterId = state.selectedEventFilter
    val filteredLive = if (filterId == null) state.liveCalls else state.liveCalls.filter { it.eventId == filterId }
    val filteredHistory = if (filterId == null) state.callHistory else state.callHistory.filter { it.eventId == filterId }
    val filteredRsvp = if (filterId == null) state.rsvpResults else state.rsvpResults.filter { it.eventId == filterId }
    val eventOptions = state.events.map { it.id to it.name }
    val callBlockingFailure = (state.callHealth as? RepositoryHealth.Stale)
        ?.takeUnless { it.hasCachedData }
        ?.reason
    val campaignBlockingFailure = (state.campaignHealth as? RepositoryHealth.Stale)
        ?.takeUnless { it.hasCachedData }
        ?.reason
    val rsvpBlockingFailure = (state.rsvpHealth as? RepositoryHealth.Stale)
        ?.takeUnless { it.hasCachedData }
        ?.reason
    val dashboardBlockingFailure =
        callBlockingFailure ?: campaignBlockingFailure ?: rsvpBlockingFailure

    NavHost(
        navController = navController,
        startDestination = DashboardRoute
    ) {
        composable<DashboardRoute> {
            if (dashboardBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את לוח הבקרה",
                    message = dashboardBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = {
                        viewModel.handleGlobalMessageAction("retry_calls")
                        viewModel.handleGlobalMessageAction("retry_campaigns")
                        viewModel.handleGlobalMessageAction("retry_rsvp")
                    },
                    modifier = contentModifier
                )
            } else {
                DashboardScreen(
                    agentName = state.agentName,
                    agentEmail = state.agentEmail,
                    onLogout = { viewModel.logout() },
                    agentStatus = state.agentStatus,
                    activeAiCalls = state.activeAiCallsCount,
                    queueDepth = state.queueDepth,
                    rsvpResults = state.rsvpResults,
                    onStatusChange = { viewModel.setAgentStatus(it) },
                    onMakeOutboundCall = { phone, name -> viewModel.makeOutboundCall(phone, name) },
                    modifier = contentModifier
                )
            }
        }
        composable<LiveCallsRoute> {
            if (callBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את השיחות",
                    message = callBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = { viewModel.handleGlobalMessageAction("retry_calls") },
                    modifier = contentModifier
                )
            } else {
                // Permissions are NOT requested here. The root call site inside
                // AuthGate already covers this screen, and mounting a second
                // EnsureCallAudioPermission() gave this route its own competing
                // permission launcher — see that comment for why that suppresses the
                // dialog instead of adding one.
                LiveCallsScreen(
                    eventOptions = eventOptions,
                    selectedEventId = filterId,
                    onSelectEvent = { viewModel.setEventFilter(it) },
                    liveTranscripts = state.liveTranscripts,
                    onWhisper = { id, text -> viewModel.whisperToAi(id, text) },
                    onMuteAi = { viewModel.muteAiOnce(it) },
                    onCloseAi = { viewModel.closeAiAgent(it) },
                    onEndCall = { viewModel.endCall(it) },
                    liveCalls = filteredLive,
                    onMonitor = { viewModel.monitorCall(it) },
                    onTakeover = { viewModel.takeoverCall(it) },
                    modifier = contentModifier
                )
            }
        }
        composable<EventsRoute> {
            if (dashboardBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את האירועים",
                    message = dashboardBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = {
                        viewModel.handleGlobalMessageAction("retry_calls")
                        viewModel.handleGlobalMessageAction("retry_campaigns")
                        viewModel.handleGlobalMessageAction("retry_rsvp")
                    },
                    modifier = contentModifier
                )
            } else {
                EventsScreen(
                    summaries = state.eventSummaries,
                    onEventClick = { navController.navigate(EventDetailRoute(it)) },
                    modifier = contentModifier
                )
            }
        }
        composable<EventDetailRoute> { entry ->
            val route = entry.toRoute<EventDetailRoute>()
            val evId = route.eventId
            val eventGuests by remember(evId) { viewModel.eventGuests(evId) }.collectAsState()
            if (dashboardBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את האירוע",
                    message = dashboardBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = {
                        viewModel.handleGlobalMessageAction("retry_calls")
                        viewModel.handleGlobalMessageAction("retry_campaigns")
                        viewModel.handleGlobalMessageAction("retry_rsvp")
                    },
                    modifier = contentModifier
                )
            } else EventDetailScreen(
                summary = state.eventSummaries.firstOrNull { it.event.id == evId },
                liveCalls = state.liveCalls.filter { it.eventId == evId },
                callHistory = state.callHistory.filter { it.eventId == evId },
                rsvpResults = state.rsvpResults.filter { it.eventId == evId },
                campaigns = state.campaigns.filter { it.eventId == evId },
                guests = eventGuests,
                guestCallFailures = state.guestCallFailures,
                guestDispatchStatuses = state.guestDispatchStatuses,
                campaignFailures = state.campaignFailures,
                liveTranscripts = state.liveTranscripts,
                canManageVoice = state.me?.canManageVoice ?: false,
                onBack = { navController.popBackStack() },
                onMonitor = { viewModel.monitorCall(it) },
                onTakeover = { viewModel.takeoverCall(it) },
                onWhisper = { id, text -> viewModel.whisperToAi(id, text) },
                onMuteAi = { viewModel.muteAiOnce(it) },
                onCloseAi = { viewModel.closeAiAgent(it) },
                onToggleCampaign = { viewModel.toggleCampaign(it) },
                onEnqueueCall = { guestId -> viewModel.enqueueOutboundCall(evId, guestId) },
                onCallClick = { call ->
                    viewModel.selectCall(call)
                    navController.navigate(CallDetailRoute(call.id))
                },
                modifier = contentModifier
            )
        }
        composable<HistoryRoute> {
            val historyBlockingFailure = callBlockingFailure ?: rsvpBlockingFailure
            if (historyBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את היסטוריית השיחות",
                    message = historyBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = {
                        viewModel.handleGlobalMessageAction("retry_calls")
                        viewModel.handleGlobalMessageAction("retry_rsvp")
                    },
                    modifier = contentModifier
                )
            } else {
                HistoryScreen(
                    eventOptions = eventOptions,
                    selectedEventId = filterId,
                    onSelectEvent = { viewModel.setEventFilter(it) },
                    onCallClick = { call ->
                        viewModel.selectCall(call)
                        navController.navigate(CallDetailRoute(call.id))
                    },
                    callHistory = filteredHistory,
                    rsvpResults = filteredRsvp,
                    modifier = contentModifier
                )
            }
        }
        composable<CallDetailRoute> { entry ->
            val route = entry.toRoute<CallDetailRoute>()
            val call = (state.callHistory + state.liveCalls).firstOrNull { it.id == route.callId }
            if (call == null) {
                // Call vanished from state (e.g. process death restore) - go back gracefully
                LaunchedEffect(route.callId) { navController.popBackStack() }
            } else {
                CallDetailScreen(
                    call = call,
                    analysisState = state.analysisState,
                    onBack = { navController.popBackStack() },
                    onRetryAnalysis = { viewModel.selectCall(call) },
                    modifier = contentModifier
                )
            }
        }
    }
}
