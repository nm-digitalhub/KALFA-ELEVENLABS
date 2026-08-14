package me.kalfa.agentconsole.telephony.vox

import me.kalfa.agentconsole.domain.model.CallState
import org.junit.Assert.assertEquals
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

    // planIncomingCallCleanup — the second guard in this file, and the one that stops a
    // finished call from tearing down a DIFFERENT call's resources. The regression to
    // catch is the original unconditional teardown: every one of these fields except
    // clearPendingOffer used to be `true` no matter which leg ended.

    @Test
    fun `the answered call releases the session and the service when nothing is ringing`() {
        val plan = planIncomingCallCleanup(
            endedCallId = "call-1",
            pendingCallId = null,
            answeredCallId = "call-1",
        )
        assertEquals(
            IncomingCallCleanup(
                clearPendingOffer = false,
                cancelRingNotification = false,
                clearAttachedSession = true,
                stopForegroundService = true,
            ),
            plan,
        )
    }

    @Test
    fun `a declined second call must not stop the foreground service the answered call is using`() {
        // Call A answered (owns CallForegroundService + CallEngine.currentSession),
        // call B arrives and is declined. B may cancel its own ring notification and
        // nothing else — stopping the microphone FGS here is how a live call loses its
        // audio, and on API 34+ how the process gets reclaimed mid-call.
        val plan = planIncomingCallCleanup(
            endedCallId = "call-B",
            pendingCallId = "call-B",
            answeredCallId = "call-A",
        )
        assertEquals(
            IncomingCallCleanup(
                clearPendingOffer = true,
                cancelRingNotification = true,
                clearAttachedSession = false,
                stopForegroundService = false,
            ),
            plan,
        )
    }

    @Test
    fun `an answered call ending while another is ringing leaves the ringing one intact`() {
        val plan = planIncomingCallCleanup(
            endedCallId = "call-A",
            pendingCallId = "call-B",
            answeredCallId = "call-A",
        )
        assertEquals(
            IncomingCallCleanup(
                clearPendingOffer = false,
                cancelRingNotification = false,
                clearAttachedSession = true,
                stopForegroundService = false,
            ),
            plan,
        )
    }

    @Test
    fun `a superseded offer that dies later releases nothing`() {
        // Offer A was replaced by offer B before A's own disconnect arrived. A owns
        // nothing any more, so its teardown must be a complete no-op.
        val plan = planIncomingCallCleanup(
            endedCallId = "call-A",
            pendingCallId = "call-B",
            answeredCallId = null,
        )
        assertEquals(
            IncomingCallCleanup(
                clearPendingOffer = false,
                cancelRingNotification = false,
                clearAttachedSession = false,
                stopForegroundService = false,
            ),
            plan,
        )
    }

    @Test
    fun `a declined-before-answer call releases everything it started`() {
        val plan = planIncomingCallCleanup(
            endedCallId = "call-1",
            pendingCallId = "call-1",
            answeredCallId = null,
        )
        assertEquals(
            IncomingCallCleanup(
                clearPendingOffer = true,
                cancelRingNotification = true,
                clearAttachedSession = false,
                stopForegroundService = true,
            ),
            plan,
        )
    }
}
