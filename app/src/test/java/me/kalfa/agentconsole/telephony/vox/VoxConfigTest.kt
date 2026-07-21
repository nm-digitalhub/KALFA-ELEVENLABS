package me.kalfa.agentconsole.telephony.vox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Guards the #1 silent Voximplant auth failure (design doc): the SDK login takes
// the FULL username "<short>@<application>.<account>.voximplant.com". A wrong suffix
// fails login with no error, so the one place that builds it is pinned here.
class VoxConfigTest {

    @Test
    fun `full username appends the exact application_account suffix`() {
        assertEquals(
            "agent_1bbe74dc@kalfa-rsvp.kalfarsvp.voximplant.com",
            VoxConfig.fullUsername("agent_1bbe74dc"),
        )
    }

    @Test
    fun `full username has exactly one @ and the kalfa-rsvp suffix`() {
        val full = VoxConfig.fullUsername("agent_x")
        assertEquals(1, full.count { it == '@' })
        assertTrue(full.endsWith("@kalfa-rsvp.kalfarsvp.voximplant.com"))
    }
}
