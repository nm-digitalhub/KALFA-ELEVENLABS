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
            analysis.score?.let { InfoRow("ציון", "%.0f".format(it * 100) + "%") }
            analysis.rsvpStatus?.let { InfoRow("סטטוס RSVP", it) }
            val guests = (analysis.adults ?: 0) + (analysis.children ?: 0)
            if (guests > 0) InfoRow(
                "אורחים",
                "$guests (מבוגרים: ${analysis.adults ?: 0}, ילדים: ${analysis.children ?: 0})"
            )
            analysis.terminationReason?.let { InfoRow("סיבת סיום", it) }
        }
    }
    if (analysis.evalCriteria.isNotEmpty()) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("קריטריוני איכות", style = MaterialTheme.typography.titleMedium)
                analysis.evalCriteria.forEach { (key, value) ->
                    InfoRow(
                        EVAL_LABELS[key] ?: key,
                        if (value.contains("success") || value == "true") "הושלם" else value
                    )
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
