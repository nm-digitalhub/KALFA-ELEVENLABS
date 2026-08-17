package me.kalfa.agentconsole.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * The HTTP client every KALFA API call uses, with timeouts that were chosen rather
 * than inherited.
 *
 * `HttpClient(OkHttp)` with no configuration takes OkHttp's defaults — 10 seconds
 * for connect, read and write. Nothing in this app had ever set them, and 10s is
 * simply too short for one of these routes: `POST /api/console-calls/dial-intent`
 * authenticates, checks consent, and asks VOXIMPLANT to resolve which number a
 * session belongs to before it answers.
 *
 * Measured from device telemetry 2026-08-17T21:54:14, tapping call-back:
 *
 *     dial.failed err=SocketTimeoutException:_Socket_timeout_has_expired
 *
 * The request reached the server — Supabase's edge log shows the route
 * authenticating at 21:53:48 — and the app stopped waiting before it answered. The
 * agent saw a failure for a call that may well have been placed, which is the worst
 * shape a timeout can take.
 *
 * 30s rather than "as long as it takes": a dial the agent is watching has to fail
 * eventually, and nginx in front of this API allows 300s, so the client must be the
 * one to give up first or a hung request holds a connection for five minutes.
 */
object KalfaHttpClient {
    /** Long enough for a dial that consults Voximplant server-side. */
    private const val REQUEST_TIMEOUT_MS = 30_000L

    /** Reaching the host is either fast or not happening — this one stays short. */
    private const val CONNECT_TIMEOUT_MS = 10_000L

    /** Gap BETWEEN packets, not total duration; the request timeout bounds that. */
    private const val SOCKET_TIMEOUT_MS = 30_000L

    fun create(): HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
    }
}
