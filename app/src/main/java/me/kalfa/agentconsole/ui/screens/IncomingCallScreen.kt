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
// [number] is the caller's own phone number, and this screen renders it (17.8).
// It used to take [displayName] alone, which made the ring surface the ONE place
// in the app that showed nothing about who was calling — ActiveCallScreen has
// always shown the number, but only AFTER answering, which is one decision too
// late for the person deciding whether to answer. The owner's report was blunt
// about it: "הכי הגיוני שהמספר של הלקוח המחייג יוצג, כמו בכל עסק".
//
// Both values come off the live SDK Call (`remoteDisplayName` / `number`) and are
// guest PII: they may be rendered, never logged. The paired server-side change
// (beta commit 3e455e3) is what makes them worth rendering — until it ships, the
// scenario passes our OWN DID as the ring's callerid and there is no name at all.
@Composable
fun IncomingCallScreen(
    displayName: String,
    number: String,
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
                // Only when it adds something. The server sends the guest's NAME as
                // the display name when it recognises the number and the NUMBER
                // itself when it does not, so for an unrecognised caller the two are
                // the same string — printing it twice would look like a rendering
                // bug, not like extra information.
                //
                // LTR forced on this one Text: a phone number is a left-to-right
                // sequence, and inside this screen's Rtl provider a leading "+" is
                // laid out at the wrong end ("972501234567+"). The digits themselves
                // are neutral-direction, so nothing but an explicit override fixes
                // it. Scoped to this Text so the surrounding Hebrew layout is
                // untouched.
                if (number.isNotBlank() && number != displayName) {
                    Spacer(modifier = Modifier.height(6.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = number,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
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
            number = "+972501234567",
            onAnswer = {},
            onDecline = {},
        )
    }
}
