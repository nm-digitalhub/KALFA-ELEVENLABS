package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Phone
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.telephony.PendingCallback
import me.kalfa.agentconsole.domain.model.RsvpAnswer
import me.kalfa.agentconsole.domain.model.RsvpResult
import me.kalfa.agentconsole.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    agentName: String = "נציג KALFA",
    agentEmail: String = "",
    onLogout: () -> Unit = {},
    agentStatus: AgentStatus,
    activeAiCalls: Int,
    queueDepth: Int,
    rsvpResults: List<RsvpResult>,
    onStatusChange: (AgentStatus) -> Unit,
    onDiagnosticsUnlock: () -> Unit = {},
    // ── Missed calls (17.8) ──────────────────────────────────────────────────
    // Defaulted so previews and any caller that does not wire them still render a
    // working dashboard; an empty list simply hides the card.
    pendingCallbacks: List<PendingCallback> = emptyList(),
    callbacksLoading: Boolean = false,
    callbacksFailed: Boolean = false,
    onRefreshCallbacks: () -> Unit = {},
    onReturnCallback: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Loaded when the dashboard appears and whenever the agent comes back to it —
    // a queue read on a screen that may sit open for an hour is stale within
    // minutes, and the agent's own return is the moment they care.
    LaunchedEffect(Unit) { onRefreshCallbacks() }
    // Ten taps on the agent's own name open "אבחון חי" — the same screen the
    // invisible 28dp long-press hotspot in MainActivity used to open, and which
    // replaced it because nobody could find it. Deliberately the platform's own
    // idiom (Android's "tap the build number seven times"), on a target the agent
    // can actually see.
    //
    // The window matters as much as the count: without it, ten stray taps spread
    // across a shift would eventually open a diagnostic screen mid-call for an
    // agent who never asked for it. Each tap must land within
    // DIAGNOSTIC_TAP_WINDOW_MS of the previous one or the run restarts, so this
    // can only be reached deliberately.
    var diagnosticTapCount by remember { mutableIntStateOf(0) }
    var lastDiagnosticTapMs by remember { mutableLongStateOf(0L) }
    // Aggregate statistics
    val countAttending = rsvpResults.filter { it.answer == RsvpAnswer.ATTENDING }.sumOf { it.guestsCount.coerceAtLeast(1) }
    val countDeclined = rsvpResults.count { it.answer == RsvpAnswer.DECLINED }
    val countMaybe = rsvpResults.count { it.answer == RsvpAnswer.MAYBE }
    val countCallback = rsvpResults.count { it.answer == RsvpAnswer.CALLBACK }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            // Dashboard Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "שלום, $agentName",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            // `indication = null` on purpose: a ripple on the greeting
                            // would advertise a gesture that is not a product feature,
                            // and would change how this line looks for every agent who
                            // never uses it. The screen-reader label DOES name it —
                            // a hidden affordance that is also unreachable without
                            // sight is a different problem from a discreet one.
                            modifier = Modifier
                                .semantics {
                                    contentDescription =
                                        "שלום, $agentName. עשר הקשות פותחות את מסך האבחון."
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    val now = System.currentTimeMillis()
                                    diagnosticTapCount =
                                        if (now - lastDiagnosticTapMs > DIAGNOSTIC_TAP_WINDOW_MS) 1
                                        else diagnosticTapCount + 1
                                    lastDiagnosticTapMs = now
                                    if (diagnosticTapCount >= DIAGNOSTIC_TAPS_REQUIRED) {
                                        diagnosticTapCount = 0
                                        onDiagnosticsUnlock()
                                    }
                                },
                        )
                        Text(
                            text = "לוח בקרה מרכזי",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = onLogout) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "התנתקות",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (agentEmail.isNotEmpty()) {
                            Text(
                                text = agentEmail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // Status Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "סטטוס נוכחות סוכן",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // agentSettable, NOT values(): "בשיחה" is observed, never
                            // declared. POST /api/agents/status validates against
                            // z.enum(['ready','not_ready','dnd']) and answers 400 for
                            // in_call by design, so this loop was rendering a button
                            // whose only possible outcome was a rejected write — and
                            // setStatus applies the new status optimistically before it
                            // asks, so one tap left currentStatus stuck on IN_CALL and
                            // every 30s heartbeat re-sent it and was rejected again.
                            // The server-set status is still SHOWN when it is one the
                            // agent cannot declare — an agent marked "בשיחה" must be able
                            // to see it. It just is not a button.
                            val statusOptions =
                                if (agentStatus.isAgentSettable) AgentStatus.agentSettable
                                else AgentStatus.agentSettable + agentStatus

                            statusOptions.forEach { status ->
                                val isSelected = agentStatus == status
                                val (bg, textCol, icon) = when (status) {
                                    AgentStatus.READY -> Triple(ColorSuccess, Color.White, Icons.Default.CheckCircle)
                                    AgentStatus.NOT_READY -> Triple(ColorDanger, Color.White, Icons.Default.Cancel)
                                    AgentStatus.DND -> Triple(ColorWarning, Color.White, Icons.Default.DoNotDisturbOn)
                                    AgentStatus.IN_CALL -> Triple(ColorInfo, Color.White, Icons.Default.PhoneInTalk)
                                }

                                val resolvedBg = if (isSelected) bg else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                val resolvedTextCol = if (isSelected) textCol else MaterialTheme.colorScheme.onSurfaceVariant

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(resolvedBg)
                                        // No clickable at all rather than a disabled one:
                                        // an unsettable status is a read-out, and a
                                        // disabled button would still announce itself to
                                        // TalkBack as something to press.
                                        .then(
                                            if (status.isAgentSettable) {
                                                Modifier.clickable { onStatusChange(status) }
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSelected) textCol else resolvedTextCol.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = status.labelHebrew,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = resolvedTextCol,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // KPIs Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    KpiCard(
                        title = "שיחות AI פעילות",
                        value = activeAiCalls.toString(),
                        subtitle = "זמן אמת",
                        icon = Icons.Default.SmartToy,
                        color = MaterialTheme.colorScheme.primary,
                        isPrimary = true,
                        modifier = Modifier.weight(1.5f)
                    )
                    KpiCard(
                        title = "עומק תור",
                        value = queueDepth.toString(),
                        subtitle = "שיחות ממתינות",
                        icon = Icons.Default.Group,
                        color = ColorWarning,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Missed calls — the queue of people waiting to be called back.
            //
            // Placed above the RSVP breakdown deliberately: it is the only card here
            // that is a TASK LIST rather than a statistic. Everything else on this
            // screen tells an agent how things are going; this one tells them what to
            // do next, and burying that under three read-outs would be the same
            // mistake as the silent missed-call push.
            //
            // Hidden entirely when the queue is empty and healthy. A card reading
            // "0 שיחות שלא נענו" is a permanent fixture that trains an agent to skip
            // past exactly the region that matters on the day it is not zero.
            if (pendingCallbacks.isNotEmpty() || callbacksFailed) {
                item {
                    MissedCallsCard(
                        callbacks = pendingCallbacks,
                        failed = callbacksFailed,
                        loading = callbacksLoading,
                        onReturnCallback = onReturnCallback,
                        onRetry = onRefreshCallbacks,
                    )
                }
            }

            // RSVP Breakdown
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "תוצאות אישור הגעה היום",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.Default.Event,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            RsvpStatItem(label = "מגיעים (אורחים)", value = countAttending.toString(), color = ColorSuccess)
                            RsvpStatItem(label = "לא מגיעים", value = countDeclined.toString(), color = ColorDanger)
                            RsvpStatItem(label = "אולי", value = countMaybe.toString(), color = ColorWarning)
                            RsvpStatItem(label = "חזרו אליי", value = countCallback.toString(), color = ColorInfo)
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                        val totalGuestsCount = (countAttending + countDeclined + countMaybe + countCallback).coerceAtLeast(1).toFloat()
                        RsvpProgressBar(
                            label = "מגיעים",
                            percentage = countAttending / totalGuestsCount,
                            color = ColorSuccess
                        )
                        RsvpProgressBar(
                            label = "לא מגיעים",
                            percentage = countDeclined / totalGuestsCount,
                            color = ColorDanger
                        )
                        RsvpProgressBar(
                            label = "אולי",
                            percentage = countMaybe / totalGuestsCount,
                            color = ColorWarning
                        )
                    }
                }
            }

            // Deliberately no free-form "dial any number" card here. It used to sit
            // here (a phone + name text pair feeding a disabled "בקרוב" button) but
            // was removed rather than wired: the real backend it would need to call,
            // POST /api/console-calls/dial-intent, only ever accepts a SERVER-VERIFIED
            // dial target — {kind:'callback', id} or {kind:'guest_service', eventId,
            // contactId} — and its own schema comment states the free-typed-number case
            // has "NO representation here on purpose... the union itself is the
            // enforcement of 'no code path exists for scenario ג'" (dial-intent's
            // consent/DNC/quiet-hours gate has nothing to resolve a raw number
            // against). A form that solicits a phone number for a call this platform
            // will never place is worse than no form — see AGENTS.md's "Outbound
            // dialing" section for the full evidence trail before reintroducing this.
        }
    }
}

/**
 * The missed-call queue: who called while nobody answered, and one tap to ring them
 * back.
 *
 * SHOWS THE FULL NUMBER, always, and that is the requirement rather than a detail.
 * Most of these callers have no name on file — someone who has never been a customer
 * still called this business and still deserves a call back — so the number IS the
 * identity here. A masked hint would make the card decorative.
 *
 * The button sends the request's ID, never the number. The server re-reads the phone
 * and runs the full consent chain before it will dial, so a number an agent can read
 * off this screen is not a number the app can dial at will.
 *
 * Nothing here claims the call connected: the snackbar says "מחייג חזרה…" and the
 * active-call screen reports what actually happens, the same division of labour the
 * live-call controls use.
 */
@Composable
private fun MissedCallsCard(
    callbacks: List<PendingCallback>,
    failed: Boolean,
    loading: Boolean,
    onReturnCallback: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "שיחות שלא נענו",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!failed) {
                    Text(
                        text = callbacks.size.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ColorWarning,
                    )
                }
            }

            if (failed) {
                // A failed read must never look like an empty queue — the whole card
                // is hidden when the queue is genuinely empty, so anything shown here
                // is a problem worth naming.
                Text(
                    text = "לא הצלחנו לטעון את רשימת השיחות שלא נענו.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                    Text(text = "נסה שוב")
                }
            } else {
                callbacks.forEach { callback ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = callback.fullName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            // LTR island: a phone number reads left-to-right, and in
                            // this RTL card a leading "+" would otherwise be laid out
                            // at the wrong end.
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = callback.phone,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                        Button(
                            onClick = { onReturnCallback(callback.id) },
                            enabled = !loading,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.semantics {
                                contentDescription = "התקשר חזרה אל ${callback.fullName}"
                            },
                        ) {
                            Icon(imageVector = Icons.Default.Phone, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "התקשר")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KpiCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPrimary) 6.dp else 2.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isPrimary) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = if (isPrimary) Color.White.copy(alpha = 0.15f) else color.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isPrimary) Color.White else color,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (isPrimary) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (isPrimary) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
fun RowScope.RsvpStatItem(
    label: String,
    value: String,
    color: Color
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
    }
}

@Composable
fun RsvpProgressBar(
    label: String,
    percentage: Float,
    color: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Text(
                text = "${(percentage * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percentage.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview() {
    MyApplicationTheme {
        DashboardScreen(
            agentStatus = AgentStatus.READY,
            activeAiCalls = 4,
            queueDepth = 2,
            rsvpResults = listOf(
                RsvpResult("1", "c1", "g1", "אורח 1", RsvpAnswer.ATTENDING, 3, "הכל טוב"),
                RsvpResult("2", "c2", "g2", "אורח 2", RsvpAnswer.DECLINED, 0, "לצערי לא"),
                RsvpResult("3", "c3", "g3", "אורח 3", RsvpAnswer.MAYBE, 1, "אולי"),
                RsvpResult("4", "c4", "g4", "אורח 4", RsvpAnswer.CALLBACK, 0, "בקשת שיחה")
            ),
            onStatusChange = {}
        )
    }
}

// How many taps on the greeting open "אבחון חי", and how long a run may pause
// before it restarts. Ten matches nothing on this screen by accident, and 1.5s is
// comfortably above a fast double-tap while being far below "the agent put the
// phone down and picked it up again".
private const val DIAGNOSTIC_TAPS_REQUIRED = 10
private const val DIAGNOSTIC_TAP_WINDOW_MS = 1_500L
