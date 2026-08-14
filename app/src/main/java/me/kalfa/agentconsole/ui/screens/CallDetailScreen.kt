package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.domain.model.Call
import me.kalfa.agentconsole.domain.model.CallAnalysis
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.InlineMessage
import me.kalfa.agentconsole.ui.message.MessageSeverity
import me.kalfa.agentconsole.ui.message.MessageAction
import me.kalfa.agentconsole.ui.message.UiMessage
import me.kalfa.agentconsole.ui.message.toHebrewMessage
import me.kalfa.agentconsole.ui.state.LoadState

private val EVAL_LABELS = mapOf(
    "rsvp_captured" to "תשובת RSVP נקלטה",
    "dnc_honored" to "כיבוד בקשת הסרה",
    "headcount_correct" to "מספר אורחים מדויק",
    "stayed_on_task" to "השיחה נשארה במשימה"
)

@Composable
fun CallDetailScreen(
    call: Call,
    analysisState: LoadState<CallAnalysis?>,
    onBack: () -> Unit,
    onRetryAnalysis: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "חזרה")
            }
            Column {
                Text("פירוט שיחה", style = MaterialTheme.typography.headlineSmall)
                Text(call.eventName, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("סוג", if (call.handledBy == "ai") "שיחת AI" else "שיחת נציג")
                InfoRow("כיוון", if (call.direction == "inbound") "נכנסת" else "יוצאת")
                InfoRow("משך", "${call.durationSec} שניות")
                InfoRow("התחילה", call.startedAt.take(19).replace("T", " "))
            }
        }

        when (analysisState) {
            LoadState.Initial, LoadState.Loading -> Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            is LoadState.Failure -> InlineMessage(
                message = UiMessage(
                    id = "analysis-load-failure",
                    severity = MessageSeverity.ERROR,
                    title = "לא ניתן לטעון את ניתוח השיחה",
                    body = analysisState.failure.toHebrewMessage(FailureContext.ANALYSIS),
                    primaryAction = MessageAction("נסה שוב", "retry_analysis")
                ),
                onAction = { actionId ->
                    if (actionId == "retry_analysis") onRetryAnalysis()
                }
            )
            is LoadState.Content -> {
                val analysis = analysisState.value
                if (analysis == null) {
                    Card {
                        Text(
                            "אין עדיין ניתוח לשיחה זו — הניתוח נוצר אוטומטית בסיום שיחות AI.",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    AnalysisContent(analysis)
                }
            }
        }
    }
}

// ElevenLabs reports each eval criterion as a bare English enum. Only "success"
// and "failure" occur in production (measured 2026-08-14: 80 and 4 occurrences,
// no other value) — but the API documents "unknown" too, so it is mapped rather
// than left to leak. Anything genuinely new is shown raw on purpose: hiding an
// unrecognised result would be worse than showing an English word.
private fun evalResultHebrew(value: String): String = when (value.trim().lowercase()) {
    "success", "true" -> "הושלם"
    "failure", "false" -> "לא הושלם"
    "unknown" -> "לא ידוע"
    else -> value
}

// ElevenLabs does not publish the set of termination_reason values — checked
// against their API reference and a web search on 2026-08-14, and their own help
// article on call failures returns 403 to non-browser clients. These two are the
// only values this account has produced (17 and 4 occurrences respectively).
//
// "Client disconnected: 1006": 1006 is the WebSocket "abnormal closure" code —
// the connection vanished without a close handshake. The "client" is OUR bridge,
// so this says the scenario's socket to ElevenLabs died mid-conversation. It is
// NOT the caller hanging up: in every recorded case ElevenLabs logged a SHORTER
// duration than Voximplant (13s vs 18s, 28s vs 33s), i.e. the AI leg died first
// and the phone call outlived it. The Hebrew deliberately states that without
// claiming a cause we have not established.
private fun terminationReasonHebrew(value: String): String = when {
    value.startsWith("end_call") -> "הסוכן סיים את השיחה"
    value.startsWith("Client disconnected") -> "החיבור לסוכן נותק באמצע השיחה"
    else -> value
}

@Composable
private fun AnalysisContent(analysis: CallAnalysis) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("תוצאת השיחה", style = MaterialTheme.typography.titleMedium)
            InfoRow("הצלחה", when (analysis.callSuccessful) {
                "success" -> "הצליחה"
                "failure" -> "נכשלה"
                else -> analysis.callSuccessful ?: "לא ידוע"
            })
            // score is ALREADY a 0-100 percentage, not a 0-1 fraction: the view
            // console_call_analysis exposes COALESCE(el_call_score, overall_score),
            // and el_call_score measured 50..100 across every row that has one
            // (2026-08-14). The old `it * 100` therefore rendered a real score of
            // 75 as "7500%".
            analysis.score?.let { InfoRow("ציון", "%.0f".format(it) + "%") }
            analysis.rsvpStatus?.let { InfoRow("סטטוס RSVP", it) }
            val guests = (analysis.adults ?: 0) + (analysis.children ?: 0)
            if (guests > 0) InfoRow(
                "אורחים",
                "$guests (מבוגרים: ${analysis.adults ?: 0}, ילדים: ${analysis.children ?: 0})"
            )
            analysis.terminationReason?.let { InfoRow("סיבת סיום", terminationReasonHebrew(it)) }
        }
    }
    if (analysis.evalCriteria.isNotEmpty()) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("קריטריוני איכות", style = MaterialTheme.typography.titleMedium)
                analysis.evalCriteria.forEach { (key, value) ->
                    InfoRow(EVAL_LABELS[key] ?: key, evalResultHebrew(value))
                }
            }
        }
    }
    Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )) {
        Text(
            "תמלול מלא של השיחה יתווסף בשלב הבא (דורש הרחבת צינור ה-webhook).",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
