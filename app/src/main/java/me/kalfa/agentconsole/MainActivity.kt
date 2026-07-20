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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.ui.screens.*
import me.kalfa.agentconsole.ui.screens.AuthGate
import me.kalfa.agentconsole.ui.theme.MyApplicationTheme
import me.kalfa.agentconsole.ui.viewmodel.*

class MainActivity : ComponentActivity() {
    private val viewModel: ConsoleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AuthGate {
                val state by viewModel.uiState.collectAsState()

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            // Bottom navigation is shown ONLY if there is no active call session!
                            if (state.currentSession == null) {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 8.dp
                                ) {
                                    NavigationBarItem(
                                        selected = state.currentScreen == Screen.Dashboard,
                                        onClick = { viewModel.setScreen(Screen.Dashboard) },
                                        icon = {
                                            Icon(
                                                imageVector = Icons.Default.Dashboard,
                                                contentDescription = "דשבורד"
                                            )
                                        },
                                        label = { Text("דשבורד") }
                                    )

                                    NavigationBarItem(
                                        selected = state.currentScreen == Screen.LiveCalls,
                                        onClick = { viewModel.setScreen(Screen.LiveCalls) },
                                        icon = {
                                            Icon(
                                                imageVector = Icons.Default.Hearing,
                                                contentDescription = "שיחות חיות"
                                            )
                                        },
                                        label = { Text("שיחות חיות") }
                                    )

                                    NavigationBarItem(
                                        selected = state.currentScreen == Screen.Campaigns,
                                        onClick = { viewModel.setScreen(Screen.Campaigns) },
                                        icon = {
                                            Icon(
                                                imageVector = Icons.Default.Campaign,
                                                contentDescription = "קמפיינים"
                                            )
                                        },
                                        label = { Text("קמפיינים") }
                                    )

                                    NavigationBarItem(
                                        selected = state.currentScreen == Screen.History,
                                        onClick = { viewModel.setScreen(Screen.History) },
                                        icon = {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = "היסטוריה"
                                            )
                                        },
                                        label = { Text("היסטוריה") }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        val contentModifier = Modifier.padding(innerPadding)

                        // If an active session is in progress, the full-screen InCallScreen overlays everything!
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
                            when (state.currentScreen) {
                                Screen.Dashboard -> {
                                    DashboardScreen(
                                        agentStatus = state.agentStatus,
                                        activeAiCalls = state.activeAiCallsCount,
                                        queueDepth = state.queueDepth,
                                        rsvpResults = state.rsvpResults,
                                        onStatusChange = { viewModel.setAgentStatus(it) },
                                        onMakeOutboundCall = { phone, name -> viewModel.makeOutboundCall(phone, name) },
                                        modifier = contentModifier
                                    )
                                }
                                Screen.LiveCalls -> {
                                    LiveCallsScreen(
                                        liveCalls = state.liveCalls,
                                        onMonitor = { viewModel.monitorCall(it) },
                                        onTakeover = { viewModel.takeoverCall(it) },
                                        modifier = contentModifier
                                    )
                                }
                                Screen.Campaigns -> {
                                    CampaignsScreen(
                                        campaigns = state.campaigns,
                                        onToggleCampaign = { viewModel.toggleCampaign(it) },
                                        modifier = contentModifier
                                    )
                                }
                                Screen.History -> {
                                    HistoryScreen(
                                        callHistory = state.callHistory,
                                        rsvpResults = state.rsvpResults,
                                        modifier = contentModifier
                                    )
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
