package me.kalfa.agentconsole.telephony.presence

import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.telephony.PresenceSyncState
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.toHebrewMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// contentTextFor is what the persistent presence notification actually shows — the
// ONLY surface a backgrounded agent sees (docs/android-presence-and-call-ux.md §1).
// Pure function, no Android/Robolectric needed, so the priority rules between its
// three orthogonal inputs (status, syncState, pushRegistrationFailure) are directly
// testable — same separation-for-testability reasoning as VoxSilentLogin.kt.
class PresenceNotificationBuilderTest {

    @Test
    fun `a fully synced status with no push problem shows a plain status line`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
        )
        assertEquals("סטטוס: זמין", text)
    }

    @Test
    fun `syncState Failed replaces the status line entirely with the consequence text`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Failed(AppFailure.NotSignedIn),
            pushRegistrationFailure = null,
        )
        assertEquals(AppFailure.NotSignedIn.toHebrewMessageForPresence(), text)
    }

    // The exact scenario this was built for: presence IS confirmed (status write
    // reached the server) but the device never registered for push — the notification
    // must not read as a plain working "זמין" in that case (owner requirement, and
    // the live incident: Voximplant reported "No push notifications has been sent").
    @Test
    fun `a synced status still surfaces a push-registration failure, not a bare status`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = AppFailure.Unknown,
        )
        assertNotEquals("סטטוס: זמין", text)
        assert(text.contains("שיחות") || text.contains("רישום") || text.contains("נכנסות")) {
            "expected the push-registration consequence to be named, got: $text"
        }
    }

    // syncState is the more urgent fact (the agent isn't even confirmed present at
    // all) — it must win when BOTH are wrong, not be silently dropped in favor of the
    // push-registration text.
    @Test
    fun `syncState Failed takes priority over a push-registration failure`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Failed(AppFailure.NetworkUnavailable),
            pushRegistrationFailure = AppFailure.Unknown,
        )
        assertEquals(AppFailure.NetworkUnavailable.toHebrewMessageForPresence(), text)
    }

    @Test
    fun `pending sync state is distinct from both synced and failed`() {
        val pending = PresenceNotificationBuilder.contentTextFor(AgentStatus.DND, PresenceSyncState.Pending, null)
        val synced = PresenceNotificationBuilder.contentTextFor(AgentStatus.DND, PresenceSyncState.Synced, null)
        assertNotEquals(pending, synced)
    }

    private fun AppFailure.toHebrewMessageForPresence() = toHebrewMessage(FailureContext.PRESENCE)
}
