package me.kalfa.agentconsole.ui.message

import me.kalfa.agentconsole.domain.error.AppFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    // Regression guard for a measured live incident (2026-08-14): an agent
    // reinstalled the app (clearing the Supabase session, never signed back in) and
    // was told their session had "expired" — factually wrong, and not what the owner
    // asked for ("name the consequence, not the mechanism"). NotSignedIn must read
    // differently from a genuinely expired Unauthorized session, under PRESENCE.
    @Test
    fun notSignedInIsDistinctFromAnExpiredSession() {
        val notSignedIn = AppFailure.NotSignedIn.toHebrewMessage(FailureContext.PRESENCE)
        val expired = AppFailure.Unauthorized.toHebrewMessage(FailureContext.PRESENCE)

        assertNotEquals(notSignedIn, expired)
        assertFalse("must not claim a session expired when there was never one", notSignedIn.contains("פג"))
    }

    // The owner's specific complaint: a generic "הפעולה נכשלה" names the mechanism,
    // not the consequence. Under PRESENCE, the text must say what the agent actually
    // needs to know — that calls may not reach them — not just that a request failed.
    @Test
    fun presenceFailuresNameTheConsequenceNotJustFailure() {
        val networkFailureUnderPresence = AppFailure.NetworkUnavailable.toHebrewMessage(FailureContext.PRESENCE)
        assertTrue(networkFailureUnderPresence.contains("שיחות") || networkFailureUnderPresence.contains("שיחה"))
    }

    // Same requirement for push registration: the consequence is "you will not
    // receive calls while the app is closed", which is materially different from —
    // and more serious than — "registration failed".
    @Test
    fun pushRegistrationFailureNamesTheConsequence() {
        val message = AppFailure.NetworkUnavailable.toHebrewMessage(FailureContext.PUSH_REGISTRATION)
        assertTrue(message.contains("שיחות") || message.contains("שיחה"))
    }

    @Test
    fun presenceAndPushRegistrationContextsDivergeFromTheGenericText() {
        val generic = AppFailure.NetworkUnavailable.toHebrewMessage(FailureContext.GENERAL)
        val presence = AppFailure.NetworkUnavailable.toHebrewMessage(FailureContext.PRESENCE)
        val pushRegistration = AppFailure.NetworkUnavailable.toHebrewMessage(FailureContext.PUSH_REGISTRATION)

        assertNotEquals(generic, presence)
        assertNotEquals(generic, pushRegistration)
        assertNotEquals(presence, pushRegistration)
    }
}
