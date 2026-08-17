package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.domain.telephony.CallOutcome
import me.kalfa.agentconsole.domain.telephony.ConsoleCallRecord
import me.kalfa.agentconsole.ui.theme.ColorDanger
import me.kalfa.agentconsole.ui.theme.ColorSuccess

/**
 * What happened on one call, in the detail the list row cannot hold.
 *
 * The list showed an outcome and a talk time and nothing could be tapped — a summary
 * of something with no way to reach the something. Everything here already travels in
 * the row from Voximplant's per-leg record; none of it required a new request.
 *
 * The distinction the sheet exists to make plain is WHY a call was not answered.
 * "לא נענתה" covers three unrelated events — nobody was rung, agents were rung and
 * none picked up, the platform refused the leg outright — and the difference is what
 * decides whether anyone should do something about it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailSheet(
    call: ConsoleCallRecord,
    onDial: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (call.name != null) {
                        Text(
                            text = call.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    // LTR island: an RTL layout reorders a phone number into one that
                    // was never dialled.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = call.phone ?: "מספר חסוי",
                            style = if (call.name != null) {
                                MaterialTheme.typography.bodyLarge
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                            fontWeight = if (call.name != null) FontWeight.Normal else FontWeight.Bold,
                            color = if (call.name != null) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                val (label, good) = outcomeSummary(call)
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (good) ColorSuccess else ColorDanger,
                )

                HorizontalDivider()

                DetailRow("כיוון", if (call.inbound) "שיחה נכנסת" else "שיחה יוצאת")
                DetailRow("מתי", formatFullTime(call.startedAt))
                // SESSION length and TALK time are different numbers and both matter:
                // a call nobody answered still ran 30 seconds of greeting and hold
                // music, and showing only the first describes a conversation that
                // never happened.
                DetailRow("משך השיחה במערכת", formatSeconds(call.durationSec))
                if (call.talkSec > 0) DetailRow("זמן שיחה עם נציג", formatSeconds(call.talkSec))
                if (call.inbound) {
                    DetailRow(
                        "נציגים שצלצלו",
                        if (call.agentLegsTried == 0) "אף אחד" else call.agentLegsTried.toString(),
                    )
                }
                // The platform's own code, shown rather than hidden: it is the fact
                // behind the Hebrew label above, and quoting it is what makes a
                // report about a specific call actionable.
                call.endCode?.let { code ->
                    DetailRow("סיבת סיום", "$code${call.endDetails?.let { " · $it" } ?: ""}")
                }
                if (call.hasRecording) {
                    DetailRow("הקלטה", "קיימת — ניתן להאזין בפאנל הניהול")
                }
                DetailRow("מזהה שיחה", call.id)

                if (call.dialable) {
                    Button(onClick = onDial, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Phone, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("חייג חזרה")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** Fuller than the list's one-liner: here there is room to say what actually happened. */
private fun outcomeSummary(call: ConsoleCallRecord): Pair<String, Boolean> = when (call.outcome) {
    CallOutcome.ANSWERED -> "נענתה על ידי נציג" to true
    CallOutcome.MISSED -> when (call.endCode) {
        480 -> "לא נענתה — הנציג לא היה מחובר" to false
        486 -> "לא נענתה — הנציג היה תפוס" to false
        603 -> "לא נענתה — הנציג דחה את השיחה" to false
        408 -> "לא נענתה — הנציג לא ענה בזמן" to false
        else -> "לא נענתה — צלצלנו ואף נציג לא ענה" to false
    }
    CallOutcome.ABANDONED -> "המתקשר ניתק לפני שהספקנו לצלצל לנציג" to false
    CallOutcome.REJECTED -> "השיחה נדחתה במרכזייה ולא נענתה כלל" to false
    CallOutcome.FAILED -> "השיחה לא התחברה" to false
    CallOutcome.UNKNOWN -> if (call.answered) "נענתה" to true else "לא נענתה" to false
}

/**
 * SimpleDateFormat, not java.time — minSdk 24 with desugaring off, so java.time
 * compiles and then throws NoClassDefFoundError on API 24–25. Same reason as
 * formatCallTime on the list screen.
 */
private fun formatFullTime(isoUtc: String): String {
    if (isoUtc.isBlank()) return "—"
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        // Voximplant already returns Asia/Jerusalem clock time — the query asks for
        // it explicitly — so this is parsed and rendered in the device's own zone
        // rather than shifted a second time.
        val parsed = parser.parse(isoUtc.take(19).replace('T', ' ')) ?: return "—"
        java.text.SimpleDateFormat("d.M.yy HH:mm", java.util.Locale.US).format(parsed)
    } catch (e: Exception) {
        "—"
    }
}

private fun formatSeconds(seconds: Int): String = when {
    seconds <= 0 -> "—"
    seconds < 60 -> "$seconds שניות"
    else -> "%d:%02d דקות".format(seconds / 60, seconds % 60)
}
