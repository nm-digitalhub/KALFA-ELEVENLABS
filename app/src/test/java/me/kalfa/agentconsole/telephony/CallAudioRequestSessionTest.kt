package me.kalfa.agentconsole.telephony

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the OTHER half of the "have we asked" question — the half that must NOT be
 * durable.
 *
 * [PermissionRequestLog] answers "ever, on this install" and is proven against real
 * storage in [PermissionRequestLogTest]. [CallAudioRequestSession] answers "since this
 * process started", and its whole value is that it is shared across every read in the
 * process and reset by nothing short of process death — a `remember`ed set would be
 * cleared by an Activity recreation (rotation, theme, locale) and let the re-prompt
 * loop back in.
 *
 * Pure: the permission names are compile-time String constants, inlined by the
 * compiler, so no Android runtime is involved (same as [CallAudioPermissionActionTest]).
 */
class CallAudioRequestSessionTest {

    private val MIC = android.Manifest.permission.RECORD_AUDIO
    private val NOTIFS = android.Manifest.permission.POST_NOTIFICATIONS

    // The object outlives individual tests by design, which is exactly what makes it
    // useful in production and leaky in a test run. Cleared on both sides so neither
    // ordering nor a failure part-way through can contaminate a sibling.
    @Before
    fun clearBefore() {
        CallAudioRequestSession.resetForTest()
    }

    @After
    fun clearAfter() {
        CallAudioRequestSession.resetForTest()
    }

    @Test
    fun `an unmarked permission reads as not yet asked in this process`() {
        assertFalse(CallAudioRequestSession.hasRequested(MIC))
    }

    @Test
    fun `permissions are tracked independently, so asking about one does not mute the other`() {
        CallAudioRequestSession.markRequested(listOf(MIC))

        assertTrue(CallAudioRequestSession.hasRequested(MIC))
        assertFalse(CallAudioRequestSession.hasRequested(NOTIFS))
    }

    @Test
    fun `marking several at once records every one of them`() {
        val both = listOf(MIC, NOTIFS)
        CallAudioRequestSession.markRequested(both)

        for (p in both) assertTrue(p, CallAudioRequestSession.hasRequested(p))
    }

    @Test
    fun `the record is shared, not per-reader — the callable reference the composable passes sees it`() {
        // EnsureCallAudioPermission marks the session on one line and hands
        // `CallAudioRequestSession::hasRequested` to the decision on another. If those
        // two ever stopped addressing the same record the loop would return silently,
        // so the exact production wiring is what is asserted here.
        CallAudioRequestSession.markRequested(listOf(MIC))
        val requestedThisProcess: (String) -> Boolean = CallAudioRequestSession::hasRequested

        assertTrue(requestedThisProcess(MIC))
        assertFalse(requestedThisProcess(NOTIFS))
    }

    @Test
    fun `a launch recorded in the session turns the user's denial into silence, not another request`() {
        // End to end over the real object: mark at launch (what the composable does),
        // then re-decide with the reading a denial produces — rationale flipped to
        // true, durable log already written. Must not launch again.
        CallAudioRequestSession.markRequested(listOf(MIC))

        val action = decideCallAudioPermissionAction(
            revoked = listOf(MIC to true),
            everRequested = { true },
            requestedThisProcess = CallAudioRequestSession::hasRequested,
        )

        assertEquals(CallAudioPermissionAction.AwaitNextLaunch(listOf(MIC)), action)
    }
}
