package me.kalfa.agentconsole.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure — no Android on the classpath. Each case is a row of Android's own state
// table in developer.android.com/training/permissions/requesting.
class RuntimePermissionStateTest {

    @Test
    fun `granted wins regardless of the other two inputs`() {
        for (rationale in listOf(true, false)) {
            for (asked in listOf(true, false)) {
                assertEquals(
                    RuntimePermissionState.Granted,
                    classifyPermission(granted = true, shouldShowRationale = rationale, everRequested = asked),
                )
            }
        }
    }

    @Test
    fun `rationale true means denied once, and is still requestable`() {
        val state = classifyPermission(granted = false, shouldShowRationale = true, everRequested = true)
        assertEquals(RuntimePermissionState.DeniedOnce, state)
        assertTrue(state.isRequestable)
    }

    @Test
    fun `the ambiguous pair is split by our own memory, not by the system`() {
        // The whole reason this function exists: identical system reads, opposite
        // meanings. Getting this backwards either re-prompts forever into a dialog
        // that cannot appear, or declares a fresh install permanently denied.
        val neverAsked = classifyPermission(granted = false, shouldShowRationale = false, everRequested = false)
        val askedBefore = classifyPermission(granted = false, shouldShowRationale = false, everRequested = true)

        assertEquals(RuntimePermissionState.NeverRequested, neverAsked)
        assertEquals(RuntimePermissionState.DeniedPermanently, askedBefore)
    }

    @Test
    fun `only the permanently denied state is unrequestable`() {
        assertTrue(RuntimePermissionState.NeverRequested.isRequestable)
        assertTrue(RuntimePermissionState.DeniedOnce.isRequestable)
        assertFalse(RuntimePermissionState.DeniedPermanently.isRequestable)
        assertFalse(RuntimePermissionState.Granted.isRequestable)
    }

    @Test
    fun `rationale is trusted over stale memory of having asked`() {
        // shouldShowRationale can only be true after a reversible denial, so it
        // settles the state by itself. If a stored flag were ever wrong, the system
        // read is the one to believe.
        assertEquals(
            RuntimePermissionState.DeniedOnce,
            classifyPermission(granted = false, shouldShowRationale = true, everRequested = false),
        )
    }
}

// The Hebrew an agent actually reads when a permission is permanently denied. Names
// the lost capability, not the permission string — "no microphone" is actionable,
// "RECORD_AUDIO denied" is not.
class PermanentDenialTextTest {

    private val MIC = android.Manifest.permission.RECORD_AUDIO
    private val NOTIFS = android.Manifest.permission.POST_NOTIFICATIONS

    @Test
    fun `microphone denial says the agent will not be heard`() {
        assertEquals("אין גישה למיקרופון", permanentDenialTitle(listOf(MIC)))
        assertTrue(permanentDenialBody(listOf(MIC)).contains("לא ישמע אותך"))
    }

    @Test
    fun `notification-only denial does not claim the microphone is missing`() {
        assertEquals("התראות חסומות", permanentDenialTitle(listOf(NOTIFS)))
        assertFalse(permanentDenialBody(listOf(NOTIFS)).contains("מיקרופון"))
    }

    @Test
    fun `both denied names both consequences, and the mic leads`() {
        val body = permanentDenialBody(listOf(MIC, NOTIFS))
        assertEquals("אין גישה למיקרופון", permanentDenialTitle(listOf(MIC, NOTIFS)))
        assertTrue(body.contains("לא תישמע"))
        assertTrue(body.contains("התראה"))
    }

    @Test
    fun `every variant says asking again is not possible, since that is the whole point`() {
        // Android will not show the dialog again; a message that omits this leaves the
        // agent waiting for a prompt that can never come.
        for (missing in listOf(listOf(MIC), listOf(NOTIFS), listOf(MIC, NOTIFS))) {
            assertTrue(permanentDenialBody(missing).contains("לא ניתן לבקש אותה שוב") ||
                permanentDenialBody(missing).contains("לא ניתן לבקש אותן שוב"))
        }
    }
}
