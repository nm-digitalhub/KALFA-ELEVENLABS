package me.kalfa.agentconsole.telephony

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState

// Runtime permissions the live-agent audio path needs before a real Voximplant leg
// can carry two-way voice:
//   • RECORD_AUDIO       — the microphone for a takeover leg (and the SDK audio unit).
//   • POST_NOTIFICATIONS — API 33+, so the ongoing-call foreground-service notification
//                          (CallForegroundService) is actually shown.
//
// The receive-only MONITOR leg also needs the audio unit; its send-isolation is
// enforced SERVER-SIDE (VoxEngine conference topology), never by withholding the mic
// on the device. These are requested at the live-supervision surface so the grant is
// in hand before the telephony-wiring step attaches a leg.
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

// Requests the call-audio permissions once when first shown. No-op in Compose
// previews/inspection (accompanist needs a real Activity). Accompanist short-circuits
// when already granted and does nothing once permanently denied, and the
// rememberSaveable guard keeps it to a single prompt per screen lifetime rather than
// re-prompting on every recomposition.
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun EnsureCallAudioPermission() {
    if (LocalInspectionMode.current) return
    val state = rememberCallAudioPermissionState()
    var requested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!requested && !state.allPermissionsGranted) {
            requested = true
            state.launchMultiplePermissionRequest()
        }
    }
}
