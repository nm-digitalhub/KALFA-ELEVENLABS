package me.kalfa.agentconsole

import android.os.Bundle
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
import me.kalfa.agentconsole.telephony.EnsureCallAudioPermission
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
        me.kalfa.agentconsole.di.DependencyContainer.attach(applicationContext)
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
                // Obtain mic + notification permissions at the live-supervision surface, so
                // the grant is in hand before the telephony-wiring step attaches a real leg.
                EnsureCallAudioPermission()
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
