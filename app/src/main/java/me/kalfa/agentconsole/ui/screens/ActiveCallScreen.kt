package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kalfa.agentconsole.domain.model.CallState
import me.kalfa.agentconsole.telephony.vox.VoxAudioController
import me.kalfa.agentconsole.telephony.vox.audioDeviceLabelHebrew
import me.kalfa.agentconsole.ui.theme.ColorDanger
import me.kalfa.agentconsole.ui.theme.ColorInfo
import me.kalfa.agentconsole.ui.theme.ColorSuccess
import me.kalfa.agentconsole.ui.theme.ColorWarning
import me.kalfa.agentconsole.ui.theme.MyApplicationTheme

// ─────────────────────────────────────────────────────────────────────────────
// The connected-call surface: what an agent sees while they are on the phone with a
// guest. Shown by MainActivity as a top-level overlay — outside AuthGate, for the same
// reason IncomingCallScreen is (an agent who answered from the notification on a
// push-woken process can open the app into AuthGate's spinner, or its LOGIN FORM on a
// refresh failure, with a live call in their ear).
//
// WHAT IS ON THIS SCREEN, AND WHY ONLY THIS
//
// An agent mid-call needs three things and does not need a fourth: who they are talking
// to, how long they have been talking, and a way to stop. Everything here is one of
// those, plus the two controls that are real:
//
//  * Duration — from the SDK's own `Call.duration`, not a UI counter (see
//    VoxCallSession.readDurationSec for the drift this fixes).
//  * Mute — `Call.muteAudio`, and the displayed state is re-read from `Call.isMuted`
//    every second rather than assumed from the last tap.
//  * Audio route — `AudioDeviceManager`, with the active device read back from the SDK
//    and its change listener. Renders as UNAVAILABLE, with a reason, whenever the SDK
//    has not told us what the route is; it never guesses a default.
//  * Reconnect banner — `onCallReconnecting`/`onCallReconnected`. Without it a dropped
//    media path is completely invisible and the running timer actively reassures.
//
// WHAT IS DELIBERATELY NOT HERE — each omission is a control that would lie:
//
//  * NO DTMF keypad. `Call.sendDTMF` is real SDK API, but nothing on the far end of this
//    leg consumes tones: the agent leg is bridged to the RSVPAgent scenario, which has
//    no DTMF handler. A keypad would send digits into nothing and look like it worked.
//  * NO hold. `Call.hold` is real, but its failure path in VoxCallSession keeps the
//    prior state and reports nothing, so a refused hold is a button that does nothing
//    with no explanation. There is also no music-on-hold on this leg — a held guest
//    hears silence, which on an RSVP call is indistinguishable from a dropped call.
//    Ship it when it can report its own failure and the guest can be told to hold on.
//  * NO RSVP capture form. The old InCallScreen's "שמור ונתק" built an RsvpResult with a
//    FABRICATED guestId and handed it to SupabaseRsvpRepository.saveRsvpResult, which is
//    an intentionally empty body — RSVP outcomes belong to the ElevenLabs client-tools
//    pipeline and the console must never write them (AGENTS.md "Known state" #3). The
//    agent typed an answer, tapped save, and it went nowhere. That is why this screen
//    replaces that one rather than reusing it. The real gap — an agent on a live call
//    has nowhere to record what the guest said — is a product decision, and inventing a
//    write path here would repeat the exact bug.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ActiveCallScreen(
    customerName: String,
    customerPhone: String,
    visibility: ActiveCallVisibility,
    callState: CallState,
    isMuted: Boolean,
    isReconnecting: Boolean,
    durationSec: Int,
    audioRoute: VoxAudioController.Route,
    onToggleMute: () -> Unit,
    onSelectAudioDevice: (com.voximplant.android.sdk.core.audio.AudioDevice) -> Unit,
    onHangup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = visibility == ActiveCallVisibility.CONNECTED

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CallStatusPill(
                    visibility = visibility,
                    callState = callState,
                    isReconnecting = isReconnecting,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = customerName.ifBlank { "אורח" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                if (customerPhone.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    // A phone number is a left-to-right run inside an RTL page; the same
                    // nested-LTR island IncomingCallScreen's sibling surfaces use.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = customerPhone,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                CallDuration(connected = connected, durationSec = durationSec, dimmed = isReconnecting)

                Spacer(modifier = Modifier.height(36.dp))

                MuteControl(isMuted = isMuted, enabled = connected, onToggleMute = onToggleMute)

                Spacer(modifier = Modifier.height(28.dp))

                AudioRoutePicker(
                    route = audioRoute,
                    enabled = connected,
                    onSelect = onSelectAudioDevice,
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Always enabled, in every state this screen can be in. Hanging up is the
                // one thing that must never be unavailable: if the leg is in a state the
                // app has mis-modelled, the agent still has to be able to get out of it.
                Button(
                    onClick = onHangup,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorDanger),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .semantics { contentDescription = "נתק את השיחה" },
                ) {
                    Icon(imageVector = Icons.Default.CallEnd, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "נתק",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * The one line that says what is actually happening. The reconnect case OUTRANKS the
 * call state on purpose: while the media path is down, "שיחה פעילה" is the least useful
 * true statement available and reads as reassurance.
 */
@Composable
private fun CallStatusPill(
    visibility: ActiveCallVisibility,
    callState: CallState,
    isReconnecting: Boolean,
) {
    val (label, tint) = when {
        isReconnecting -> "החיבור אבד — מנסים להתחבר מחדש" to ColorDanger
        visibility == ActiveCallVisibility.CONNECTING -> "מתחבר…" to ColorWarning
        callState == CallState.MONITORED -> "האזנה שקטה" to ColorInfo
        callState == CallState.TAKEN_OVER -> "השתלטות סוכן" to ColorSuccess
        else -> "שיחה פעילה" to ColorSuccess
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * No timer before the media is up. A `00:00` during connect is a claim about elapsed
 * call time that is not yet true, and it is the kind of small lie this screen exists to
 * stop telling.
 */
@Composable
private fun CallDuration(connected: Boolean, durationSec: Int, dimmed: Boolean) {
    if (!connected) {
        Text(
            text = "טרם התחברה",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
        return
    }

    val text = formatTime(durationSec)
    // The digits are an LTR run; the spoken form is not. TalkBack reading "03:12" as
    // digits in an RTL context is at best ambiguous, so the visual keeps its LTR island
    // and the semantics carry a sentence instead.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (dimmed) 0.4f else 1f),
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "משך השיחה ${durationSec / 60} דקות ו-${durationSec % 60} שניות"
            },
        )
    }
}

@Composable
private fun MuteControl(isMuted: Boolean, enabled: Boolean, onToggleMute: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // A real toggle rather than a Box{}.clickable: it carries toggleable semantics
        // and an on/off state TalkBack can announce, and it meets the 48dp touch target
        // without the caller measuring anything.
        FilledIconToggleButton(
            checked = isMuted,
            onCheckedChange = { onToggleMute() },
            enabled = enabled,
            modifier = Modifier
                .size(72.dp)
                .semantics {
                    contentDescription = if (isMuted) "בטל השתקה של המיקרופון" else "השתק את המיקרופון"
                },
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                checkedContainerColor = ColorDanger,
                checkedContentColor = Color.White,
            ),
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isMuted) "מושתק" else "השתק",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.75f else 0.38f),
        )
    }
}

/**
 * The audio route, as the SDK reports it.
 *
 * Renders one chip per device the SDK actually lists, with the active one selected — so
 * a headset plugged in mid-call appears, and a route the SDK changed on its own is
 * reflected without the agent touching anything. When the SDK has told us nothing yet,
 * this says so and disables itself instead of drawing a plausible-looking
 * earpiece/speaker pair that is not backed by anything.
 */
@Composable
private fun AudioRoutePicker(
    route: VoxAudioController.Route,
    enabled: Boolean,
    onSelect: (com.voximplant.android.sdk.core.audio.AudioDevice) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "פלט שמע",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (!route.isKnown || route.devices.isEmpty()) {
            Text(
                text = "נתיב השמע יוצג ברגע שהמערכת תדווח עליו",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
            return@Column
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            route.devices.forEach { device ->
                val label = audioDeviceLabelHebrew(device.type)
                val selected = route.active?.id == device.id
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(device) },
                    enabled = enabled,
                    label = { Text(label) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(),
                    modifier = Modifier.semantics {
                        contentDescription =
                            if (selected) "$label, פלט השמע הנוכחי" else "העבר את השמע ל$label"
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ActiveCallScreenPreview() {
    MyApplicationTheme {
        ActiveCallScreen(
            customerName = "ששון מנחם",
            customerPhone = "052-999-8888",
            visibility = ActiveCallVisibility.CONNECTED,
            callState = CallState.ACTIVE,
            isMuted = false,
            isReconnecting = false,
            durationSec = 112,
            audioRoute = VoxAudioController.Route(),
            onToggleMute = {},
            onSelectAudioDevice = {},
            onHangup = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ActiveCallScreenReconnectingPreview() {
    MyApplicationTheme {
        ActiveCallScreen(
            customerName = "ששון מנחם",
            customerPhone = "052-999-8888",
            visibility = ActiveCallVisibility.CONNECTED,
            callState = CallState.ACTIVE,
            isMuted = true,
            isReconnecting = true,
            durationSec = 74,
            audioRoute = VoxAudioController.Route(),
            onToggleMute = {},
            onSelectAudioDevice = {},
            onHangup = {},
        )
    }
}
