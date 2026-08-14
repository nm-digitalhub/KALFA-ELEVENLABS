package me.kalfa.agentconsole.ui.screens

import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.CallDispatchStatus
import me.kalfa.agentconsole.domain.model.EventGuest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ManualDialPolicyTest {
    private fun guest(
        canStart: Boolean? = true,
        reason: String? = null,
        callbackAt: String? = null
    ) = EventGuest(
        guestId = "g1",
        eventId = "e1",
        guestName = "אורח",
        dialable = true,
        phone = "",
        rsvpStatus = null,
        hasActiveCampaign = true,
        callbackScheduledAt = callbackAt,
        canStartOutreachCall = canStart,
        callBlockReason = reason
    )

    private fun dispatch(status: String, reason: String? = null) = CallDispatchStatus(
        dispatchId = "d1",
        eventId = "e1",
        status = status,
        reason = reason,
        callAttemptId = null,
        updatedAt = null
    )

    @Test fun nullableCanStartIsFailClosed() {
        assertFalse(manualDialAvailability(guest(canStart = null), true, null, null).enabled)
        assertTrue(manualDialAvailability(guest(canStart = true), true, null, null).enabled)
    }

    @Test fun alreadyReachedWinsAndNeverRetries() {
        val availability = manualDialAvailability(
            guest(reason = "already_reached", callbackAt = "2026-07-23T10:00:00Z"),
            true,
            null,
            null
        )
        assertTrue(availability.alreadyReached)
        assertFalse(availability.callbackScheduled)
        assertFalse(availability.enabled)
        assertFalse(dispatchPresentation(dispatch("skipped", "already_reached")).retryAllowed)
    }

    @Test fun synchronous409AlreadyReachedAlsoDisablesDial() {
        val availability = manualDialAvailability(guest(), true, AppFailure.AlreadyReached, null)
        assertTrue(availability.alreadyReached)
        assertFalse(availability.enabled)
    }

    @Test fun acceptedWaitsAndUnknownDoesNotInviteRedial() {
        assertFalse(manualDialAvailability(guest(), true, null, dispatch("accepted")).enabled)
        assertFalse(dispatchPresentation(dispatch("unknown", "start_unknown")).retryAllowed)
        assertEquals(
            "לא ניתן לאמת אם השיחה יצאה",
            dispatchPresentation(dispatch("unknown", "start_unknown")).title
        )
    }

    @Test fun onlyTemporaryFinalStatesOfferRetry() {
        assertTrue(dispatchPresentation(dispatch("failed", "temporary_dispatch_failure")).retryAllowed)
        assertTrue(dispatchPresentation(dispatch("skipped", "max_concurrency")).retryAllowed)
        assertFalse(dispatchPresentation(dispatch("blocked", "balance_below_reserve")).retryAllowed)
    }

    @Test fun callbackTimeIsHumanReadableAndNeverAnEnumOrRawIso() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
            assertEquals("23.07.2026 13:00", formatCallbackScheduledAt("2026-07-23T10:00:00Z"))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
