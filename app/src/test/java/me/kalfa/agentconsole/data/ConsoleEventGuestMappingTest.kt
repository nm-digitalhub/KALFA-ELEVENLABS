package me.kalfa.agentconsole.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConsoleEventGuestMappingTest {
    @Test fun mapsServerReachAndCallbackFieldsWithoutInferringFromRsvpStatus() {
        val guest = DbConsoleEventGuest(
            guest_id = "g1",
            event_id = "e1",
            rsvp_status = "attending",
            reached_at = "2026-07-22T10:00:00Z",
            callback_scheduled_at = null,
            can_start_outreach_call = false,
            call_block_reason = "already_reached"
        ).toDomain()

        assertEquals("2026-07-22T10:00:00Z", guest.reachedAt)
        assertEquals("already_reached", guest.callBlockReason)
        assertFalse(guest.canStartOutreachCall!!)
    }
}
