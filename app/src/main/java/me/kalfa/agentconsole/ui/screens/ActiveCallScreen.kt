package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import me.kalfa.agentconsole.domain.telephony.TransferTarget
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
// those, plus the controls that are real:
//
//  * Duration — from the SDK's own `Call.duration`, not a UI counter (see
//    VoxCallSession.readDurationSec for the drift this fixes).
//  * Mute — `Call.muteAudio`, and the displayed state is re-read from `Call.isMuted`
//    every second rather than assumed from the last tap.
//  * Hold — `Call.hold`, wired 2026-08-17. Was withheld until it could report its own
//    failure (see HoldControl's kdoc): `Call.hold`'s `CallCallback.onFailure` is real
//    per-call feedback, not a fire-and-forget command, and `CallSession.holdRefused`
//    now surfaces it as an honest Hebrew snackbar (`ConsoleViewModel`) instead of a
//    button that silently does nothing. The held STATE is made impossible to miss —
//    `CallStatusPill` replaces "שיחה פעילה" outright while held, it does not just tint
//    an icon — because an agent who forgets a call is on hold is its own malfunction.
//    The music-on-hold this bullet used to call an unaddressed gap SHIPPED on the
//    server side on 2026-08-17: ConsoleInbound/ConsoleDial now play a looped audio
//    file to the customer leg on `CallEvents.OnHold`, so a held guest no longer hears
//    silence. Nothing on this screen changed for it — correctly, since it was never
//    something this screen could fix.
//  * DTMF keypad — `Call.sendDTMF`, wired 2026-08-17 (see DtmfKeypad). It needs no
//    server or scenario support: `Call.handleTones` is documented OFF by default, so
//    tones ride the bridged audio to whatever the customer leg is connected to.
//  * Transfer / consult / conference — the three server-side handoffs. Each POSTs to
//    /api/console-calls/{id}/… and the VoxEngine scenario does the rewiring; the
//    device only asks, and nothing here claims the handoff succeeded (see
//    ConsoleViewModel.runCallAction). Disabled, with the reason stated on screen,
//    when the call carries no console_calls id.
//  * Audio route — `AudioDeviceManager`, with the active device read back from the SDK
//    and its change listener. Renders as UNAVAILABLE, with a reason, whenever the SDK
//    has not told us what the route is; it never guesses a default.
//  * Reconnect banner — `onCallReconnecting`/`onCallReconnected`. Without it a dropped
//    media path is completely invisible and the running timer actively reassures.
//
// WHAT IS DELIBERATELY NOT HERE — each omission is a control that would lie:
//
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
    isHeld: Boolean,
    isReconnecting: Boolean,
    durationSec: Int,
    audioRoute: VoxAudioController.Route,
    onToggleMute: () -> Unit,
    onToggleHold: () -> Unit,
    onSelectAudioDevice: (com.voximplant.android.sdk.core.audio.AudioDevice) -> Unit,
    onHangup: () -> Unit,
    modifier: Modifier = Modifier,
    // ── Live-call handoff + keypad (17.8) ────────────────────────────────────
    // Defaulted so the previews and any caller that does not wire the handoff
    // still compile and render a working call screen. A default of `false` for
    // [handoffAvailable] is the honest one: without a console_calls id the three
    // server-side actions cannot address anything.
    handoffAvailable: Boolean = false,
    transferTargets: List<TransferTarget> = emptyList(),
    transferTargetsLoading: Boolean = false,
    transferTargetsFailed: Boolean = false,
    consultRequested: Boolean = false,
    onSendDtmf: (String) -> Unit = {},
    onLoadTransferTargets: () -> Unit = {},
    onTransfer: (String) -> Unit = {},
    onConsult: (String) -> Unit = {},
    onConference: (String) -> Unit = {},
    onCancelConsult: () -> Unit = {},
    onCompleteConsult: () -> Unit = {},
) {
    val connected = visibility == ActiveCallVisibility.CONNECTED
    var keypadOpen by rememberSaveable { mutableStateOf(false) }
    // Which handoff the open sheet is for; null = closed. One sheet for all three
    // because they differ only in the verb — the list, the empty state and the
    // failure state are identical, and three near-copies would drift.
    var pickerFor by rememberSaveable { mutableStateOf<HandoffKind?>(null) }

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
                    isHeld = isHeld,
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    MuteControl(isMuted = isMuted, enabled = connected, onToggleMute = onToggleMute)
                    HoldControl(isHeld = isHeld, enabled = connected, onToggleHold = onToggleHold)
                }

                Spacer(modifier = Modifier.height(28.dp))

                AudioRoutePicker(
                    route = audioRoute,
                    enabled = connected,
                    onSelect = onSelectAudioDevice,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Every control below acts on a CONNECTED call and nothing else.
                // `connected` already gates mute/hold above for the same reason: a
                // keypad on a ringing leg sends tones nobody receives, and a transfer
                // of a call that has not been answered has nothing to transfer.
                HandoffControls(
                    enabled = connected,
                    handoffAvailable = handoffAvailable,
                    consultRequested = consultRequested,
                    keypadOpen = keypadOpen,
                    onToggleKeypad = { keypadOpen = !keypadOpen },
                    onOpenPicker = { kind ->
                        pickerFor = kind
                        // Loaded on OPEN, never cached across openings: who is free to
                        // take a call changes minute to minute, and a stale name
                        // produces a failure the agent cannot explain.
                        onLoadTransferTargets()
                    },
                    onCancelConsult = onCancelConsult,
                    onCompleteConsult = onCompleteConsult,
                )

                if (keypadOpen) {
                    Spacer(modifier = Modifier.height(16.dp))
                    DtmfKeypad(enabled = connected, onDigit = onSendDtmf)
                }

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

            pickerFor?.let { kind ->
                TransferTargetSheet(
                    kind = kind,
                    targets = transferTargets,
                    loading = transferTargetsLoading,
                    failed = transferTargetsFailed,
                    onPick = { agentId ->
                        // Closed before the request, not after it. These are
                        // fire-and-forget POSTs whose real outcome arrives out of
                        // band (see ConsoleViewModel), so there is nothing to wait
                        // for on this sheet, and leaving it open invites a second tap
                        // that would start a second handoff on the same call.
                        pickerFor = null
                        when (kind) {
                            HandoffKind.TRANSFER -> onTransfer(agentId)
                            HandoffKind.CONSULT -> onConsult(agentId)
                            HandoffKind.CONFERENCE -> onConference(agentId)
                        }
                    },
                    onDismiss = { pickerFor = null },
                )
            }
        }
    }
}

/** Which of the three server-side handoffs an open target picker is for. */
enum class HandoffKind(val title: String, val verb: String) {
    TRANSFER("העברת השיחה", "העבר"),
    CONSULT("התייעצות", "התייעץ"),
    CONFERENCE("צירוף לשיחה", "צרף"),
}

/**
 * Keypad toggle + the three handoff buttons + the consult exits.
 *
 * [handoffAvailable] and [enabled] are SEPARATE gates and both disable, because
 * they are different facts and an agent deserves to know which one applies. A
 * ringing call has nothing to hand over yet; a connected call with no
 * `console_calls` id (an older scenario) has nowhere to send the request. The
 * keypad depends on neither — DTMF goes down the SDK leg, not through the server —
 * which is why it stays usable when the other three are not.
 */
@Composable
private fun HandoffControls(
    enabled: Boolean,
    handoffAvailable: Boolean,
    consultRequested: Boolean,
    keypadOpen: Boolean,
    onToggleKeypad: () -> Unit,
    onOpenPicker: (HandoffKind) -> Unit,
    onCancelConsult: () -> Unit,
    onCompleteConsult: () -> Unit,
) {
    val handoffEnabled = enabled && handoffAvailable

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryCallAction(
                icon = Icons.Default.Dialpad,
                label = "מקשים",
                enabled = enabled,
                selected = keypadOpen,
                onClick = onToggleKeypad,
            )
            SecondaryCallAction(
                icon = Icons.AutoMirrored.Filled.CallMade,
                label = "העבר",
                enabled = handoffEnabled,
                onClick = { onOpenPicker(HandoffKind.TRANSFER) },
            )
            SecondaryCallAction(
                icon = Icons.Default.SupportAgent,
                label = "התייעצות",
                enabled = handoffEnabled,
                onClick = { onOpenPicker(HandoffKind.CONSULT) },
            )
            SecondaryCallAction(
                icon = Icons.Default.GroupAdd,
                label = "ועידה",
                enabled = handoffEnabled,
                onClick = { onOpenPicker(HandoffKind.CONFERENCE) },
            )
        }

        // Only reachable once a consult was requested. Both are offered rather than
        // one: cancelling returns the customer to THIS agent, completing hands them
        // to the colleague — opposite outcomes, and guessing which the agent wants
        // would be the worse failure.
        if (consultRequested) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCancelConsult,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(text = "בטל התייעצות") }
                Button(
                    onClick = onCompleteConsult,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(text = "השלם העברה") }
            }
        }

        if (enabled && !handoffAvailable) {
            Spacer(modifier = Modifier.height(10.dp))
            // Said out loud rather than left as three greyed buttons with no reason.
            Text(
                text = "העברה, התייעצות וועידה אינן זמינות בשיחה הזו.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SecondaryCallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(
            checked = selected,
            onCheckedChange = { onClick() },
            enabled = enabled,
            modifier = Modifier
                .size(56.dp)
                .semantics { contentDescription = label },
        ) {
            Icon(imageVector = icon, contentDescription = null)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.9f else 0.4f),
        )
    }
}

/**
 * A standard 12-key DTMF pad.
 *
 * Fire-and-forget by design, matching the SDK: `Call.sendDTMF` returns Unit and
 * reports nothing back, so there is no success to display and no failure to catch.
 * What the agent gets instead is the digit echoed in the row above the keys — the
 * only honest feedback available is "the app registered your tap".
 *
 * Needs NO server or scenario support: `Call.handleTones` is documented as OFF by
 * default, so tones travel in the bridged audio to whatever is on the other end
 * (an IVR the agent is navigating) untouched by VoxEngine.
 */
@Composable
private fun DtmfKeypad(enabled: Boolean, onDigit: (String) -> Unit) {
    var entered by rememberSaveable { mutableStateOf("") }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // An LTR island: a dialled sequence reads left-to-right regardless of the
        // Hebrew page around it — the same treatment the phone number gets above.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = entered.ifBlank { " " },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.height(32.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        // Explicit LTR for the GRID too: a keypad's layout is universal — 1 is
        // top-left in every locale — and inheriting RTL would mirror it into 3-2-1.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DTMF_ROWS.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { digit ->
                            Button(
                                onClick = {
                                    onDigit(digit)
                                    // Bounded so a long IVR session cannot grow this
                                    // line until it wraps and shifts the keys.
                                    entered = (entered + digit).takeLast(20)
                                },
                                enabled = enabled,
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                modifier = Modifier
                                    .size(width = 68.dp, height = 52.dp)
                                    .semantics { contentDescription = "חייג $digit" },
                            ) {
                                Text(text = digit, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

private val DTMF_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#"),
)

/**
 * The colleague picker behind all three handoffs.
 *
 * Empty, loading and failed are three DIFFERENT screens on purpose. "Nobody is
 * available" is a normal state an agent must be able to act on (wait, or handle the
 * call themselves); a failed request that renders as an empty list tells them the
 * same thing about the world and is simply false.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TransferTargetSheet(
    kind: HandoffKind,
    targets: List<TransferTarget>,
    loading: Boolean,
    failed: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = kind.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(16.dp))

                when {
                    loading -> Text(
                        text = "טוען נציגים זמינים…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    failed -> Text(
                        text = "לא הצלחנו לטעון את רשימת הנציגים. נסו שוב.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    targets.isEmpty() -> Text(
                        text = "אין כרגע נציג זמין לקבל את השיחה.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> targets.forEach { target ->
                        Button(
                            onClick = { onPick(target.agentId) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .semantics {
                                    contentDescription = "${kind.verb} אל ${target.displayName}"
                                },
                        ) {
                            Text(text = target.displayName)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one line that says what is actually happening. The reconnect case OUTRANKS
 * everything below it on purpose: while the media path is down, any other label is
 * the least useful true statement available and reads as reassurance.
 *
 * `isHeld` outranks the call-state labels (MONITORED/TAKEN_OVER/plain "active") but
 * not reconnecting — a held call is still a state the agent chose and can undo by
 * tapping HoldControl again, while a dropped media path is not. Deliberately a
 * distinct LABEL, not a tint on the existing one: "an agent who forgets a call is on
 * hold is its own malfunction" (this file's header) means the held state has to
 * replace "שיחה פעילה" outright, not sit beside it as a detail easy to miss.
 */
@Composable
private fun CallStatusPill(
    visibility: ActiveCallVisibility,
    callState: CallState,
    isHeld: Boolean,
    isReconnecting: Boolean,
) {
    val (label, tint) = when {
        isReconnecting -> "החיבור אבד — מנסים להתחבר מחדש" to ColorDanger
        visibility == ActiveCallVisibility.CONNECTING -> "מתחבר…" to ColorWarning
        isHeld -> "השיחה מוחזקת" to ColorWarning
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
 * `Call.hold` — same toggle-button shape and semantics as [MuteControl], deliberately:
 * an agent already knows how the mute button behaves, and a second control that
 * followed a different interaction pattern would be one more thing to learn mid-call.
 *
 * Held state ALSO renders in [CallStatusPill], not only here — the icon change alone
 * on a small control is easy to miss, and "an agent who forgets a call is on hold is
 * its own malfunction" (this file's header) is the whole reason this control exists
 * at all. A refused hold attempt does not change what this button shows (the SDK's
 * own `isOnHold` — re-polled every second by `VoxCallSession`'s ticker — is the
 * source of truth here, same as [MuteControl]); it is reported once, as a snackbar,
 * by `ConsoleViewModel`'s `CallSession.holdRefused` collector.
 */
@Composable
private fun HoldControl(isHeld: Boolean, enabled: Boolean, onToggleHold: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(
            checked = isHeld,
            onCheckedChange = { onToggleHold() },
            enabled = enabled,
            modifier = Modifier
                .size(72.dp)
                .semantics {
                    contentDescription = if (isHeld) "המשך את השיחה" else "החזק את השיחה"
                },
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                checkedContainerColor = ColorWarning,
                checkedContentColor = Color.White,
            ),
        ) {
            Icon(
                imageVector = if (isHeld) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isHeld) "המשך" else "החזק",
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
            isHeld = false,
            isReconnecting = false,
            durationSec = 112,
            audioRoute = VoxAudioController.Route(),
            onToggleMute = {},
            onToggleHold = {},
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
            isHeld = false,
            isReconnecting = true,
            durationSec = 74,
            audioRoute = VoxAudioController.Route(),
            onToggleMute = {},
            onToggleHold = {},
            onSelectAudioDevice = {},
            onHangup = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ActiveCallScreenHeldPreview() {
    MyApplicationTheme {
        ActiveCallScreen(
            customerName = "ששון מנחם",
            customerPhone = "052-999-8888",
            visibility = ActiveCallVisibility.CONNECTED,
            callState = CallState.ACTIVE,
            isMuted = false,
            isHeld = true,
            isReconnecting = false,
            durationSec = 45,
            audioRoute = VoxAudioController.Route(),
            onToggleMute = {},
            onToggleHold = {},
            onSelectAudioDevice = {},
            onHangup = {},
        )
    }
}
