package me.kalfa.agentconsole.telephony.vox

import com.voximplant.android.sdk.core.audio.AudioDevice
import com.voximplant.android.sdk.core.audio.AudioDeviceListener
import com.voximplant.android.sdk.core.audio.AudioDeviceManager
import com.voximplant.android.sdk.core.audio.AudioDeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Thin wrapper over the v3 AudioDeviceManager for choosing the in-call audio route
// (earpiece / speaker / bluetooth / wired headset).
//
// NOTE: the SDK owns the audio-manager lifecycle — start()/stop() are internal
// Voximplant API (@RequiresOptIn: "shouldn't be used outside of Voximplant API"),
// so the app must NOT drive them; audio activates automatically with a live call.
// This wrapper only enumerates and selects devices. `selectedAudioDevice` is
// nullable (no device selected before a call).
//
// ─────────────────────────────────────────────────────────────────────────────
// OBSERVABLE ROUTE, ADDED FOR ActiveCallScreen.
//
// The three original methods are one-shot reads, which is not enough to draw an
// honest control: a route picker built on a one-shot read shows whatever was true
// when the screen composed, and the SDK re-routes on its own. Its documented
// behaviour (verified live 2026-08-15,
// `voximplant.com/api/v2/getDoc?fqdn=references.androidsdk3.android.sdk.core.audio.audiodevicemanager`)
// is that during a call "if a new audio device, such as a wired, USB, or Bluetooth
// headset, is connected, the active audio device is switched to the newly connected
// device", and if the active device disconnects it falls back by priority order. So
// the active route changes without the app asking, and a locally-remembered boolean
// would be wrong the moment an agent plugs in a headset.
//
// Hence [observe]: the displayed route comes from `selectedAudioDevice` and from
// `AudioDeviceListener`, never from what [select] was last asked to do. [select] does
// NOT optimistically update anything — the picker moves when the SDK says it moved.
// ─────────────────────────────────────────────────────────────────────────────
class VoxAudioController {

    fun devices(): List<AudioDevice> = AudioDeviceManager.audioDevices

    fun active(): AudioDevice? = AudioDeviceManager.selectedAudioDevice

    fun select(device: AudioDevice) = AudioDeviceManager.selectAudioDevice(device)

    /**
     * What the audio route looks like right now.
     *
     * [active] null is the honest "the SDK has not told us which route is in use" state,
     * and the UI must render it as unavailable rather than inventing a default. Do not
     * collapse this into a boolean: "not on speaker" and "we do not know the route" are
     * different claims and only one of them is ever safe to make.
     *
     * [isKnown] keys off [active] ALONE, and the difference is not academic. The two
     * fields become true at different times: `audioDevices` enumerates what is
     * *available* (on a bare phone, earpiece and speaker, present from the start) while
     * `selectedAudioDevice` is documented as null until routing begins — "audio routing
     * starts automatically when a call or conference is active". An `isKnown` that also
     * accepted a non-empty device list would therefore go true during the window before
     * routing starts, and the picker would draw two enabled chips with NEITHER selected,
     * since nothing matches a null active device. That renders as "you are on no audio
     * route at all" — a state this screen exists to never claim.
     */
    data class Route(
        val devices: List<AudioDevice> = emptyList(),
        val active: AudioDevice? = null,
    ) {
        val isKnown: Boolean get() = active != null
    }

    private val _route = MutableStateFlow(Route())
    val route: StateFlow<Route> = _route.asStateFlow()

    private val listener = object : AudioDeviceListener {
        override fun onAudioDeviceChanged(audioDevice: AudioDevice) = refresh()
        override fun onAudioDeviceListChanged(audioDevices: List<AudioDevice>) = refresh()
    }

    /**
     * Starts reflecting the SDK's audio route in [route], and returns the function that
     * stops it. Registration is paired by the caller — ActiveCallScreen holds it in a
     * `DisposableEffect` so the listener lives exactly as long as the screen does.
     *
     * Every SDK touch is wrapped: `AudioDeviceManager` requires `VICore` to have been
     * initialised (its own docs say so), and this app spent three days and 76 failed
     * ring attempts on a missing `VICore.initialize` — so an audio call that arrives
     * before initialisation must degrade to "route unknown, picker disabled" rather
     * than take down the screen the agent needs in order to hang up.
     */
    fun observe(): () -> Unit {
        runCatching { AudioDeviceManager.addAudioDeviceListener(listener) }
        refresh()
        return { runCatching { AudioDeviceManager.removeAudioDeviceListener(listener) } }
    }

    private fun refresh() {
        _route.value = runCatching {
            Route(
                devices = AudioDeviceManager.audioDevices,
                active = AudioDeviceManager.selectedAudioDevice,
            )
        }.getOrDefault(Route())
    }

    /**
     * Asks the SDK to route audio to [device]. Deliberately returns nothing and updates
     * nothing: `selectAudioDevice` has no callback, so the only truthful confirmation
     * available is the `onAudioDeviceChanged` event that [observe] is listening for.
     */
    fun selectRoute(device: AudioDevice) {
        runCatching { AudioDeviceManager.selectAudioDevice(device) }
    }
}

/**
 * The Hebrew label for an audio device type.
 *
 * Kept next to the controller, and driven off [AudioDeviceType] rather than off
 * `AudioDevice.name` — the SDK's `name` is an arbitrary platform string (an OEM's
 * Bluetooth product name, in whatever language the accessory ships in) and would put
 * untranslated, sometimes unreadable, text into a Hebrew RTL interface.
 *
 * `when` is exhaustive over the five documented types, so a future SDK adding a sixth
 * fails to compile here instead of silently rendering a blank chip.
 */
fun audioDeviceLabelHebrew(type: AudioDeviceType): String = when (type) {
    AudioDeviceType.Earpiece -> "אוזנית הטלפון"
    AudioDeviceType.Speaker -> "רמקול"
    AudioDeviceType.WiredHeadset -> "אוזניות בכבל"
    AudioDeviceType.Usb -> "אוזניות USB"
    AudioDeviceType.Bluetooth -> "בלוטות'"
}
