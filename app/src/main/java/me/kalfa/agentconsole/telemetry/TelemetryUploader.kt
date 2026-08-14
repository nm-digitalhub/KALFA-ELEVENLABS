package me.kalfa.agentconsole.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ships buffered events to `POST /api/agents/telemetry` on beta.kalfa.me so the
 * owner can `tail -f` the server log while the phone rings.
 *
 * **This is the convenience layer, not the record of truth** — see
 * [TelemetryLogFile]'s kdoc. Everything here is allowed to fail, and failing has
 * exactly one consequence: lines arrive later, or not at all, and the `seq` gap
 * says so. Nothing here may ever propagate into the call path.
 *
 * Batched rather than one POST per event, deliberately and against the letter of
 * the original brief. The reason is the wake path: the process may only be alive
 * for a few hundred milliseconds after the telephony work finishes, and ten
 * sequential POSTs over cellular do not fit in that window while one POST
 * carrying ten events does. The server writes one line per event either way, so
 * the `tail -f` the brief asks for is byte-identical; only the number of round
 * trips changes.
 *
 * HTTP contract, kept in step with `beta/src/app/api/agents/telemetry/route.ts`:
 *   202 → accepted           401/403 → not authorised (back off, keep the batch)
 *   400 → payload rejected   429 → rate limited (back off, keep the batch)
 *   503 → the server-side feature flag is off (back off long, DROP the batch)
 */
class TelemetryUploader(
    private val httpClient: HttpClient,
    private val getJwt: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    /** What the Debug Live screen shows about the upload half. */
    data class Status(
        val queued: Int = 0,
        val dropped: Long = 0,
        val sent: Long = 0,
        val lastOutcome: String? = null,
        val serverDisabled: Boolean = false,
    )

    private val lock = Any()
    private val queue = ArrayDeque<TelemetryEvent>()
    private var dropped = 0L
    private var sent = 0L
    private var lastOutcome: String? = null
    private var serverDisabled = false

    /** Newly dropped events since the last time [drainDropCount] was called. */
    private var undeclaredDrops = 0L

    fun status(): Status = synchronized(lock) {
        Status(queue.size, dropped, sent, lastOutcome, serverDisabled)
    }

    /**
     * Queue events for sending. Never blocks, never throws, and drops the OLDEST
     * events when full: in a diagnostic whose subject is a live call, the newest
     * lines are the ones being watched.
     */
    fun enqueue(events: List<TelemetryEvent>) = synchronized(lock) {
        for (e in events) {
            if (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                dropped++
                undeclaredDrops++
            }
            queue.addLast(e)
        }
    }

    /**
     * How many events have been discarded since this was last asked, so
     * [TelemetryEvents.UPLOAD_DROPPED] can state the cause of a `seq` gap in the
     * server log rather than leaving it as an unexplained hole.
     */
    fun drainDropCount(): Long = synchronized(lock) {
        val n = undeclaredDrops
        undeclaredDrops = 0
        n
    }

    /**
     * Send one batch if there is anything to send.
     *
     * @return the delay in milliseconds the caller should wait before pumping
     *   again, or 0 when it may pump immediately (more is queued and the last
     *   send succeeded).
     */
    suspend fun pumpOnce(): Long {
        val batch = synchronized(lock) {
            if (serverDisabled || queue.isEmpty()) return IDLE_DELAY_MS
            val take = minOf(MAX_BATCH, queue.size)
            List(take) { queue.removeFirst() }
        }

        val jwt = runCatching { getJwt() }.getOrNull()
        if (jwt.isNullOrEmpty()) {
            requeue(batch, "no_jwt")
            return AUTH_BACKOFF_MS
        }

        val code = runCatching {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                httpClient.post("$baseUrl$PATH") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(WireBatch(batch.map { it.toWire() })))
                }.status.value
            }
        }.getOrNull()

        return when (code) {
            null -> { requeue(batch, "network"); NETWORK_BACKOFF_MS }
            200, 202, 204 -> {
                synchronized(lock) {
                    sent += batch.size
                    lastOutcome = "ok"
                    if (queue.isEmpty()) IDLE_DELAY_MS else 0L
                }
            }
            400, 413, 422 -> {
                // The server rejected the payload's shape. Re-sending the same
                // bytes cannot succeed, so drop rather than loop forever on it —
                // the local file still holds every line.
                synchronized(lock) {
                    dropped += batch.size
                    undeclaredDrops += batch.size
                    lastOutcome = "rejected_$code"
                }
                NETWORK_BACKOFF_MS
            }
            401, 403 -> { requeue(batch, "unauthorised_$code"); AUTH_BACKOFF_MS }
            429 -> { requeue(batch, "rate_limited"); RATE_BACKOFF_MS }
            503 -> {
                // The server-side feature flag is off. Nothing is wrong and nothing
                // will change until someone turns it on, so stop burning battery and
                // discard the batch — the local file remains complete.
                synchronized(lock) {
                    dropped += batch.size
                    lastOutcome = "server_disabled"
                    serverDisabled = true
                }
                DISABLED_BACKOFF_MS
            }
            else -> { requeue(batch, "http_$code"); NETWORK_BACKOFF_MS }
        }
    }

    /** Clears the "server said it is switched off" latch, e.g. when the owner retries by hand. */
    fun resetServerDisabled() = synchronized(lock) {
        serverDisabled = false
        lastOutcome = null
    }

    private fun requeue(batch: List<TelemetryEvent>, outcome: String) = synchronized(lock) {
        lastOutcome = outcome
        // Back onto the FRONT, in order, so a retry preserves sequence. Anything
        // that no longer fits is counted as dropped rather than silently lost.
        for (e in batch.asReversed()) {
            if (queue.size >= MAX_QUEUE) {
                dropped++
                undeclaredDrops++
            } else {
                queue.addFirst(e)
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://beta.kalfa.me"
        const val PATH = "/api/agents/telemetry"

        /** Must not exceed the route's own per-request event cap. */
        const val MAX_BATCH = 50

        private const val MAX_QUEUE = 300
        private const val REQUEST_TIMEOUT_MS = 6_000L
        private const val IDLE_DELAY_MS = 1_500L
        private const val NETWORK_BACKOFF_MS = 5_000L
        private const val AUTH_BACKOFF_MS = 15_000L
        private const val RATE_BACKOFF_MS = 30_000L
        private const val DISABLED_BACKOFF_MS = 300_000L
    }
}

@Serializable
private data class WireEvent(
    val at: String,
    val sid: String,
    val seq: Long,
    val name: String,
    val fields: Map<String, String> = emptyMap(),
)

@Serializable
private data class WireBatch(val events: List<WireEvent>)

private fun TelemetryEvent.toWire() = WireEvent(
    at = formatTelemetryTimestamp(atMs),
    sid = sessionId,
    seq = seq,
    name = name,
    fields = fields.toMap(),
)
