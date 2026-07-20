package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.BuildConfig
import me.kalfa.agentconsole.domain.telephony.TelephonyCapabilities
import me.kalfa.agentconsole.ui.theme.MyApplicationTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemInfoScreen(
    supabaseConfigured: Boolean,
    connectionError: String?,
    capabilities: TelephonyCapabilities,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("מידע ומצב מערכת") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoCard(title = "גרסת אפליקציה") {
                    InfoRow("גרסה", BuildConfig.VERSION_NAME)
                    InfoRow("מספר בנייה", BuildConfig.VERSION_CODE.toString())
                }
            }

            item {
                InfoCard(title = "חיבור נתונים") {
                    StatusRow("סופאבייס", supabaseConfigured, if (supabaseConfigured) "מוגדר" else "מצב הדגמה")
                    StatusRow("חיבור אחרון", connectionError == null, connectionError ?: "תקין")
                }
            }

            item {
                InfoCard(title = "יכולות שיחה") {
                    val modeText = if (capabilities.isSimulation) {
                        "מצב מדומה: אין שמע אמיתי במכשיר"
                    } else {
                        "מנוע שיחות אמיתי מחובר"
                    }
                    StatusRow("מצב מנוע", !capabilities.isSimulation, modeText)
                    StatusRow("שיחה יוצאת", capabilities.outboundCalling)
                    StatusRow("האזנה שקטה", capabilities.silentMonitoring)
                    StatusRow("השתלטות", capabilities.takeover)
                    StatusRow("השתקה", capabilities.mute)
                    StatusRow("המתנה", capabilities.hold)
                    StatusRow("צלילי חיוג", capabilities.dtmf)
                    StatusRow("שיחות נכנסות", capabilities.incomingCalls)
                    StatusRow("ניתוב בלוטות", capabilities.bluetoothRouting)
                    StatusRow("שמירת תוצאת נציג", capabilities.manualRsvpPersistence)
                }
            }

            if (capabilities.isSimulation) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "מסכי השיחה פעילים לצורכי בקרה והדגמה, אך פעולות שמע במכשיר עדיין אינן מחוברות למנוע ווקסאימפלנט אמיתי.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable Column.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusRow(label: String, available: Boolean, detail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = if (available) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (available) Color(0xFF16A36A) else MaterialTheme.colorScheme.error
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SystemInfoScreenPreview() {
    MyApplicationTheme {
        SystemInfoScreen(
            supabaseConfigured = true,
            connectionError = null,
            capabilities = TelephonyCapabilities(),
            onBack = {}
        )
    }
}
