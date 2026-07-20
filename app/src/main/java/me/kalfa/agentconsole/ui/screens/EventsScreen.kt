package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.domain.model.CampaignState
import me.kalfa.agentconsole.domain.model.EventSummary

fun formatEventDate(iso: String?): String =
    iso?.take(10)?.split("-")?.reversed()?.joinToString(".") ?: ""

private fun eventTypeLabel(type: String?): String = when (type) {
    "wedding" -> "חתונה"
    "bar_mitzvah" -> "בר מצווה"
    "bat_mitzvah" -> "בת מצווה"
    "henna" -> "חינה"
    "brit", "brit_mila" -> "ברית"
    else -> type ?: ""
}

@Composable
fun EventsScreen(
    summaries: List<EventSummary>,
    onEventClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (finished, current) = summaries.partition { it.campaignState == CampaignState.COMPLETED }
    var showFinished by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text("סקירת מנהל", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            Text("אירועים", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
        }

        if (summaries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("אין אירועים בסקופ שיחות ה-AI",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(current, key = { it.event.id }) { s -> EventCard(s, onClick = { onEventClick(s.event.id) }) }

            if (finished.isNotEmpty()) {
                item {
                    TextButton(onClick = { showFinished = !showFinished }) {
                        Text(if (showFinished) "הסתר אירועים שהסתיימו (${finished.size})"
                             else "הצג אירועים שהסתיימו (${finished.size})")
                    }
                }
                if (showFinished) {
                    items(finished, key = { it.event.id }) { s -> EventCard(s, onClick = { onEventClick(s.event.id) }) }
                }
            }
        }
    }
}

@Composable
fun EventCard(s: EventSummary, onClick: () -> Unit = {}) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.event.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            eventTypeLabel(s.event.type).ifEmpty { null },
                            formatEventDate(s.event.date).ifEmpty { null }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    s.liveNow > 0 -> Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, null, Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("${s.liveNow} חיות")
                        }
                    }
                    s.campaignState != null -> CampaignStatusBadge(state = s.campaignState)
                    else -> Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("טרם הופעל קמפיין", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (s.targetsTotal > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("התקדמות שיחות", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${s.targetsDone} מתוך ${s.targetsTotal}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { if (s.targetsTotal == 0) 0f else s.targetsDone.toFloat() / s.targetsTotal },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RsvpStat("מגיעים", s.attending, MaterialTheme.colorScheme.primary)
                RsvpStat("אורחים", s.totalGuests, MaterialTheme.colorScheme.tertiary)
                RsvpStat("לא", s.declined, MaterialTheme.colorScheme.error)
                RsvpStat("אולי", s.maybe, MaterialTheme.colorScheme.secondary)
                RsvpStat("חזרו", s.callback, MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun RsvpStat(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
