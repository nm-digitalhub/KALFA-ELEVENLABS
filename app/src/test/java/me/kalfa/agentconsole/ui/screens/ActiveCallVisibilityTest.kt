package me.kalfa.agentconsole.ui.screens

import me.kalfa.agentconsole.domain.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The regression guard for the bug this screen exists to fix: a real answered call
 * published a real `CallSession`, and `MainActivity` still refused to render anything
 * for it because the render was gated on `BuildConfig.DEBUG`.
 *
 * Worth stating why the test is HERE and not on the composable: `tools/verify.sh` runs
 * no Compose UI tests, and its `BuildConfig` stub hardcodes `DEBUG = true` — the very
 * flag that hid the screen. Any local check that went through `BuildConfig` would have
 * passed happily on the broken code. This function is what the decision was extracted
 * into so that a plain JVM test can see it.
 */
class ActiveCallVisibilityTest {

    @Test
    fun `no session shows nothing`() {
        // Every CallState is irrelevant when there is no session to render.
        CallState.values().forEach { state ->
            assertEquals(
                "state=$state",
                ActiveCallVisibility.HIDDEN,
                activeCallVisibility(hasSession = false, state = state),
            )
        }
    }

    @Test
    fun `an answered call that has not connected yet shows the connecting state`() {
        // The real production window: VoxIncomingCallCoordinator.answer() calls
        // attachIncomingSession and clears pendingOffer immediately, before the SDK
        // fires onCallConnected. Without CONNECTING, the ring screen disappears and the
        // dashboard shows through until the leg connects.
        assertEquals(
            ActiveCallVisibility.CONNECTING,
            activeCallVisibility(hasSession = true, state = CallState.RINGING),
        )
    }

    @Test
    fun `a connected call shows the full surface`() {
        assertEquals(
            ActiveCallVisibility.CONNECTED,
            activeCallVisibility(hasSession = true, state = CallState.ACTIVE),
        )
    }

    @Test
    fun `the supervision states also count as connected`() {
        // Unreachable today (monitorCall/takeoverCall are notifyNotWired) but this is
        // what a connected leg looks like when they ship. Defaulting an unrecognised
        // connected state to HIDDEN would recreate the original bug for whoever wires
        // them.
        assertEquals(
            ActiveCallVisibility.CONNECTED,
            activeCallVisibility(hasSession = true, state = CallState.MONITORED),
        )
        assertEquals(
            ActiveCallVisibility.CONNECTED,
            activeCallVisibility(hasSession = true, state = CallState.TAKEN_OVER),
        )
    }

    @Test
    fun `the screen does not outlive the call`() {
        // VoxCallSession.finish() sets DISCONNECTED; VoxIncomingCallCoordinator.cleanUp()
        // clears the session afterwards. In the gap between them the session is still
        // non-null, so DISCONNECTED alone has to be enough to take the screen down —
        // otherwise it sits there over a call that has already ended.
        assertEquals(
            ActiveCallVisibility.HIDDEN,
            activeCallVisibility(hasSession = true, state = CallState.DISCONNECTED),
        )
    }

    @Test
    fun `every call state is covered by exactly one outcome`() {
        // Guards against a future CallState constant silently falling into the
        // else-branch and being treated as a connected call.
        val outcomes = CallState.values().associateWith { activeCallVisibility(true, it) }
        assertEquals(
            mapOf(
                CallState.RINGING to ActiveCallVisibility.CONNECTING,
                CallState.ACTIVE to ActiveCallVisibility.CONNECTED,
                CallState.DISCONNECTED to ActiveCallVisibility.HIDDEN,
                CallState.MONITORED to ActiveCallVisibility.CONNECTED,
                CallState.TAKEN_OVER to ActiveCallVisibility.CONNECTED,
            ),
            outcomes,
        )
    }
}
