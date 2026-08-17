package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kalfa.agentconsole.domain.telephony.looksLikePhoneNumber

/**
 * A keypad for calling a number nobody has called us from yet.
 *
 * The last piece of "the console can do what the handset beside it does". Dialling
 * a typed number had no representation at all until `manual` joined dial-intent's
 * union — the rule that forbade it was written for the AI campaign, where an
 * automated system ringing strangers is the whole thing consent law governs, and it
 * was being applied to the owner picking up their own business phone.
 *
 * Even after the server accepted it, the only way to reach it was a button attached
 * to a row that already existed in the log, so a number never called before could
 * still not be dialled. This closes that.
 *
 * The number is NOT normalized here. The server does it with libphonenumber, which
 * is the same helper the rest of the system uses, so 0536212562 and +972536212562
 * are one number everywhere. A second implementation on the device would be a
 * second rule that eventually disagrees with the first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDialSheet(
    onDial: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var number by remember { mutableStateOf("") }
    val canDial = looksLikePhoneNumber(number)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "חיוג למספר",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                // LTR island. A phone number reordered by the RTL layout is not the
                // number being dialled, and this is the one field where the agent is
                // reading digits back to check them.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = number.ifEmpty { "‎" },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        fontSize = 30.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }

                Text(
                    text = "אפשר 0536212562 או +972536212562",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )

                // LTR for the pad itself: 1-2-3 must read left to right even in an
                // RTL screen, the way every phone keypad on earth is laid out.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        KEYPAD.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { key ->
                                    DialKey(key) { number += key }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { number = number.dropLast(1) },
                        enabled = number.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = "מחק ספרה" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null)
                    }
                    Button(
                        onClick = { onDial(number) },
                        // Disabled rather than dialling and failing: the server would
                        // refuse an unparseable number anyway, and a button that
                        // sometimes errors teaches an agent to distrust the one that
                        // works.
                        enabled = canDial,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("חייג")
                    }
                }
            }
        }
    }
}

/** '+' on the 0 key, long-press free: an Israeli number is typed one way or the other. */
private val KEYPAD = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("+", "0", "#"),
)

@Composable
private fun DialKey(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(width = 92.dp, height = 56.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
