package me.kalfa.agentconsole.telephony.vox

import com.voximplant.android.sdk.core.audio.AudioDevice
import com.voximplant.android.sdk.core.audio.AudioDeviceManager

// Thin wrapper over the v3 AudioDeviceManager for the in-call audio route
// (earpiece / speaker / bluetooth / wired headset).
//
// start() must run when a leg becomes active and stop() when it ends. The `false`
// argument means the app manages call audio itself — NOT via a self-managed Telecom
// ConnectionService (that path is deferred; see docs/voximplant-sdk-phase.md). If
// ConnectionService is adopted, pass the Telecom Connection to the SDK instead.
class VoxAudioController {

    fun start() = AudioDeviceManager.start(false)

    fun stop() = AudioDeviceManager.stop()

    fun devices(): List<AudioDevice> = AudioDeviceManager.audioDevices

    fun active(): AudioDevice = AudioDeviceManager.selectedAudioDevice

    fun select(device: AudioDevice) = AudioDeviceManager.selectAudioDevice(device)
}
