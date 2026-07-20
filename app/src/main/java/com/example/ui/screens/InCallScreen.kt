package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.CallState
import com.example.domain.model.RsvpAnswer
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InCallScreen(
    customerName: String,
    customerPhone: String,
    state: CallState,
    isMuted: Boolean,
    isHeld: Boolean,
    durationSec: Int,
    notes: String,
    rsvpAnswer: RsvpAnswer,
    guestsCount: Int,
    onNotesChange: (String) -> Unit,
    onRsvpAnswerChange: (RsvpAnswer) -> Unit,
    onGuestsCountChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    onHoldToggle: () -> Unit,
    onSendDtmf: (String) -> Unit,
    onHangup: () -> Unit,
    onSubmitRsvpAndHangup: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showKeypad by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Connection Status Indicator
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when (state) {
                                CallState.TAKEN_OVER, CallState.ACTIVE -> ColorSuccess.copy(alpha = 0.15f)
                                CallState.MONITORED -> ColorInfo.copy(alpha = 0.15f)
                                else -> ColorWarning.copy(alpha = 0.15f)
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = when (state) {
                            CallState.RINGING -> "מחייג..."
                            CallState.ACTIVE -> "שיחה פעילה (סוכן)"
                            CallState.MONITORED -> "האזנה שקטה לשיחת AI"
                            CallState.TAKEN_OVER -> "השתלטות סוכן פעילה"
                            CallState.DISCONNECTED -> "מנותק"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = when (state) {
                            CallState.TAKEN_OVER, CallState.ACTIVE -> ColorSuccess
                            CallState.MONITORED -> ColorInfo
                            else -> ColorWarning
                        }
                    )
                }

                // Customer Info Title
                Text(
                    text = customerName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Phone
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = customerPhone,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }

                // Dynamic Call Timer
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = formatTime(durationSec),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        letterSpacing = 1.sp
                    )
                }

                // Actions: Mute / Hold / Keypad Toggle
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallActionButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isMuted) "לא מושתק" else "השתק",
                        active = isMuted,
                        onClick = onMuteToggle,
                        tint = if (isMuted) ColorDanger else MaterialTheme.colorScheme.primary
                    )

                    CallActionButton(
                        icon = if (isHeld) Icons.Default.PlayArrow else Icons.Default.Pause,
                        label = if (isHeld) "המשך" else "המתנה",
                        active = isHeld,
                        onClick = onHoldToggle,
                        tint = if (isHeld) ColorWarning else MaterialTheme.colorScheme.primary
                    )

                    CallActionButton(
                        icon = Icons.Default.Dialpad,
                        label = "לוח מקשים",
                        active = showKeypad,
                        onClick = { showKeypad = !showKeypad }
                    )
                }

                // DTMF Keypad (Animated Drawdown)
                AnimatedVisibility(
                    visible = showKeypad,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    DtmfKeypad(onKeyClick = onSendDtmf)
                }

                Divider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))

                // RSVP Result Sheet Form
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "טופס עדכון אישור הגעה",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // RSVP radio options
                        Text(
                            text = "תשובת האורח:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RsvpAnswer.values().forEach { answer ->
                                val selected = rsvpAnswer == answer
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surface
                                        )
                                        .clickable { onRsvpAnswerChange(answer) }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = answer.labelHebrew,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Guests Counter Stepper (Active only when Attending)
                        AnimatedVisibility(visible = rsvpAnswer == RsvpAnswer.ATTENDING) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "מספר מגיעים כולל:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    IconButton(
                                        onClick = { if (guestsCount > 1) onGuestsCountChange(guestsCount - 1) },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)
                                    ) {
                                        Icon(imageVector = Icons.Default.Remove, contentDescription = null)
                                    }

                                    Text(
                                        text = guestsCount.toString(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )

                                    IconButton(
                                        onClick = { onGuestsCountChange(guestsCount + 1) },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)
                                    ) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                                    }
                                }
                            }
                        }

                        // Notes Field
                        OutlinedTextField(
                            value = notes,
                            onValueChange = onNotesChange,
                            label = { Text("הערות או בקשות מיוחדות") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                    }
                }

                // Final Actions: Red Direct Hangup vs. Purple Save & End
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Direct Hangup (Red)
                    Button(
                        onClick = onHangup,
                        colors = ButtonDefaults.buttonColors(containerColor = ColorDanger),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.CallEnd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("נתק שיחה", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    // Save RSVP & End
                    Button(
                        onClick = onSubmitRsvpAndHangup,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.5f),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("שמור ונתק", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CallActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (active) tint.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    width = 2.dp,
                    color = if (active) tint else Color.Transparent,
                    shape = CircleShape
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) tint else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun DtmfKeypad(onKeyClick: (String) -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(12.dp).height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(keys) { key ->
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { onKeyClick(key) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun InCallScreenPreview() {
    MyApplicationTheme {
        InCallScreen(
            customerName = "ששון מנחם",
            customerPhone = "052-999-8888",
            state = CallState.ACTIVE,
            isMuted = false,
            isHeld = false,
            durationSec = 112,
            notes = "",
            rsvpAnswer = RsvpAnswer.ATTENDING,
            guestsCount = 3,
            onNotesChange = {},
            onRsvpAnswerChange = {},
            onGuestsCountChange = {},
            onMuteToggle = {},
            onHoldToggle = {},
            onSendDtmf = {},
            onHangup = {},
            onSubmitRsvpAndHangup = {}
        )
    }
}
