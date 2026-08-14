package me.kalfa.agentconsole.ui

import kotlinx.serialization.Serializable

// Type-safe Navigation Compose routes (navigation 2.8+ style)
@Serializable object DashboardRoute
@Serializable object LiveCallsRoute
@Serializable object EventsRoute
@Serializable object HistoryRoute
@Serializable data class EventDetailRoute(val eventId: String)
@Serializable data class CallDetailRoute(val callId: String)

// There is deliberately NO route for the "אבחון חי" diagnostic screen. It is a
// top-level overlay in MainActivity, outside AuthGate and outside this NavHost —
// see the comment at its declaration for why that placement is load-bearing
// rather than incidental.
