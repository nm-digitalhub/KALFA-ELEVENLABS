package me.kalfa.agentconsole.ui.screens

import me.kalfa.agentconsole.domain.model.CallState

/**
 * Whether the connected-call surface should be on screen, and in which of its two
 * shapes.
 *
 * Pulled out to the top level, away from `MainActivity`, for the same reason as
 * [ManualDialPolicy], `canActOnOffer` and `planIncomingCallCleanup`: it is the single
 * decision this whole change turns on, and inside a composable it is unverifiable here.
 * `tools/verify.sh` runs no Compose UI tests, and — worse for this particular decision —
 * its `BuildConfig` stub hardcodes `DEBUG = true`, which is exactly the flag that hid
 * the screen in the first place. A local check that cannot see a release build is no
 * check at all for this bug, so the decision lives somewhere a JVM test can reach it.
 *
 * THE BUG THIS REPLACES. `MainActivity` rendered the in-call surface under
 * `if (session != null && BuildConfig.DEBUG)`. That gate was written when a session
 * could only ever be a DEBUG mock, and it was left in place when
 * `CallEngine.attachIncomingSession` made a REAL answered call publish a real session
 * (`docs/android-presence-and-call-ux.md` §3). The result was reported from a live call
 * on 2026-08-14: the agent answered, and the app showed him the dashboard — no timer,
 * no controls, no indication a call was in progress at all.
 */
enum class ActiveCallVisibility {
    /** Nothing to show: no session, or the leg is over. */
    HIDDEN,

    /**
     * A session exists but its media is not up yet. Reachable in production: the
     * coordinator's `answer()` calls `attachIncomingSession` and clears `pendingOffer`
     * IMMEDIATELY, before the SDK reports `onCallConnected`. Without a state for this,
     * that window renders the dashboard between the ring screen vanishing and the call
     * screen appearing — a visible flash of the wrong thing at the exact moment the
     * agent has just put the phone to their ear.
     */
    CONNECTING,

    /** Media is up. The full surface, with the duration running. */
    CONNECTED,
}

/**
 * Decides [ActiveCallVisibility] from the two facts the UI actually has.
 *
 * [hasSession] is `CallEngine.currentSession != null`; [state] is that session's
 * `CallState`. Note that the two are cleared at different moments — the session by
 * `VoxIncomingCallCoordinator.cleanUp` and the state by `VoxCallSession.finish` — and
 * `finish()` runs first. Keying HIDDEN off DISCONNECTED as well as off the null session
 * is what stops the screen outliving its call in that gap; the screen must not be able
 * to sit there over a call that has ended.
 */
fun activeCallVisibility(hasSession: Boolean, state: CallState): ActiveCallVisibility = when {
    !hasSession -> ActiveCallVisibility.HIDDEN
    state == CallState.DISCONNECTED -> ActiveCallVisibility.HIDDEN
    state == CallState.RINGING -> ActiveCallVisibility.CONNECTING
    // ACTIVE, and the two supervision states. MONITORED/TAKEN_OVER cannot occur today
    // (ConsoleViewModel.monitorCall/takeoverCall are notifyNotWired), but they are what
    // a connected leg looks like when they ship, and defaulting an unknown connected
    // state to HIDDEN would reproduce this very bug for whoever wires them.
    else -> ActiveCallVisibility.CONNECTED
}
