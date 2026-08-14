package me.kalfa.agentconsole.telephony.presence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// Regression guard for the defect that made PresenceForegroundService's documented
// START_STICKY resume path (docs/android-presence-and-call-ux.md §1, "System kill under
// memory pressure") unreachable: the shift watcher used a plain `filter { !it }` over
// AgentPresence.shiftActive, which is a StateFlow initialised to false, and a StateFlow
// replays its current value to every new collector. On a system-restarted process the
// watcher's first emission was therefore the process-start default rather than a shift
// ending, and the service stopped itself — wiping the persisted record it was in the
// middle of resuming from. Pure flow logic, so it is asserted directly with no Android,
// DataStore or Voximplant SDK on the classpath.
class PresenceShiftWatcherTest {

    @Test
    fun `ignores the false a fresh collector is replayed on a cold process`() = runTest {
        // Exactly AgentPresence.shiftActive after process death:
        // SupabaseImplementations.kt, `_shiftActive = MutableStateFlow(false)`.
        val shiftActive = MutableStateFlow(false)
        val shiftEnded = mutableListOf<Boolean>()
        backgroundScope.launch {
            shiftActive.shiftEndedAfterBeingActive().collect { shiftEnded += it }
        }

        runCurrent()
        // The old `filter { !it }` fired here, and stopped the service.
        assertEquals(emptyList<Boolean>(), shiftEnded)

        shiftActive.value = true
        runCurrent()
        assertEquals(emptyList<Boolean>(), shiftEnded)
    }

    @Test
    fun `emits when a shift that really was active ends`() = runTest {
        val shiftActive = MutableStateFlow(false)
        val shiftEnded = mutableListOf<Boolean>()
        backgroundScope.launch {
            shiftActive.shiftEndedAfterBeingActive().collect { shiftEnded += it }
        }

        shiftActive.value = true
        runCurrent()
        shiftActive.value = false
        runCurrent()

        assertEquals(listOf(false), shiftEnded)
    }

    @Test
    fun `only the falses after the first true count`() = runTest {
        assertEquals(
            listOf(false, false),
            flowOf(false, false, true, false, true, false).shiftEndedAfterBeingActive().toList(),
        )
    }
}
