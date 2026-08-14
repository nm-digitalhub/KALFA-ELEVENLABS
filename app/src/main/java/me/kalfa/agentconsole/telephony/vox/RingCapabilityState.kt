package me.kalfa.agentconsole.telephony.vox

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// In-process reactive mirror of RingCapabilityChecker.check() — there is no OS
// callback for "notification/full-screen-intent settings changed", so this is
// refreshed on a cadence (PresenceForegroundService: once at startup, then every
// heartbeat tick) rather than pushed. Plain global object, same pattern as
// AppMessageCenter/PushRegistrationState. null until the first check runs.
object RingCapabilityState {
    private val _current = MutableStateFlow<RingCapability?>(null)
    val current: StateFlow<RingCapability?> = _current.asStateFlow()

    fun refresh(context: Context): RingCapability {
        val capability = RingCapabilityChecker.check(context)
        _current.value = capability
        return capability
    }
}
