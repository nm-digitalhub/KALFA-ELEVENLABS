package me.kalfa.agentconsole.telephony.presence

import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// shouldResumeFromPersistedState is PresenceForegroundService's fail-closed gate on a
// system-initiated restart (docs/android-presence-and-call-ux.md §1, "System kill
// under memory pressure"): resume ONLY with solid persisted evidence the agent was
// actually on shift, never guess.
class PresenceStateStoreTest {

    @Test
    fun `resumes when the persisted record says shift is active`() {
        val persisted = PersistedPresenceState(
            status = AgentStatus.READY,
            shiftActive = true,
            voxUsername = "agent1",
        )
        assertTrue(shouldResumeFromPersistedState(persisted))
    }

    @Test
    fun `does not resume when the persisted record says shift is inactive`() {
        val persisted = PersistedPresenceState(
            status = AgentStatus.NOT_READY,
            shiftActive = false,
            voxUsername = "agent1",
        )
        assertFalse(shouldResumeFromPersistedState(persisted))
    }

    @Test
    fun `does not resume when there is no persisted record at all`() {
        assertFalse(shouldResumeFromPersistedState(null))
    }

    // Regression guard for the push-registration-failure persistence round-trip
    // (docs/android-presence-and-call-ux.md's "Update 2026-08-14 (later)") — a
    // failure that can't be reconstructed after a process restart is exactly as
    // useless as one that was never persisted at all.
    @Test
    fun `every persistable AppFailure round-trips through its persist name`() {
        val persistable = listOf(
            AppFailure.NetworkUnavailable,
            AppFailure.Unauthorized,
            AppFailure.NotSignedIn,
            AppFailure.Forbidden,
            AppFailure.Unknown,
        )
        persistable.forEach { failure ->
            assertEquals(failure, appFailureFromPersistName(appFailureToPersistName(failure)))
        }
    }

    @Test
    fun `an unrecognized persisted name falls back to Unknown rather than crashing`() {
        assertEquals(AppFailure.Unknown, appFailureFromPersistName("SomeFutureFailureTypeNotYetHandled"))
    }
}
