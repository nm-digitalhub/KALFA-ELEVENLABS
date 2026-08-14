package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import me.kalfa.agentconsole.telemetry.DeviceTelemetry
import me.kalfa.agentconsole.telemetry.Telemetry
import me.kalfa.agentconsole.telemetry.TelemetryUploader
import me.kalfa.agentconsole.ui.theme.MyApplicationTheme

// "אבחון חי" — the on-device view of the same lines the owner tails over SSH.
//
// It exists because the upload half cannot be trusted on the one path being
// diagnosed: a push-woken cold start may have no Supabase JWT yet, may be killed
// before a fire-and-forget coroutine runs, and may have no usable network. So the
// device writes its own local file first (see TelemetryLogFile's kdoc) and this
// screen reads THAT — which means the diagnostic is complete and readable even if
// zero lines ever reached the server.
//
// Reachable in RELEASE builds, deliberately. CI stopped publishing a debug APK in
// b5a11f4, so the build on the owner's phone is the release one; a
// BuildConfig.DEBUG gate here would put the screen on the one variant he does not
// install. It is kept off the navigation suite instead — a long-press hotspot in
// MainActivity — so it is not a product surface, and both telemetry switches
// below are OFF until he turns them on.

@Composable
fun DebugLiveScreen(modifier: Modifier = Modifier) {
    val telemetry = remember { Telemetry.instance }
    if (telemetry == null) {
        UnavailableState(modifier)
        return
    }
    DebugLiveContent(telemetry, modifier)
}

@Composable
private fun DebugLiveContent(telemetry: DeviceTelemetry, modifier: Modifier) {
    val lines by telemetry.lines.collectAsState()

    var enabled by remember { mutableStateOf(telemetry.enabled) }
    var uploading by remember { mutableStateOf(telemetry.uploadEnabled) }

    // The upload counters and the file size are plain reads rather than flows —
    // they change on a background thread with no observer, and a 1s poll while
    // this screen is open is cheaper than making every counter a StateFlow.
    var status by remember { mutableStateOf(telemetry.uploadStatus()) }
    var logBytes by remember { mutableStateOf(telemetry.logSizeBytes()) }
    var writeDrops by remember { mutableStateOf(telemetry.writeDrops()) }
    LaunchedEffect(Unit) {
        while (true) {
            status = telemetry.uploadStatus()
            logBytes = telemetry.logSizeBytes()
            writeDrops = telemetry.writeDrops()
            delay(1_000)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "אבחון חי",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                SwitchRow(
                    label = "רישום אירועים במכשיר",
                    checked = enabled,
                    onCheckedChange = {
                        telemetry.enabled = it
                        enabled = it
                    },
                )
                SwitchRow(
                    label = "שידור לשרת",
                    checked = uploading,
                    enabledControl = enabled,
                    onCheckedChange = {
                        telemetry.uploadEnabled = it
                        uploading = it
                    },
                )
                Text(
                    text = statusSummary(telemetry, status, logBytes, writeDrops, lines.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { telemetry.requestUploadNow() }) { Text("שלח עכשיו") }
                    OutlinedButton(onClick = { telemetry.refreshFromFile() }) { Text("רענן מהקובץ") }
                    OutlinedButton(onClick = { telemetry.clear() }) { Text("נקה") }
                }
            }
        }

        // The lines themselves are ASCII and column-aligned, so they are rendered
        // LTR inside this RTL app — an RTL rendering would reorder `k=v` pairs and
        // move the timestamp to the wrong edge, making the on-phone view disagree
        // with the SSH view of the identical text. Monospace for the same reason:
        // the whole value of the format is that the prefix of every line lines up.
        //
        // reverseLayout with a reversed list gives `tail -f` behaviour — newest at
        // the bottom, staying anchored there as lines arrive.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                reverseLayout = true,
            ) {
                items(lines.asReversed()) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

private fun statusSummary(
    telemetry: DeviceTelemetry,
    status: TelemetryUploader.Status,
    logBytes: Long,
    writeDrops: Long,
    lineCount: Int,
): String {
    val kb = logBytes / 1024
    val outcome = status.lastOutcome ?: "—"
    // writeDrops (queue overflow, never recorded at all) and status.dropped
    // (recorded locally, never reached the server) look identical in the server
    // log and have completely different remedies, so they are never merged here.
    return buildString {
        append("תהליך ${telemetry.processId} · מזהה נוכחי ${telemetry.currentSessionId}")
        append("\nשורות בזיכרון $lineCount · קובץ ${kb}KB · נמחקו לפני כתיבה $writeDrops")
        append("\nממתינות לשליחה ${status.queued} · נשלחו ${status.sent} · אבדו ${status.dropped}")
        append("\nתוצאה אחרונה: $outcome")
        if (status.serverDisabled) append(" (הערוץ כבוי בשרת)")
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabledControl: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabledControl)
    }
}

@Composable
private fun UnavailableState(modifier: Modifier) {
    // Reachable in a fresh checkout with no Supabase configuration, where
    // DependencyContainer never builds the HTTP client telemetry is created
    // alongside. An honest empty state rather than a blank screen.
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "האבחון אינו זמין",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "המכשיר לא סיים אתחול, או שהאפליקציה לא מוגדרת מול השרת.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true, locale = "he")
@Composable
private fun DebugLiveScreenPreview() {
    // Previews cannot construct a DeviceTelemetry (it starts a thread and needs a
    // Context), so this exercises the line list — the part with layout risk.
    MyApplicationTheme {
        val sample = remember {
            MutableStateFlow(
                listOf(
                    "2026-08-15T04:12:31.010Z sid=p1a2b3c4 seq=1 app.attach via=fcm",
                    "2026-08-15T04:12:31.012Z sid=p1a2b3c4 seq=2 fcm.service_created",
                    "2026-08-15T04:12:31.180Z sid=c7f3a91b seq=4 fcm.message_received vox=true keys=3",
                    "2026-08-15T04:12:33.402Z sid=c7f3a91b seq=9 vox.login_ok plan=access",
                    "2026-08-15T04:12:34.900Z sid=c7f3a91b seq=13 fcm.wake_done ms=3720 timedout=false incoming=false",
                ),
            )
        }
        val lines: State<List<String>> = sample.collectAsState()
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(lines.value) { line ->
                    Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
    }
}
