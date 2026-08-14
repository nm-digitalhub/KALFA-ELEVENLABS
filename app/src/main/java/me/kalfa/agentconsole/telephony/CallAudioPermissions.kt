package me.kalfa.agentconsole.telephony

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import me.kalfa.agentconsole.ui.message.AppMessageCenter
import me.kalfa.agentconsole.ui.message.MessageSeverity
import me.kalfa.agentconsole.ui.message.UiMessage

// Runtime permissions the live-agent audio path needs before a real Voximplant leg
// can carry two-way voice:
//   • RECORD_AUDIO       — the microphone for a takeover leg (and the SDK audio unit).
//   • POST_NOTIFICATIONS — API 33+, so the ongoing-call foreground-service notification
//                          (CallForegroundService) is actually shown.
//
// The receive-only MONITOR leg also needs the audio unit; its send-isolation is
// enforced SERVER-SIDE (VoxEngine conference topology), never by withholding the mic
// on the device.
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberCallAudioPermissionState(): MultiplePermissionsState {
    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return rememberMultiplePermissionsState(perms)
}

const val CALL_AUDIO_PERMISSION_MESSAGE_ID = "call_audio_permission"

/**
 * Requests the call-audio permissions, and — the part that was missing — notices when
 * requesting can no longer achieve anything.
 *
 * A permission denied twice is permanently denied: per Android's own docs the system
 * dialog never appears again, and `launchMultiplePermissionRequest()` becomes a
 * SILENT no-op — no dialog, no callback that distinguishes it, nothing. The previous
 * version could not see that state at all. Its only guard was a `rememberSaveable`
 * scoped to a screen's lifetime, and after a cold start "never asked" and
 * "permanently denied" read identically from the system (see [classifyPermission]),
 * so it re-launched a request that could never appear and then waited forever.
 *
 * For this app that is not cosmetic: an agent can go "available", be routed a real
 * customer call, and discover there is no microphone only when the call is silent.
 *
 * When the state IS permanent, the response follows Android's guidance rather than
 * fighting it — "Do NOT direct users to system settings or continuously prompt them
 * - respect their choice". So this states, once, exactly which capability is lost,
 * and stops. No settings deep-link, no repeat prompting. The message is not
 * dismissible because the consequence persists, and it clears itself the moment a
 * later read comes back granted (the user can still change it in Settings on their
 * own terms).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun EnsureCallAudioPermission() {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val state = rememberCallAudioPermissionState()

    val granted = state.allPermissionsGranted
    val missing = state.revokedPermissions.map { it.permission }
    // Group-level: accompanist reports true when ANY revoked permission still has a
    // reversible denial, which is exactly the condition for "a request can still
    // show a dialog".
    val rationale = state.shouldShowRationale

    LaunchedEffect(granted, rationale, missing) {
        if (granted) {
            AppMessageCenter.resolve(CALL_AUDIO_PERMISSION_MESSAGE_ID)
            return@LaunchedEffect
        }

        val everRequested = missing.any { PermissionRequestLog.hasEverRequested(context, it) }
        when (classifyPermission(granted = false, shouldShowRationale = rationale, everRequested = everRequested)) {
            RuntimePermissionState.NeverRequested, RuntimePermissionState.DeniedOnce -> {
                // Recorded at LAUNCH, not on the result: a request that produces no
                // dialog produces no result either, so recording on the callback
                // would never record the case this log exists to catch.
                PermissionRequestLog.markRequested(context, missing)
                state.launchMultiplePermissionRequest()
            }

            RuntimePermissionState.DeniedPermanently -> AppMessageCenter.publish(
                UiMessage(
                    id = CALL_AUDIO_PERMISSION_MESSAGE_ID,
                    severity = MessageSeverity.ERROR,
                    title = permanentDenialTitle(missing),
                    body = permanentDenialBody(missing),
                    dismissible = false,
                    deduplicationKey = CALL_AUDIO_PERMISSION_MESSAGE_ID,
                ),
            )

            RuntimePermissionState.Granted -> AppMessageCenter.resolve(CALL_AUDIO_PERMISSION_MESSAGE_ID)
        }
    }
}

// Names the capability that is actually lost, not the permission that is missing —
// an agent needs to know they cannot be heard, not that a string was denied.
internal fun permanentDenialTitle(missing: List<String>): String =
    if (Manifest.permission.RECORD_AUDIO in missing) "אין גישה למיקרופון" else "התראות חסומות"

internal fun permanentDenialBody(missing: List<String>): String {
    val mic = Manifest.permission.RECORD_AUDIO in missing
    val notifications = Manifest.permission.POST_NOTIFICATIONS in missing
    return when {
        mic && notifications ->
            "לא תישמע בשיחות ולא תקבל התראה עליהן. ההרשאות נדחו ולא ניתן לבקש אותן שוב מתוך האפליקציה."
        mic ->
            "שיחה שתענה תהיה חד-צדדית — הצד השני לא ישמע אותך. ההרשאה נדחתה ולא ניתן לבקש אותה שוב מתוך האפליקציה."
        else ->
            "לא תקבל התראה על שיחה נכנסת. ההרשאה נדחתה ולא ניתן לבקש אותה שוב מתוך האפליקציה."
    }
}
