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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.navigation.toRoute
import me.kalfa.agentconsole.ui.*
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
                            showNavigation = state.currentSession == null
                        ) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize()
                        ) { innerPadding ->
                            val contentModifier = Modifier.padding(innerPadding)

                            state.connectionError?.let { err ->
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth().padding(innerPadding)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(err, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.weight(1f))
                                        TextButton(onClick = { viewModel.refreshAll(); viewModel.dismissError() }) {
                                            Text("רענן")
                                        }
                                    }
                                }
                            }

                            // Full-screen InCall overlay while a session is active
                            val session = state.currentSession
                            if (session != null) {
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

    fun navigateTop(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            consoleDestinations.forEach { d: ConsoleDestination ->
                val selected = dest?.hierarchy?.any { h ->
                    d.routeClasses.any { rc -> h.hasRoute(rc) }
                } == true
                item(
                    selected = selected,
                    onClick = { navigateTop(d.route) },
                    icon = {
                        Icon(
                            imageVector = if (selected) d.selectedIcon else d.unselectedIcon,
                            contentDescription = d.label
                        )
                    },
                    label = { Text(d.label) }
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

    NavHost(
        navController = navController,
        startDestination = DashboardRoute
    ) {
        composable<DashboardRoute> {
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
        composable<LiveCallsRoute> {
            LiveCallsScreen(
                eventOptions = eventOptions,
                selectedEventId = filterId,
                onSelectEvent = { viewModel.setEventFilter(it) },
                liveTranscripts = state.liveTranscripts,
                onWhisper = { id, text -> viewModel.whisperToAi(id, text) },
                onMuteAi = { viewModel.muteAiOnce(it) },
                onCloseAi = { viewModel.closeAiAgent(it) },
                liveCalls = filteredLive,
                onMonitor = { viewModel.monitorCall(it) },
                onTakeover = { viewModel.takeoverCall(it) },
                modifier = contentModifier
            )
        }
        composable<EventsRoute> {
            EventsScreen(
                summaries = state.eventSummaries,
                onEventClick = { navController.navigate(EventDetailRoute(it)) },
                modifier = contentModifier
            )
        }
        composable<EventDetailRoute> { entry ->
            val route = entry.toRoute<EventDetailRoute>()
            val evId = route.eventId
            EventDetailScreen(
                summary = state.eventSummaries.firstOrNull { it.event.id == evId },
                liveCalls = state.liveCalls.filter { it.eventId == evId },
                callHistory = state.callHistory.filter { it.eventId == evId },
                rsvpResults = state.rsvpResults.filter { it.eventId == evId },
                campaigns = state.campaigns.filter { it.eventId == evId },
                targets = state.campaigns.filter { it.eventId == evId }
                    .flatMap { viewModel.targetsFor(it.id) },
                liveTranscripts = state.liveTranscripts,
                canManageVoice = state.me?.canManageVoice ?: false,
                onBack = { navController.popBackStack() },
                onMonitor = { viewModel.monitorCall(it) },
                onTakeover = { viewModel.takeoverCall(it) },
                onWhisper = { id, text -> viewModel.whisperToAi(id, text) },
                onMuteAi = { viewModel.muteAiOnce(it) },
                onCloseAi = { viewModel.closeAiAgent(it) },
                onToggleCampaign = { viewModel.toggleCampaign(it) },
                onDialTarget = { phone, name -> viewModel.makeOutboundCall(phone, name) },
                onCallClick = { call ->
                    viewModel.selectCall(call)
                    navController.navigate(CallDetailRoute(call.id))
                },
                modifier = contentModifier
            )
        }
        composable<HistoryRoute> {
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
        composable<CallDetailRoute> { entry ->
            val route = entry.toRoute<CallDetailRoute>()
            val call = (state.callHistory + state.liveCalls).firstOrNull { it.id == route.callId }
            if (call == null) {
                // Call vanished from state (e.g. process death restore) — go back gracefully
                LaunchedEffect(route.callId) { navController.popBackStack() }
            } else {
                CallDetailScreen(
                    call = call,
                    analysis = state.selectedAnalysis,
                    loading = state.analysisLoading,
                    onBack = { navController.popBackStack() },
                    modifier = contentModifier
                )
            }
        }
    }
}
