package me.kalfa.agentconsole.telephony.presence

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Regression guard for the cadence safety-margin arithmetic documented in
// docs/android-presence-and-call-ux.md §1, "Cadence: 30s, not the browser's 60s":
// the server's routing gate is AGENT_STATUS_FRESHNESS_MS = 90_000L
// (beta/src/lib/console/presence.ts, not importable from this repo — mirrored here
// as a literal). If a future change "aligns" HEARTBEAT_INTERVAL_MS back toward the
// browser's 60s cadence without re-deriving the margin, this fails loudly instead of
// silently reopening the exact routability gap this service exists to close.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PresenceCadenceTest {

    // Mirrors beta's AGENT_STATUS_FRESHNESS_MS — see this file's header comment for
    // why it's a literal, not an import.
    private val serverFreshnessGateMs = 90_000L

    @Test
    fun `one missed heartbeat still lands safely inside the server freshness gate`() {
        val oneMissedBeatGapMs = PresenceForegroundService.HEARTBEAT_INTERVAL_MS * 2
        assertTrue(
            "a single missed beat (gap=${oneMissedBeatGapMs}ms) must stay under the " +
                "server's ${serverFreshnessGateMs}ms freshness gate",
            oneMissedBeatGapMs < serverFreshnessGateMs,
        )
    }

    @Test
    fun `cadence is strictly tighter than the browser console's 60s heartbeat`() {
        // Deliberate, not a rounding accident — see docs §1 for why mobile
        // background delivery gets a tighter margin than a foreground browser tab.
        assertTrue(PresenceForegroundService.HEARTBEAT_INTERVAL_MS < 60_000L)
    }
}
