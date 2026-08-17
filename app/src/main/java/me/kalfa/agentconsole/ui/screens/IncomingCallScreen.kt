package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.ui.theme.ColorDanger
import me.kalfa.agentconsole.ui.theme.ColorSuccess
import me.kalfa.agentconsole.ui.theme.MyApplicationTheme

// Minimal, purpose-built ring surface for a still-RINGING inbound offer — Answer/
// Decline only, deliberately NOT InCallScreen (no mute/hold/keypad/RSVP capture).
// See docs/android-presence-and-call-ux.md §3, "Why a (minimal) new screen is added
// here, and InCallScreen is not touched" for the full reasoning. Shown by
// MainActivity as a top-level overlay whenever VoxIncomingCallCoordinator.pendingOffer
// is non-null — including as the content of the full-screen-intent launch on a locked
// device, since Android does not draw its own call UI for a locked-device FSI; the
// activity it launches has to.
//
// WHO IS CALLING — and why [displayName] is the only identity this screen takes.
//
// The owner's report, 17.8: "בעת שיחה נכנסת המספר המוצג הוא המספר שלנו ולא המספר
// טלפון שמחייג… הכי הגיוני שהמספר של הלקוח המחייג יוצג, כמו בכל עסק". That is
// fixed, but SERVER-side (beta commit 3e455e3), not by adding a second field
// here. The VoxEngine scenario now decides one label and sends it as callUser's
// `displayName`: the guest's name when it recognises the caller, their E.164 when
// it does not, and "מספר חסוי" when the CLI was withheld. All three arrive here
// as `remoteDisplayName` and are correct exactly as received.
//
// A first version of this change ALSO rendered `IncomingOffer.number` underneath,
// suppressed when it equalled the label. Reverted before deploy, because that
// comparison is not sound and its failure modes both surface as visible nonsense
// on the ring screen:
//
//   * The two values reach the device through different pipelines — the label is
//     libphonenumber-normalized ("+972501234567"), while the number is the CLI
//     echoed verbatim from the platform (bare digits, by the scenario's own
//     deliberate choice). Byte-equality between a normalized and an
//     un-normalized form does not hold, so an unrecognised caller would print
//     "+972501234567" over "972501234567" — the double-print the suppression
//     existed to prevent.
//   * On a withheld CLI the scenario must still pass SOME callerid and falls back
//     to our own DID, so the screen would render our number underneath
//     "מספר חסוי" — presenting our DID as the withheld caller's, which is a
//     sharper version of the original complaint.
//
// Both are fixable only with knowledge this screen does not have (our own DID,
// the caller's canonical form). The server has both and already produces one
// correct string from them, so the number stays where it is already right:
// ActiveCallScreen, after answering. Revisit once a live call establishes what
// `Call.number` actually contains on a callUser leg — that it mirrors `callerid`
// is INFERRED, never measured, and is the same unverified step this screen would
// have been built on.
@Composable
fun IncomingCallScreen(
    displayName: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "שיחה נכנסת",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = displayName.ifBlank { "אורח" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(64.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                ) {
                    RingActionButton(
                        icon = Icons.Default.CallEnd,
                        label = "דחה",
                        containerColor = ColorDanger,
                        onClick = onDecline,
                    )
                    RingActionButton(
                        icon = Icons.Default.Call,
                        label = "ענה",
                        containerColor = ColorSuccess,
                        onClick = onAnswer,
                    )
                }
            }
        }
    }
}

@Composable
private fun RingActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    containerColor: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = Color.White,
            modifier = Modifier.size(72.dp).background(Color.Transparent, CircleShape),
        ) {
            Icon(imageVector = icon, contentDescription = label)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
fun IncomingCallScreenPreview() {
    MyApplicationTheme {
        IncomingCallScreen(displayName = "ששון מנחם", onAnswer = {}, onDecline = {})
    }
}
