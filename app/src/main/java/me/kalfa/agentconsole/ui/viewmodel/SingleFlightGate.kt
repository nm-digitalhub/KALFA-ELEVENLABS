package me.kalfa.agentconsole.ui.viewmodel

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One outbound dial at a time.
 *
 * MEASURED 18.8: a manual dial takes 6.2-10.1 s wall-clock — `dial-intent` is
 * 3.2-5.2 s of sequential Supabase round trips, and the Voximplant SDK's
 * connect+login is a further 2.1-6.5 s because [VoxClientManager.placeCall] does it
 * lazily. The dialpad closed on the tap, so the agent was left watching a silent
 * list and dialled again. Two dial tokens were minted, two Voximplant sessions ran
 * against the same number (7769476232 / 7769493570), and hanging up ended only the
 * one the UI was tracking — the orphan played hold music to its 120 s cap and the
 * owner reported it as "I pressed hang up and it did not disconnect".
 *
 * ONE gate shared by every dial path rather than a flag per function: the keypad, a
 * history row and a callback return all contend for the same single outbound call,
 * and a flag each would let two of them race.
 *
 * Lives here, free of Android and Compose imports, so the invariant can be tested on
 * a plain JVM — the same separation the telemetry scrub and `planSilentLogin` use.
 * The correctness of this class is not something a code review can see: it is one
 * compare-and-set away from being a check-then-act that both taps pass.
 *
 * The server cannot substitute for this. `countLiveConsoleCalls()` is account-wide
 * with `MANUAL_DIAL_MAX_LIVE_CALLS = 2`, so two calls from one agent is exactly what
 * it permits by design.
 */
class SingleFlightGate {
    private val busy = AtomicBoolean(false)

    /** True if the caller now HOLDS the gate and must [release] it. */
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)

    /**
     * Hand the gate back. Safe to call when not held — a release that races a
     * failure path must not become a second bug.
     */
    fun release() {
        busy.set(false)
    }

    /** For rendering only. Never branch on this to decide whether to act — use [tryAcquire]. */
    val isBusy: Boolean get() = busy.get()
}
