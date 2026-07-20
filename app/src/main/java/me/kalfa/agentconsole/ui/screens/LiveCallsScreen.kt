package me.kalfa.agentconsole.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.SmartToy
import me.kalfa.agentconsole.domain.model.TranscriptLine
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kalfa.agentconsole.domain.model.Call
import me.kalfa.agentconsole.domain.model.CallKind
import me.kalfa.agentconsole.domain.model.CallState
import me.kalfa.agentconsole.domain.model.TranscriptTurn
import me.kalfa.agentconsole.ui.theme.ColorSuccess
import me.kalfa.agentconsole.ui.theme.ColorWarning
import me.kalfa.agentconsole.ui.theme.MyApplicationTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCallsScreen(
    liveTranscripts: Map<String, List<TranscriptLine>> = emptyMap(),
    onWhisper: (String, String) -> Unit = { _, _ -> },
    onMuteAi: (String) -> Unit = {},
    onCloseAi: (String) -> Unit = {},
    liveCalls: List<Call>,
    onMonitor: (String) -> Unit,
    onTakeover: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Screen Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "פיקוח ובקרה",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "שיחות חיות (AI)",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.scalePulse()
                ) {
                    Text(
                        text = "${liveCalls.size} פעילות",
                        modifier = Modifier.padding(6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (liveCalls.isEmpty()) {
                // Empty State
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneCallback,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = "אין שיחות פעילות כרגע",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "כאשר מערכת ה-AI תבצע שיחות, הן יופיעו כאן בזמן אמת ותוכל לפקח עליהן.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(liveCalls, key = { it.id }) { call ->
                        LiveCallCard(
                            call = call,
                            onMonitor = { onMonitor(call.id) },
                            onTakeover = { onTakeover(call.id) },
                            liveLines = liveTranscripts[call.id].orEmpty(),
                            onWhisper = { text -> onWhisper(call.id, text) },
                            onMuteAi = { onMuteAi(call.id) },
                            onCloseAi = { onCloseAi(call.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveCallCard(
    call: Call,
    onMonitor: () -> Unit,
    onTakeover: () -> Unit,
    liveLines: List<TranscriptLine> = emptyList(),
    onWhisper: (String) -> Unit = {},
    onMuteAi: () -> Unit = {},
    onCloseAi: () -> Unit = {}
) {
    var whisperText by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Customer Meta
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (call.state == CallState.RINGING) ColorWarning else ColorSuccess,
                                CircleShape
                            )
                    )
                    Column {
                        Text(
                            text = call.customerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        // Phone number should always be left-to-right
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = call.customerPhone,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Right
                            )
                        }
                    }
                }
                
                // Duration & Event
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = call.eventName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = formatTime(call.durationSec),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Live Transcript Box (if available)
            if (call.transcript.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    val lastTurn = call.transcript.last()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (lastTurn.speaker == "ai") MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (lastTurn.speaker == "ai") "בוט" else "אורח",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (lastTurn.speaker == "ai") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        }
                        
                        Text(
                            text = lastTurn.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── AI management (live captions from Broadcast + whisper + controls) ──
            if (call.handledBy == "ai") {
                if (liveLines.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        liveLines.takeLast(3).forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                Text(
                                    text = if (line.role == "agent") "בוט" else "אורח",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (line.role == "agent") MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = line.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = whisperText,
                        onValueChange = { whisperText = it },
                        placeholder = { Text("הנחיה ל-AI (הלחשה)...", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(
                        onClick = {
                            if (whisperText.isNotBlank()) { onWhisper(whisperText); whisperText = "" }
                        },
                        enabled = whisperText.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "שלח הנחיה", modifier = Modifier.size(18.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(onClick = onMuteAi, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("השתק AI", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = { confirmClose = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("סיים AI", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (confirmClose) {
                    AlertDialog(
                        onDismissRequest = { confirmClose = false },
                        title = { Text("לסיים את סוכן ה-AI?") },
                        text = { Text("ה-AI יתנתק מהשיחה; שיחת הטלפון עצמה תישאר פעילה עד השתלטות או ניתוק.") },
                        confirmButton = {
                            TextButton(onClick = { confirmClose = false; onCloseAi() }) { Text("סיים AI") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmClose = false }) { Text("ביטול") }
                        }
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

            // Supervisor Call-to-Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Monitor button
                OutlinedButton(
                    onClick = onMonitor,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Hearing, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("האזנה שקטה", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }

                // Takeover button
                Button(
                    onClick = onTakeover,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(imageVector = Icons.Default.PhoneForwarded, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("השתלטות סוכן", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun formatTime(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return String.format("%02d:%02d", m, s)
}

@Composable
fun Modifier.scalePulse(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    return this.scale(scale)
}

@Preview(showBackground = true)
@Composable
fun LiveCallsScreenPreview() {
    MyApplicationTheme {
        LiveCallsScreen(
            liveCalls = listOf(
                Call(
                    id = "1",
                    direction = "outbound",
                    kind = CallKind.AI_RSVP,
                    voxSessionId = "vox-1",
                    customerPhone = "052-123-4567",
                    customerName = "יוחנן כהן",
                    eventName = "חתונה של דני ורוני",
                    handledBy = "ai",
                    agentId = null,
                    state = CallState.ACTIVE,
                    startedAt = "עכשיו",
                    answeredAt = "עכשיו",
                    endedAt = null,
                    durationSec = 45,
                    recordingUrl = null,
                    transcript = listOf(
                        TranscriptTurn("ai", "שלום, הגעתי ליוחנן?", "10:00"),
                        TranscriptTurn("customer", "כן, מדבר יוחנן. מי זה?", "10:01")
                    )
                )
            ),
            onMonitor = {},
            onTakeover = {}
        )
    }
}
