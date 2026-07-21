package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kalfa.agentconsole.domain.model.AgentStatus
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
    onMakeOutboundCall: (phone: String, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var outPhone by remember { mutableStateOf("") }
    var outName by remember { mutableStateOf("") }

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
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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
                            AgentStatus.values().forEach { status ->
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
                                        .clickable { onStatusChange(status) }
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

            // Quick Outbound Dialer
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "חיוג מהיר ללקוח",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        OutlinedTextField(
                            value = outName,
                            onValueChange = { outName = it },
                            label = { Text("שם הלקוח") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = outPhone,
                            onValueChange = { outPhone = it },
                            label = { Text("מספר טלפון") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // App-initiated outbound dialing is DISABLED: there is no
                        // enqueue route yet (the worker owns dispatch), so tapping
                        // this must not open a mock call. Re-enable when
                        // POST /api/events/{id}/outreach-call ships.
                        Button(
                            onClick = {
                                if (outPhone.isNotBlank() && outName.isNotBlank()) {
                                    onMakeOutboundCall(outPhone, outName)
                                    outPhone = ""
                                    outName = ""
                                }
                            },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Call, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("חיוג יזום — בקרוב", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            onStatusChange = {},
            onMakeOutboundCall = { _, _ -> }
        )
    }
}
