package me.kalfa.agentconsole.telephony.vox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure — no Android or SDK on the classpath. The gating logic is what has to be right:
// each of the three inputs can be off independently, and getting the AND wrong would
// mean reporting a device as able to ring when it cannot.
class RingCapabilityTest {

    private fun capability(
        notifications: Boolean = true,
        channel: Boolean = true,
        fsi: Boolean = true,
    ) = RingCapability(
        notificationsEnabled = notifications,
        channelAlerting = channel,
        fullScreenIntentAllowed = fsi,
    )

    @Test
    fun `all three granted means the device can ring on a locked screen`() {
        val c = capability()
        assertTrue(c.canAlert)
        assertTrue(c.canRingOnLockedScreen)
    }

    @Test
    fun `missing full-screen intent still alerts but never takes over a locked screen`() {
        // The case this class exists for: setFullScreenIntent silently degrades to a
        // heads-up notification, so the call arrives and a pocketed phone misses it.
        val c = capability(fsi = false)
        assertTrue(c.canAlert)
        assertFalse(c.canRingOnLockedScreen)
    }

    @Test
    fun `notifications off means nothing alerts, full-screen intent is irrelevant`() {
        val c = capability(notifications = false)
        assertFalse(c.canAlert)
        assertFalse(c.canRingOnLockedScreen)
    }

    @Test
    fun `a channel the user set to silent blocks alerting even with notifications on`() {
        val c = capability(channel = false)
        assertFalse(c.canAlert)
        assertFalse(c.canRingOnLockedScreen)
    }

    @Test
    fun `locked-screen ringing requires alerting first, not full-screen intent alone`() {
        // Guards against a refactor that checks fullScreenIntentAllowed on its own and
        // reports a device with notifications disabled as ready to ring.
        assertFalse(capability(notifications = false, channel = false, fsi = true).canRingOnLockedScreen)
    }
}
