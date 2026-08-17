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
// WHO IS CALLING — a name and, whenever there is one, a number. Both, always.
//
// The owner's report on 17.8 came in two parts. First: "בעת שיחה נכנסת המספר
// המוצג הוא המספר שלנו ולא המספר טלפון שמחייג" — the screen showed our own DID,
// fixed server-side in beta commit 3e455e3 by putting the caller's CLI on the ring
// leg. Then, after the live call: "השם לא מספיק לדעתי, חובה תמיד להציג את המספר
// ממנו השיחה מתקבלת" — a name alone is not an answer to "who is this", because
// the agent may want to call back, or to recognise a number whose name we got
// wrong. Hence two fields here, not one.
//
// [displayName] is the server's label: the guest's full name when route-inbound
// recognises the caller, their E.164 when it does not, "מספר חסוי" when the CLI
// was withheld. It arrives as the SIP display name.
//
// [displayNumber] is the caller's number, or "" for none, resolved in
// VoxIncomingCallCoordinator.displayNumberFrom from a dedicated SIP header. It is
// NOT the raw `Call.number` — see that function for why trusting the raw value
// would print OUR DID as the caller's on a withheld call.
//
// The equality check below is sound only because both strings come from the same
// server-side value: route-inbound returns `caller_display` and `caller_number`
// from one normalized `normalizedCli`, so for an unrecognised caller they are
// byte-identical and collapse to one line. An earlier draft compared the label
// against the raw platform CLI — one normalized, one not — and would have printed
// "+972501234567" over "972501234567" on every unrecognised call.
@Composable
fun IncomingCallScreen(
    displayName: String,
    displayNumber: String,
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
                // LTR is forced on this one Text. A phone number reads
                // left-to-right, its digits are direction-neutral, and inside this
                // screen's Rtl provider a leading "+" would otherwise be laid out at
                // the wrong end ("972536212562+"). Scoped to the Text so the Hebrew
                // around it is untouched.
                if (displayNumber.isNotBlank() && displayNumber != displayName) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = displayNumber,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        )
                    }
                }
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
        IncomingCallScreen(
            displayName = "ששון מנחם",
            displayNumber = "+972536212562",
            onAnswer = {},
            onDecline = {},
        )
    }
}
