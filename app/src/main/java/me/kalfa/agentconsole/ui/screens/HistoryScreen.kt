package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.domain.model.*
import me.kalfa.agentconsole.domain.telephony.CallOutcome
import me.kalfa.agentconsole.domain.telephony.ConsoleCallRecord
import me.kalfa.agentconsole.domain.telephony.ConsoleHistoryFilter
import me.kalfa.agentconsole.domain.telephony.HistoryDirection
import me.kalfa.agentconsole.domain.telephony.HistoryOutcome
import me.kalfa.agentconsole.domain.telephony.HistoryRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.kalfa.agentconsole.ui.theme.*

/**
 * The call log.
 *
 * WHAT THIS SCREEN STOPPED BEING, because two things were removed rather than
 * restyled and the reasons are not cosmetic.
 *
 * 1. AN EVENT FILTER SAT ABOVE THE LIST AND FILTERED NOTHING. `EventFilterChips`
 *    was rendered above both tabs, but MainActivity passed `state.consoleHistory`
 *    through unfiltered while filtering only the RSVP list beside it. Tapping an
 *    event changed the screen not at all — a control that appears to work and does
 *    not, which is worse than an absent one because it makes an empty result
 *    unreadable: no way to tell "no calls" from "the filter is broken".
 *
 *    Working, it would still have been the wrong axis. Measured on the live
 *    database 2026-08-17: of 1,241 inbound calls in one week, 28 carried an event.
 *    This is a business phone line, not an event guest list, and 98% of what rings
 *    it has nothing to do with any event. It is replaced by the axes a phone log is
 *    actually read along — time, outcome, direction.
 *
 * 2. THE RSVP TAB IS GONE. It rendered guest names, head counts and free-text
 *    notes ("בקשת מנה טבעונית אחת") — personal data belonging to a CUSTOMER'S
 *    GUESTS, on the owner's call archive. A guest is not our customer; they are our
 *    customer's invitee, and their dietary preferences have no business on a screen
 *    about telephony. The aggregate RSVP read-out on the dashboard is untouched:
 *    counts are not identities.
 *
 * What remains is one list, and every row's identity is the NUMBER.
 */
@Composable
fun HistoryScreen(
    filter: ConsoleHistoryFilter = ConsoleHistoryFilter(),
    onFilterChange: (ConsoleHistoryFilter) -> Unit = {},
    onDialFromHistory: (ConsoleCallRecord) -> Unit = {},
    /**
     * Loads the list. NOT optional in practice, and its absence is why this screen
     * shipped showing "אין שיחות בהיסטוריה" over 1,242 rows: the state, the endpoint,
     * the model and this screen were all wired, and nothing ever called the loader.
     * An empty list is indistinguishable from a list never asked for, which is what
     * made it invisible to every gate — it compiled, it tested, it rendered.
     */
    onRefreshHistory: () -> Unit = {},
    /** Dials a number the agent typed. See ManualDialSheet. */
    onDialManual: (String) -> Unit = {},
    callHistory: List<ConsoleCallRecord>,
    loading: Boolean = false,
    failed: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    var openCall by remember { mutableStateOf<ConsoleCallRecord?>(null) }
    var showDialpad by remember { mutableStateOf(false) }

    // Re-runs when the filter changes, because the filter is applied SERVER-SIDE.
    // Narrowing the page already in hand would report "no calls" for a range full of
    // them — the server returns a bounded page, not the whole history.
    LaunchedEffect(filter) { onRefreshHistory() }

    if (showFilterSheet) {
        HistoryFilterSheet(
            current = filter,
            onApply = {
                onFilterChange(it)
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    if (showDialpad) {
        ManualDialSheet(
            onDial = {
                onDialManual(it)
                showDialpad = false
            },
            onDismiss = { showDialpad = false },
        )
    }

    openCall?.let { selected ->
        CallDetailSheet(
            call = selected,
            onDial = {
                onDialFromHistory(selected)
                openCall = null
            },
            onDismiss = { openCall = null },
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
      Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ארכיון",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "יומן שיחות",
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

            HistoryFilterBar(
                filter = filter,
                onChange = onFilterChange,
                onOpenSheet = { showFilterSheet = true },
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    // Order matters: a failed read must never be shown as an empty
                    // log. "Nobody called" and "we could not find out" are opposite
                    // facts and an agent acts on them differently.
                    failed -> HistoryNotice(
                        text = "לא ניתן לטעון את יומן השיחות.",
                        actionLabel = "נסה שוב",
                        onAction = onRefreshHistory,
                    )
                    loading && callHistory.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    callHistory.isEmpty() -> HistoryNotice(
                        // Worded from the filter, so an empty result is attributable.
                        // "אין שיחות" under an active filter reads as a broken screen.
                        text = if (filter.isDefault) {
                            "אין שיחות ב${filter.range.labelHebrew} האחרון."
                        } else {
                            "אין שיחות שמתאימות לסינון שנבחר."
                        },
                        actionLabel = if (filter.isDefault) null else "נקה סינון",
                        onAction = { onFilterChange(ConsoleHistoryFilter(range = filter.range)) },
                    )
                    else -> CallHistoryList(
                        callHistory = callHistory,
                        onDial = onDialFromHistory,
                        onOpen = { openCall = it },
                    )
                }
            }
        }

        // A call log you cannot dial FROM is a list. Every row already offers a
        // call-back; this is the number nobody has called us from yet, which had
        // no way in at all until dial-intent gained the `manual` shape.
        FloatingActionButton(
            onClick = { showDialpad = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .semantics { contentDescription = "חיוג למספר חדש" },
        ) {
            Icon(Icons.Default.Dialpad, contentDescription = null)
        }
      }
    }
}

/**
 * Time, outcome, direction.
 *
 * Horizontally scrollable rather than wrapped: on a narrow phone in RTL a wrapping
 * chip row reflows as options are toggled, and a control that moves under the
 * thumb between taps is how an agent selects the wrong one.
 */
/**
 * The shortcuts, plus the way in to everything else.
 *
 * The presets answer the common question in one tap. They are NOT the filter — the
 * platform takes a date range to the second, a specific number and a duration band,
 * and offering only three drawers while calling it filtering is what the owner
 * rejected. The button opens the sheet where those live, and carries a count so an
 * active filter is visible without opening it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterBar(
    filter: ConsoleHistoryFilter,
    onChange: (ConsoleHistoryFilter) -> Unit,
    onOpenSheet: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryRange.entries.forEach { r ->
            FilterChip(
                // A preset reads as unselected while an explicit window is set —
                // otherwise two different time ranges appear active at once.
                selected = !filter.hasExplicitWindow && filter.range == r,
                onClick = { onChange(filter.copy(range = r, fromMs = null, toMs = null)) },
                label = { Text(r.labelHebrew) },
            )
        }
        FilterChip(
            selected = filter.activeCount > 0,
            onClick = onOpenSheet,
            label = {
                Text(if (filter.activeCount > 0) "סינון (${filter.activeCount})" else "סינון")
            },
            leadingIcon = {
                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
    }
}

@Composable
private fun HistoryNotice(text: String, actionLabel: String?, onAction: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = text, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
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

/**
 * What actually happened to a call, in one phrase an agent can act on.
 *
 * FROM VOXIMPLANT'S VERDICT, not from a reason string we composed. "לא נענתה" used
 * to cover four unrelated events and the server could not tell them apart either,
 * because it was reading `answered_at` — a column set when the SCENARIO answered.
 *
 * The four outcomes are separated on facts the platform reports per leg, and the
 * Hebrew says which:
 *   MISSED     agents were rung, nobody picked up   ← the only one to act on
 *   ABANDONED  we answered, the caller gave up      ← nothing was ever offered
 *   REJECTED   never answered at all                ← flood traffic
 *
 * The SIP code refines MISSED only, because that is the case where WHY changes what
 * an agent does: 480 means the app was asleep (ring again, it may wake), 603 means
 * someone actively declined (ringing again is unlikely to help).
 */
private fun callStatusLabel(call: ConsoleCallRecord): Pair<String, Boolean> = when (call.outcome) {
    CallOutcome.ANSWERED -> "נענתה" to true
    // Worded from the OWNER's side of the call, not the platform's. "האפליקציה לא
    // זמינה" read as a fault in the app the owner was holding, when SIP 480 on an
    // agent leg means the opposite: the agent's own phone never rang because it was
    // asleep or signed out.
    CallOutcome.MISSED -> when (call.endCode) {
        480 -> "לא נענתה · הנציג לא היה מחובר" to false
        486 -> "לא נענתה · הנציג היה תפוס" to false
        603 -> "לא נענתה · הנציג דחה" to false
        408 -> "לא נענתה · הנציג לא ענה" to false
        else -> "לא נענתה" to false
    }
    CallOutcome.ABANDONED -> "המתקשר ניתק לפני מענה" to false
    CallOutcome.REJECTED -> "נדחתה במרכזייה" to false
    CallOutcome.FAILED -> "לא התחברה" to false
    // Never invent a verdict. An unknown outcome falls back to the one fact still
    // in hand rather than to a guess dressed as a status.
    CallOutcome.UNKNOWN -> if (call.answered) "נענתה" to true else "לא נענתה" to false
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
    onOpen: (ConsoleCallRecord) -> Unit = {},
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(callHistory, key = { it.id }) { call ->
            ConsoleCallCard(call = call, onDial = { onDial(call) }, onOpen = { onOpen(call) })
        }
    }
}

/**
 * One past call.
 *
 * THE NUMBER IS THE IDENTITY. A name appears above it only when the caller is
 * someone this business actually knows, and it is never a guest's name: a guest
 * belongs to a customer's event, and reading their name off an unrelated invite
 * list is how this card came to greet the owner's own phone as "מבורך קלפה" —
 * a label from a brit that had nothing to do with the call.
 *
 * So the fallback is not a placeholder. For a caller who has never been a customer
 * the number IS their whole identity, and "אורח" would be strictly less information
 * wearing the appearance of more.
 */
@Composable
fun ConsoleCallCard(
    call: ConsoleCallRecord,
    onDial: () -> Unit = {},
    onOpen: () -> Unit = {},
) {
    Card(
        // The whole row opens the detail. A card that shows a SIP outcome and a talk
        // time is a summary of something, and there was no way to reach the something.
        onClick = onOpen,
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
                // LTR island: a phone number reordered by the RTL layout is not the
                // number that was dialled.
                if (call.name != null) {
                    Text(
                        text = call.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = call.phone ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = call.phone ?: "מספר חסוי",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val (statusText, statusGood) = callStatusLabel(call)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        // Colour REINFORCES the word, it does not replace it. The
                        // label alone is readable to someone who cannot tell the two
                        // apart.
                        color = if (statusGood) ColorSuccess else ColorDanger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = buildString {
                            append(" · ")
                            append(formatCallTime(call.startedAt))
                            // TALK time, not session length. The session includes the
                            // disclosure and the hold music, so a call nobody answered
                            // still ran 30 seconds — printing that as a duration reads
                            // as a conversation that never happened.
                            if (call.talkSec > 0) {
                                append(" · ")
                                append(formatDuration(call.talkSec))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        maxLines = 1,
                    )
                }
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

@Preview(showBackground = true)
@Composable
fun HistoryScreenPreview() {
    MyApplicationTheme {
        HistoryScreen(
            callHistory = listOf(
                // Numbers only, both of them. The preview used to carry a guest's real
                // name lifted from an event — the exact defect this screen was rebuilt
                // to remove, sitting in the file as the example to copy.
                ConsoleCallRecord(
                    id = "1",
                    inbound = true,
                    outcome = CallOutcome.ANSWERED,
                    endCode = 200,
                    agentLegsTried = 1,
                    talkSec = 185,
                    name = null,
                    phone = "+972536212562",
                    startedAt = "2026-08-17T16:42:22",
                    durationSec = 201,
                    answered = true,
                    eventId = null,
                    contactId = null,
                ),
                ConsoleCallRecord(
                    id = "2",
                    inbound = true,
                    // A real missed call: two agents rung, the last one's phone was
                    // asleep. This is the row the whole screen exists for.
                    outcome = CallOutcome.MISSED,
                    endCode = 480,
                    agentLegsTried = 2,
                    talkSec = 0,
                    name = null,
                    phone = "+972501234567",
                    startedAt = "2026-08-17T14:10:00",
                    durationSec = 32,
                    answered = false,
                    eventId = null,
                    contactId = null,
                ),
                ConsoleCallRecord(
                    id = "3",
                    inbound = true,
                    // 1,073 of these in the measured week, and they are NOT misses:
                    // the caller heard us and hung up before anyone was rung.
                    outcome = CallOutcome.ABANDONED,
                    endCode = 200,
                    agentLegsTried = 0,
                    talkSec = 0,
                    name = null,
                    phone = "+972521112233",
                    startedAt = "2026-08-17T11:05:00",
                    durationSec = 12,
                    answered = false,
                    eventId = null,
                    contactId = null,
                ),
            ),
        )
    }
}

// The AI-call-attempt card, used by EventDetailScreen. Kept alongside
// ConsoleCallCard above rather than merged with it: the two render different things
// from different tables — this one a `call_attempts` row from console_call_feed (an
// AI RSVP call, no PII by design), that one a `console_calls` row a human took, with
// the caller's real number.
//
// NOTE this card still shows "אורח" and a blank number for every row, because its
// mapper hardcodes both. It lives on the EVENT surface, where an event and its
// guests are the subject and that framing is correct — which is exactly why it was
// wrong on the call log. Fixing its blank number is a separate change there.
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
                        .background(
                            color = if (call.handledBy == "ai") MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
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
