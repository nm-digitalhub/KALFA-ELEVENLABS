package me.kalfa.agentconsole.ui.message

import me.kalfa.agentconsole.domain.error.AppFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FailureMessagesTest {
    @Test
    fun conflictMessageIsScopedToGuestCall() {
        assertEquals(
            "לא ניתן לחייג לאורח לפי כללי הקמפיין.",
            AppFailure.Conflict.toHebrewMessage(FailureContext.GUEST_CALL)
        )
    }

    @Test
    fun userMessageDoesNotExposeTransportDetails() {
        val message = AppFailure.Unknown.toHebrewMessage(FailureContext.GENERAL)

        assertFalse(message.contains("HTTP", ignoreCase = true))
        assertFalse(message.contains("exception", ignoreCase = true))
        assertFalse(message.contains("api/", ignoreCase = true))
    }

    @Test
    fun alreadyReachedUsesTheFixedDomainMessage() {
        assertEquals(
            "כבר נוצר קשר באירוע זה.",
            AppFailure.AlreadyReached.toHebrewMessage(FailureContext.GUEST_CALL)
        )
    }
}
