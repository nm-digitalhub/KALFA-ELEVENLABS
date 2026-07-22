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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.InlineMessage
import me.kalfa.agentconsole.ui.message.MessageSeverity
import me.kalfa.agentconsole.ui.message.MessageAction
import me.kalfa.agentconsole.ui.message.UiMessage
import me.kalfa.agentconsole.ui.message.toHebrewMessage

@Composable
fun EventDetailScreen(
    summary: EventSummary?,
    liveCalls: List<Call>,
    callHistory: List<Call>,
    rsvpResults: List<RsvpResult>,
    campaigns: List<Campaign>,
    guests: List<EventGuest>,
    guestCallFailures: Map<String, AppFailure> = emptyMap(),
    guestDispatchStatuses: Map<String, CallDispatchStatus> = emptyMap(),
    campaignFailures: Map<String, AppFailure> = emptyMap(),
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
                onMonitor, onTakeover, onWhisper, onMuteAi, onCloseAi, onToggleCampaign,
                campaignFailures)
            1 -> GuestsTab(
                guests,
                rsvpResults,
                canManageVoice,
                onEnqueueCall,
                guestCallFailures,
                guestDispatchStatuses
            )
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
    onToggleCampaign: (String) -> Unit,
    campaignFailures: Map<String, AppFailure>
) {
    LazyColumn(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { EventCard(summary) }

        campaigns.forEach { campaign ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampaignCard(
                        campaign = campaign,
                        onToggle = { if (canManageVoice) onToggleCampaign(campaign.id) }
                    )
                    campaignFailures[campaign.id]?.let { failure ->
                        InlineMessage(
                            message = UiMessage(
                                id = "campaign-${campaign.id}-failure",
                                severity = MessageSeverity.ERROR,
                                title = "שינוי מצב הקמפיין לא הושלם",
                                body = failure.toHebrewMessage(FailureContext.CAMPAIGN),
                                primaryAction = MessageAction("נסה שוב", "retry_campaign")
                            ),
                            onAction = { actionId ->
                                if (actionId == "retry_campaign") onToggleCampaign(campaign.id)
                            }
                        )
                    }
                }
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
    onEnqueueCall: (String) -> Unit,
    guestCallFailures: Map<String, AppFailure>,
    guestDispatchStatuses: Map<String, CallDispatchStatus>
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
            val failure = guestCallFailures[g.guestId]
            val dispatch = guestDispatchStatuses[g.guestId]
            val availability = manualDialAvailability(g, canManageVoice, failure, dispatch)
            val alreadyReached = availability.alreadyReached
            val callbackScheduled = availability.callbackScheduled
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.guestName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            val answer = answersByGuestName[g.guestName]?.answer?.labelHebrew
                            val sub = listOfNotNull(
                                g.phone.ifEmpty { null },
                                answer ?: g.rsvpStatus?.let { rsvpStatusHebrew(it) }
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canManageVoice) {
                            FilledTonalIconButton(
                                onClick = { pendingCall = g },
                                enabled = availability.enabled,
                                modifier = Modifier.semantics {
                                    contentDescription = availability.accessibilityDescription
                                }
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (alreadyReached) {
                        InlineMessage(
                            message = UiMessage(
                                id = "guest-${g.guestId}-reached",
                                severity = MessageSeverity.INFO,
                                title = "כבר נוצר קשר באירוע זה",
                                body = "עם אורח שכבר נוצר עמו קשר באירוע לא ניתן ליזום שיחה נוספת, כדי למנוע הטרדה וחיוב כפול."
                            ),
                            onAction = { }
                        )
                    } else if (callbackScheduled) {
                        InlineMessage(
                            message = UiMessage(
                                id = "guest-${g.guestId}-callback",
                                severity = MessageSeverity.INFO,
                                title = "האורח ביקש שיחת חזרה",
                                body = "שיחת החזרה תתבצע אוטומטית ב־${formatCallbackScheduledAt(g.callbackScheduledAt!!)}."
                            ),
                            onAction = { }
                        )
                    }
                    if (!alreadyReached) failure?.let { currentFailure ->
                        InlineMessage(
                            message = UiMessage(
                                id = "guest-${g.guestId}-call-failure",
                                severity = MessageSeverity.ERROR,
                                title = "השיחה לא נוספה לתור",
                                body = currentFailure.toHebrewMessage(FailureContext.GUEST_CALL),
                                primaryAction = MessageAction("נסה שוב", "retry_guest_call")
                            ),
                            onAction = { actionId ->
                                if (actionId == "retry_guest_call") onEnqueueCall(g.guestId)
                            }
                        )
                    }
                    if (!alreadyReached) dispatch?.let { currentDispatch ->
                        DispatchStatusMessage(currentDispatch, g.guestId, onEnqueueCall)
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
private fun DispatchStatusMessage(
    dispatch: CallDispatchStatus,
    guestId: String,
    onEnqueueCall: (String) -> Unit
) {
    val presentation = dispatchPresentation(dispatch)
    InlineMessage(
        message = UiMessage(
            id = "dispatch-${dispatch.dispatchId}",
            severity = if (dispatch.status == "failed") MessageSeverity.ERROR else MessageSeverity.INFO,
            title = presentation.title,
            body = presentation.body,
            primaryAction = if (presentation.retryAllowed) MessageAction("נסה שוב", "retry_dispatch") else null
        ),
        onAction = { if (it == "retry_dispatch") onEnqueueCall(guestId) }
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp))
}

// Raw console_event_guests.rsvp_status → Hebrew. Falls back to the raw value so
// an unknown status still shows something rather than nothing (spec §8.3:
// 'exhausted' was previously shown untranslated in the guest card).
private fun rsvpStatusHebrew(raw: String): String = when (raw.lowercase()) {
    "attending", "confirmed" -> "אישר הגעה"
    "declined" -> "לא מגיע"
    "maybe" -> "אולי"
    "callback" -> "ביקש שיחה חוזרת"
    "pending", "pending_contact" -> "ממתין"
    "reached", "reached_billed" -> "נוצר קשר"
    "no_answer" -> "אין מענה"
    "wrong_number" -> "מספר שגוי"
    "stopped" -> "הופסק"
    "exhausted" -> "מוצה"
    else -> raw
}
