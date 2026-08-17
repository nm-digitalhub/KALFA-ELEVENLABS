package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.telephony.ConsoleCallRecord
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.kalfa.agentconsole.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    eventOptions: List<Pair<String, String>> = emptyList(),
    selectedEventId: String? = null,
    onSelectEvent: (String?) -> Unit = {},
    onDialFromHistory: (ConsoleCallRecord) -> Unit = {},
    callHistory: List<ConsoleCallRecord>,
    rsvpResults: List<RsvpResult>,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("יומן שיחות", "תוצאות אישור הגעה")

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ארכיון ודוחות",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "היסטוריית פעילות",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Tab Row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                divider = { Divider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f)) },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    )
                }
            }

            EventFilterChips(events = eventOptions, selectedEventId = selectedEventId, onSelect = onSelectEvent)

            // Tab Content
            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    CallHistoryList(callHistory = callHistory, onDial = onDialFromHistory)
                } else {
                    RsvpResultsList(rsvpResults = rsvpResults)
                }
            }
        }
    }
}

/**
 * Formats a call's timestamp the way an agent reads one: relative when it is recent,
 * dated when it is not.
 *
 * SimpleDateFormat rather than java.time, deliberately. minSdk here is 24 and core
 * library desugaring is not enabled, so java.time would compile fine and then throw
 * NoClassDefFoundError on API 24–25 — a crash on the history screen of the oldest
 * devices this app supports, invisible to every test that runs on a newer one.
 *
 * The server sends UTC; agents read Asia/Jerusalem. Parsing as UTC and formatting in
 * the device's own zone is what keeps a 19:42 call from displaying as 16:42.
 */
private fun formatCallTime(isoUtc: String): String {
    if (isoUtc.isBlank()) return ""
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        // Postgres sends fractional seconds and an offset; neither is needed to place
        // the call to the minute, and trimming is more predictable than a pattern that
        // has to match every variant the driver might produce.
        val parsed = parser.parse(isoUtc.take(19)) ?: return ""

        val local = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val now = java.util.Date()
        val yesterday = java.util.Date(now.time - 24L * 60 * 60 * 1000)

        val time = local.format(parsed)
        when (dayKey.format(parsed)) {
            dayKey.format(now) -> "היום $time"
            dayKey.format(yesterday) -> "אתמול $time"
            else -> java.text.SimpleDateFormat("d.M", java.util.Locale.US).format(parsed) + " $time"
        }
    } catch (e: Exception) {
        // A malformed timestamp must not blank the whole row — the call still happened
        // and everything else about it is worth showing.
        ""
    }
}

/** "3:05", not "185 שניות" — the form every phone shows a call length in. */
private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "—"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
fun CallHistoryList(
    callHistory: List<ConsoleCallRecord>,
    onDial: (ConsoleCallRecord) -> Unit = {},
) {
    if (callHistory.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "אין שיחות בהיסטוריה", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(callHistory, key = { it.id }) { call ->
                ConsoleCallCard(call = call, onDial = { onDial(call) })
            }
        }
    }
}

/**
 * One past call.
 *
 * WHAT CHANGED, because the shape looks similar and the content does not. This card
 * used to render `Call.customerName` and `Call.customerPhone` from the
 * `console_call_feed` mapper, which hardcodes them to "אורח" and "" — so every row in
 * the app's entire history read "אורח" above a blank line. It now takes a
 * ConsoleCallRecord from /api/agents/call-history, which carries the real name and
 * number of the person on the call.
 *
 * The title falls back to the NUMBER when there is no name, rather than to a
 * placeholder. For a caller who has never been a customer the number is their whole
 * identity, and "אורח" would be strictly less information wearing the appearance of
 * more.
 */
@Composable
fun ConsoleCallCard(call: ConsoleCallRecord, onDial: () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = when {
                            !call.answered -> ColorDanger.copy(alpha = 0.12f)
                            call.inbound -> ColorSuccess.copy(alpha = 0.12f)
                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    // A missed call gets its OWN icon rather than the inbound one in a
                    // different colour: colour alone is not a distinction every agent
                    // can make, and this is the row they are scanning for.
                    imageVector = when {
                        !call.answered -> Icons.Default.CallMissed
                        call.inbound -> Icons.Default.CallReceived
                        else -> Icons.Default.CallMade
                    },
                    contentDescription = null,
                    tint = when {
                        !call.answered -> ColorDanger
                        call.inbound -> ColorSuccess
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.name ?: call.phone ?: "מספר חסוי",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The number, but only when it is not already the title — printing it
                // twice for an unrecognised caller reads as a rendering fault.
                if (call.phone != null && call.name != null) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = call.phone,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(formatCallTime(call.startedAt))
                        if (call.answered) {
                            append(" · ")
                            append(formatDuration(call.durationSec))
                        } else {
                            append(" · לא נענתה")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }

            // Shown only where a consent-checked path back exists. An inbound call from
            // a number with no contact record has none, and dial-intent would refuse
            // it — an always-visible button that sometimes errors teaches an agent to
            // distrust the one that works.
            if (call.dialable) {
                IconButton(
                    onClick = onDial,
                    modifier = Modifier.semantics {
                        contentDescription = "התקשר אל ${call.name ?: call.phone ?: ""}"
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun RsvpResultsList(rsvpResults: List<RsvpResult>) {
    if (rsvpResults.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "אין תוצאות אישור הגעה", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(rsvpResults) { result ->
                RsvpResultCard(result = result)
            }
        }
    }
}

@Composable
fun RsvpResultCard(result: RsvpResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = result.guestName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                
                RsvpAnswerBadge(answer = result.answer)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "אורחים מגיעים: ${if (result.answer == RsvpAnswer.ATTENDING) result.guestsCount else 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            if (result.notes.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Text(
                        text = "הערות: ${result.notes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun RsvpAnswerBadge(answer: RsvpAnswer) {
    val (bg, textCol) = when (answer) {
        RsvpAnswer.ATTENDING -> ColorSuccess.copy(alpha = 0.15f) to ColorSuccess
        RsvpAnswer.DECLINED -> ColorDanger.copy(alpha = 0.15f) to ColorDanger
        RsvpAnswer.MAYBE -> ColorWarning.copy(alpha = 0.15f) to ColorWarning
        RsvpAnswer.CALLBACK -> ColorInfo.copy(alpha = 0.15f) to ColorInfo
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = answer.labelHebrew,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = textCol
        )
    }
}

@Preview(showBackground = true)
@Composable
fun HistoryScreenPreview() {
    MyApplicationTheme {
        HistoryScreen(
            callHistory = listOf(
                ConsoleCallRecord(
                    id = "1",
                    inbound = true,
                    name = "מבורך קלפה",
                    phone = "+972536212562",
                    startedAt = "2026-08-17T16:42:22",
                    durationSec = 185,
                    answered = true,
                    eventId = "e-1",
                    contactId = "c-1",
                ),
                ConsoleCallRecord(
                    id = "2",
                    inbound = true,
                    // No name: the number becomes the title, which is the case this
                    // whole change exists for.
                    name = null,
                    phone = "+972501234567",
                    startedAt = "2026-08-17T14:10:00",
                    durationSec = 0,
                    answered = false,
                    eventId = null,
                    contactId = null,
                ),
            ),
            rsvpResults = listOf(
                RsvpResult("1", "c1", "g1", "שמעון ישראלי", RsvpAnswer.ATTENDING, 3, "בקשת מנה טבעונית אחת")
            )
        )
    }
}

// The AI-call-attempt card, used by EventDetailScreen. Kept alongside
// ConsoleCallCard above rather than merged with it: the two render different things
// from different tables — this one a `call_attempts` row from console_call_feed (an
// AI RSVP call, no PII by design), that one a `console_calls` row a human took, with
// the caller's real name and number. Merging them would mean one card pretending it
// knows an identity it was never given.
//
// NOTE this card still shows "אורח" and a blank number for every row, because its
// mapper hardcodes both — the same defect the console history just stopped having.
// Fixing it is a separate change against the event surface, not a rename here.
@Composable
fun HistoryCallCard(call: Call, onClick: () -> Unit = {}) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Name & Type
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = if (call.direction == "inbound") ColorSuccess.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (call.direction == "inbound") Icons.Default.CallReceived else Icons.Default.CallMade,
                            contentDescription = null,
                            tint = if (call.direction == "inbound") ColorSuccess else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    
                    Column {
                        Text(
                            text = call.customerName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = call.customerPhone,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // Call metadata: time & duration
                Column(horizontalAlignment = Alignment.End) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = call.startedAt,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${call.durationSec} שניות",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "אירוע: ${call.eventName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                // Handled By tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (call.handledBy == "ai") MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (call.handledBy == "ai") "שיחת AI" else "שיחת נציג",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (call.handledBy == "ai") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

