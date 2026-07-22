package me.kalfa.agentconsole.telephony.vox

import com.voximplant.android.sdk.core.audio.AudioDevice
import com.voximplant.android.sdk.core.audio.AudioDeviceManager

// Thin wrapper over the v3 AudioDeviceManager for choosing the in-call audio route
// (earpiece / speaker / bluetooth / wired headset).
//
// NOTE: the SDK owns the audio-manager lifecycle — start()/stop() are internal
// Voximplant API (@RequiresOptIn: "shouldn't be used outside of Voximplant API"),
// so the app must NOT drive them; audio activates automatically with a live call.
// This wrapper only enumerates and selects devices. `selectedAudioDevice` is
// nullable (no device selected before a call).
class VoxAudioController {

    fun devices(): List<AudioDevice> = AudioDeviceManager.audioDevices

    fun active(): AudioDevice? = AudioDeviceManager.selectedAudioDevice

    fun select(device: AudioDevice) = AudioDeviceManager.selectAudioDevice(device)
}
