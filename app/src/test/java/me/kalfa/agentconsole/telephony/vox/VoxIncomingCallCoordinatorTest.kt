package me.kalfa.agentconsole.telephony.vox

import me.kalfa.agentconsole.domain.model.CallState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// canActOnOffer is the guard behind answer()/decline() — a stale notification/UI
// action (offer already disconnected, or a second call replaced it) must be a no-op
// instead of calling Call.answer/reject on a dead or wrong Call. See
// docs/android-presence-and-call-ux.md §3 and VoxIncomingCallCoordinator's kdoc.
class VoxIncomingCallCoordinatorTest {

    @Test
    fun `allows acting on the current offer while still ringing`() {
        assertTrue(canActOnOffer(pendingCallId = "call-1", actionCallId = "call-1", sessionState = CallState.RINGING))
    }

    @Test
    fun `refuses a call id that does not match the current offer`() {
        assertFalse(canActOnOffer(pendingCallId = "call-1", actionCallId = "call-2", sessionState = CallState.RINGING))
    }

    @Test
    fun `refuses when there is no current offer at all`() {
        assertFalse(canActOnOffer(pendingCallId = null, actionCallId = "call-1", sessionState = CallState.RINGING))
    }

    @Test
    fun `refuses once the offer already moved past RINGING`() {
        // The platform gave up (RING_RETRY_WINDOW_MS elapsed) or the leg already
        // connected/disconnected by the time the action arrives — Call.answer is
        // documented to throw CallException in exactly this situation.
        assertFalse(canActOnOffer(pendingCallId = "call-1", actionCallId = "call-1", sessionState = CallState.ACTIVE))
        assertFalse(
            canActOnOffer(pendingCallId = "call-1", actionCallId = "call-1", sessionState = CallState.DISCONNECTED),
        )
    }
}
