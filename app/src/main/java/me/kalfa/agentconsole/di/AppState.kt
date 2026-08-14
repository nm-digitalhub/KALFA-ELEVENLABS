package me.kalfa.agentconsole.di

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Foreground gate for polling loops (set from MainActivity's onStart/onStop).
 *
 * Starts FALSE, and the default is the whole point. It used to start true, which
 * defeated the gate in precisely the case it exists for: a process with no Activity in
 * it. PresenceForegroundService restarted by START_STICKY, or woken by an FCM push,
 * reaches DependencyContainer.agentPresence and therefore constructs the repositories —
 * but MainActivity never runs, so nothing ever set this to false, and the 60s console
 * polls plus the 15s dispatch poll ran for the whole shift against a screen nobody was
 * looking at.
 *
 * Nothing depends on the old default to get its first read: every repository fetches on
 * the signed-in transition (see data/SessionGate.kt), not on the first poll tick. And a
 * normal launch is unaffected — Activity.onStart runs before the first composition, and
 * the repositories are constructed during composition, so this is already true by the
 * time any loop evaluates it.
 */
object AppVisibility {
    val isForeground = MutableStateFlow(false)
}
