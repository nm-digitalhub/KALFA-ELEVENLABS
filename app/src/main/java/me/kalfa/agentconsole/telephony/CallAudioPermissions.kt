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
// PermissionStatus.shouldShowRationale is an EXTENSION property (PermissionsUtil.kt,
// verified against the installed 0.37.3 source), not an interface member — unlike
// MultiplePermissionsState.shouldShowRationale, which resolves without an import
// because it IS a member. Needed here because this file now reads each permission's
// OWN rationale (PermissionState.status.shouldShowRationale) instead of the group's.
import com.google.accompanist.permissions.shouldShowRationale
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
 * What [EnsureCallAudioPermission] should do about its (possibly several) missing
 * permissions, decided PER PERMISSION and then combined — never from one group-level
 * reading.
 *
 * The bug this replaces: this app used to ask `state.shouldShowRationale` — a
 * SINGLE boolean for the whole permission group. Verified against the real installed
 * artifact's source (accompanist 0.37.3, `MutableMultiplePermissionsState.kt`):
 *
 * ```
 * override val shouldShowRationale: Boolean by derivedStateOf {
 *     permissions.any { it.status.shouldShowRationale } &&
 *         permissions.none { !it.status.isGranted && !it.status.shouldShowRationale }
 * }
 * ```
 *
 * That second clause makes it false — group-wide — the instant ANY missing
 * permission's own rationale is false, which is true for BOTH "never asked" and
 * "permanently denied" (see [classifyPermission]'s table). Paired with the old
 * `missing.any { everRequested }` (true the instant ANY missing permission was ever
 * logged as requested, regardless of which one), the two combine to poison the WHOLE
 * group to [RuntimePermissionState.DeniedPermanently] whenever:
 *
 *  - any missing permission looks permanently denied to the system, even if a
 *    sibling permission was never itself requested and its dialog would still
 *    appear, OR
 *  - any missing permission has simply never been asked yet (rationale is false for
 *    "never asked" too), while ANY permission in the group carries a "we asked
 *    before" record — even one that is only `DeniedOnce`, still fully requestable.
 *
 * Either way the group is declared un-requestable and `launchMultiplePermissionRequest()`
 * is never called — for a permission whose system dialog absolutely would still
 * appear. That is exactly the "no permission dialog ever shown" symptom this
 * function exists to stop.
 *
 * The fix: classify each missing permission independently, using ITS OWN
 * `PermissionState.status.shouldShowRationale` (not the group aggregate) and ITS OWN
 * durable "asked before" record ([PermissionRequestLog]). If ANY of them is still
 * requestable, request — `launchMultiplePermissionRequest()` always requests every
 * configured permission together regardless of which subset is passed here (there is
 * no accompanist API to request a subset), and Android itself already handles a
 * mixed batch correctly: it shows a dialog only for the permissions that still can,
 * and silently confirms "denied" for the rest in the same callback. This function
 * only decides WHETHER to call it, never which permissions to include. Only when
 * NOTHING in the group is requestable does this report the permanent-denial banner —
 * and only then is that true for every permission it names.
 */
internal sealed class CallAudioPermissionAction {
    data class Request(val permissions: List<String>) : CallAudioPermissionAction()
    data class ShowPermanentDenial(val permissions: List<String>) : CallAudioPermissionAction()
}

internal fun decideCallAudioPermissionAction(
    // permission -> THAT permission's own shouldShowRationale, never the group's.
    revoked: List<Pair<String, Boolean>>,
    everRequested: (String) -> Boolean,
): CallAudioPermissionAction {
    val classified = revoked.map { (permission, rationale) ->
        permission to classifyPermission(
            granted = false,
            shouldShowRationale = rationale,
            everRequested = everRequested(permission),
        )
    }
    val requestable = classified.filter { (_, state) -> state.isRequestable }.map { it.first }
    return if (requestable.isNotEmpty()) {
        CallAudioPermissionAction.Request(requestable)
    } else {
        CallAudioPermissionAction.ShowPermanentDenial(classified.map { it.first })
    }
}

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
    // Each permission's OWN rationale — see decideCallAudioPermissionAction's kdoc
    // for why the group-level state.shouldShowRationale is the wrong input. Keyed
    // into the effect below as (permission, rationale) pairs, not just permission
    // names, so a denied-once -> denied-permanently transition (same missing set,
    // different rationale) still re-triggers it.
    val revoked = state.revokedPermissions.map { it.permission to it.status.shouldShowRationale }

    LaunchedEffect(granted, revoked) {
        if (granted) {
            AppMessageCenter.resolve(CALL_AUDIO_PERMISSION_MESSAGE_ID)
            return@LaunchedEffect
        }
        if (revoked.isEmpty()) return@LaunchedEffect

        when (
            val action = decideCallAudioPermissionAction(revoked) {
                PermissionRequestLog.hasEverRequested(context, it)
            }
        ) {
            is CallAudioPermissionAction.Request -> {
                // Recorded at LAUNCH, not on the result: a request that produces no
                // dialog produces no result either, so recording on the callback
                // would never record the case this log exists to catch.
                PermissionRequestLog.markRequested(context, action.permissions)
                state.launchMultiplePermissionRequest()
            }

            is CallAudioPermissionAction.ShowPermanentDenial -> AppMessageCenter.publish(
                UiMessage(
                    id = CALL_AUDIO_PERMISSION_MESSAGE_ID,
                    severity = MessageSeverity.ERROR,
                    title = permanentDenialTitle(action.permissions),
                    body = permanentDenialBody(action.permissions),
                    dismissible = false,
                    deduplicationKey = CALL_AUDIO_PERMISSION_MESSAGE_ID,
                ),
            )
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
