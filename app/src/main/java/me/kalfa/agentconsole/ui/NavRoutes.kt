package me.kalfa.agentconsole.ui

import kotlinx.serialization.Serializable

// Type-safe Navigation Compose routes (navigation 2.8+ style)
@Serializable object DashboardRoute
@Serializable object LiveCallsRoute
@Serializable object EventsRoute
@Serializable object HistoryRoute
@Serializable data class EventDetailRoute(val eventId: String)
@Serializable data class CallDetailRoute(val callId: String)

// Diagnostic only — deliberately NOT in `consoleDestinations`, so it never
// appears in the navigation suite. Reached by a long-press hotspot in
// MainActivity; see DebugLiveScreen's header for why it must work in release.
@Serializable object DebugLiveRoute
