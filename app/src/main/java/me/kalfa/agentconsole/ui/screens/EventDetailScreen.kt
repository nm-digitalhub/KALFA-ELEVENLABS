package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.domain.model.*

@Composable
fun EventDetailScreen(
    summary: EventSummary?,
    liveCalls: List<Call>,
    callHistory: List<Call>,
    rsvpResults: List<RsvpResult>,
    campaigns: List<Campaign>,
    guests: List<EventGuest>,
    liveTranscripts: Map<String, List<TranscriptLine>> = emptyMap(),
    canManageVoice: Boolean = false,
    onBack: () -> Unit = {},
    onMonitor: (String) -> Unit = {},
    onTakeover: (String) -> Unit = {},
    onWhisper: (String, String) -> Unit = { _, _ -> },
    onMuteAi: (String) -> Unit = {},
    onCloseAi: (String) -> Unit = {},
    onToggleCampaign: (String) -> Unit = {},
    onEnqueueCall: (guestId: String) -> Unit = {},
    onCallClick: (Call) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (summary == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("סקירה", "אורחים", "היסטוריה")

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "חזרה")
            }
            Column {
                Text(summary.event.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(formatEventDate(summary.event.date), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }

        when (tab) {
            0 -> OverviewTab(summary, liveCalls, campaigns, liveTranscripts, canManageVoice,
                onMonitor, onTakeover, onWhisper, onMuteAi, onCloseAi, onToggleCampaign)
            1 -> GuestsTab(guests, rsvpResults, canManageVoice, onEnqueueCall)
            2 -> LazyColumn(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (callHistory.isEmpty()) item { EmptyLine("אין שיחות בהיסטוריה לאירוע") }
                items(callHistory, key = { it.id }) { call ->
                    HistoryCallCard(call = call, onClick = { onCallClick(call) })
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(
    summary: EventSummary,
    liveCalls: List<Call>,
    campaigns: List<Campaign>,
    liveTranscripts: Map<String, List<TranscriptLine>>,
    canManageVoice: Boolean,
    onMonitor: (String) -> Unit,
    onTakeover: (String) -> Unit,
    onWhisper: (String, String) -> Unit,
    onMuteAi: (String) -> Unit,
    onCloseAi: (String) -> Unit,
    onToggleCampaign: (String) -> Unit
) {
    LazyColumn(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { EventCard(summary) }

        campaigns.forEach { campaign ->
            item {
                CampaignCard(
                    campaign = campaign,
                    onToggle = { if (canManageVoice) onToggleCampaign(campaign.id) }
                )
            }
        }

        item {
            Text("שיחות חיות (${liveCalls.size})", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        }
        if (liveCalls.isEmpty()) item { EmptyLine("אין שיחות פעילות כרגע") }
        items(liveCalls, key = { it.id }) { call ->
            if (canManageVoice) {
                LiveCallCard(
                    call = call,
                    onMonitor = { onMonitor(call.id) },
                    onTakeover = { onTakeover(call.id) },
                    liveLines = liveTranscripts[call.id].orEmpty(),
                    onWhisper = { text -> onWhisper(call.id, text) },
                    onMuteAi = { onMuteAi(call.id) },
                    onCloseAi = { onCloseAi(call.id) }
                )
            } else {
                LiveCallCard(call = call, onMonitor = { onMonitor(call.id) }, onTakeover = { onTakeover(call.id) })
            }
        }
    }
}

@Composable
private fun GuestsTab(
    guests: List<EventGuest>,
    rsvpResults: List<RsvpResult>,
    canManageVoice: Boolean,
    onEnqueueCall: (String) -> Unit
) {
    val answersByGuestName = rsvpResults.associateBy { it.guestName }
    var pendingCall by remember { mutableStateOf<EventGuest?>(null) }
    LazyColumn(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        if (guests.isEmpty()) item { EmptyLine("אין אורחים לאירוע") }
        items(guests, key = { it.guestId }) { g ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.guestName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        val answer = answersByGuestName[g.guestName]?.answer?.labelHebrew
                        val sub = listOfNotNull(
                            g.phone.ifEmpty { null },
                            answer ?: g.rsvpStatus
                        ).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Per-guest outbound — enqueues a REAL AI call via the gated worker
                    // (POST /api/events/{eventId}/outreach-call) using the guest's real
                    // guests.id. Offered ONLY when the event has an active campaign (the
                    // route's own 409 gate) and the guest is dialable — so we never show
                    // a button that the backend would immediately refuse. Confirm first.
                    if (canManageVoice && g.dialable && g.hasActiveCampaign) {
                        FilledTonalIconButton(onClick = { pendingCall = g }) {
                            Icon(Icons.Default.Call, contentDescription = "חייג", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    pendingCall?.let { guest ->
        AlertDialog(
            onDismissRequest = { pendingCall = null },
            title = { Text("לחייג ל${guest.guestName}?") },
            text = { Text("המערכת תוסיף שיחת AI לתור ותחייג לפי כללי הקמפיין (הסכמה, מגבלות, יתרה).") },
            confirmButton = {
                TextButton(onClick = { onEnqueueCall(guest.guestId); pendingCall = null }) { Text("חייג") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCall = null }) { Text("ביטול") }
            },
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp))
}
