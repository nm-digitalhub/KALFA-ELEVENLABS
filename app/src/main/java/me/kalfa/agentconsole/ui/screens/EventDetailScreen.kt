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
    targets: List<CampaignTarget>,
    liveTranscripts: Map<String, List<TranscriptLine>> = emptyMap(),
    canManageVoice: Boolean = false,
    onBack: () -> Unit = {},
    onMonitor: (String) -> Unit = {},
    onTakeover: (String) -> Unit = {},
    onWhisper: (String, String) -> Unit = { _, _ -> },
    onMuteAi: (String) -> Unit = {},
    onCloseAi: (String) -> Unit = {},
    onToggleCampaign: (String) -> Unit = {},
    onDialTarget: (phone: String, name: String) -> Unit = { _, _ -> },
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
            1 -> GuestsTab(targets, rsvpResults, canManageVoice, onDialTarget)
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
    targets: List<CampaignTarget>,
    rsvpResults: List<RsvpResult>,
    canManageVoice: Boolean,
    onDialTarget: (String, String) -> Unit
) {
    val answersByGuestName = rsvpResults.associateBy { it.guestName }
    LazyColumn(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        if (targets.isEmpty()) item { EmptyLine("אין יעדי קמפיין לאירוע") }
        items(targets, key = { it.id }) { t ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.guestName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        val answer = answersByGuestName[t.guestName]?.answer?.labelHebrew
                        val sub = listOfNotNull(
                            t.phone.ifEmpty { null },
                            answer ?: t.lastResult
                        ).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Per-guest dial DISABLED until app-initiated outbound is wired
                    // to a real enqueue route (never a mock call).
                    if (canManageVoice && t.phone.isNotEmpty()) {
                        FilledTonalIconButton(onClick = { onDialTarget(t.phone, t.guestName) }, enabled = false) {
                            Icon(Icons.Default.Call, contentDescription = "חייג (בקרוב)", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp))
}
