package me.kalfa.agentconsole.telephony

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs against a REAL SharedPreferences (Robolectric), not a fake, because the whole
 * claim under test is that the record survives — a mock would prove only that the
 * code calls a method.
 *
 * The owner's report was that permission requests "should be saved and not be asked
 * every time". Whether the SYSTEM dialog appears is Android's decision and needs a
 * device; what this app owes is a durable answer to "have we ever asked for this",
 * because without it "never asked" and "permanently denied" are indistinguishable
 * (see [classifyPermission]) and the app re-launches a request that can never show.
 * That part is verifiable here, and this pins it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PermissionRequestLogTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `an unrecorded permission reads as never requested`() {
        assertFalse(PermissionRequestLog.hasEverRequested(context, Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun `a recorded request survives being read back through a fresh accessor`() {
        // The accessor re-opens SharedPreferences by name rather than caching a
        // handle, so this exercises the same path a later process would take.
        PermissionRequestLog.markRequested(context, listOf(Manifest.permission.RECORD_AUDIO))

        assertTrue(PermissionRequestLog.hasEverRequested(context, Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun `permissions are tracked independently, so one denial does not mute the other`() {
        PermissionRequestLog.markRequested(context, listOf(Manifest.permission.RECORD_AUDIO))

        assertTrue(PermissionRequestLog.hasEverRequested(context, Manifest.permission.RECORD_AUDIO))
        assertFalse(PermissionRequestLog.hasEverRequested(context, Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `marking several at once records every one of them`() {
        val both = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        PermissionRequestLog.markRequested(context, both)

        for (p in both) assertTrue(p, PermissionRequestLog.hasEverRequested(context, p))
    }

    @Test
    fun `an empty list is a no-op rather than a crash or a wildcard write`() {
        PermissionRequestLog.markRequested(context, emptyList())

        assertFalse(PermissionRequestLog.hasEverRequested(context, Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun `the stored record drives the classifier away from re-prompting`() {
        // The behaviour the owner actually asked for, end to end: once we have asked
        // and the system reports denied-with-no-rationale, the app must conclude
        // "permanently denied" and stop launching requests, instead of asking again
        // on every cold start.
        PermissionRequestLog.markRequested(context, listOf(Manifest.permission.RECORD_AUDIO))
        val everRequested = PermissionRequestLog.hasEverRequested(context, Manifest.permission.RECORD_AUDIO)

        val state = classifyPermission(
            granted = false,
            shouldShowRationale = false,
            everRequested = everRequested,
        )

        assertEquals(RuntimePermissionState.DeniedPermanently, state)
        assertFalse(state.isRequestable)
    }
}
