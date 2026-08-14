package me.kalfa.agentconsole.telemetry

import android.content.Context
import io.ktor.client.HttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live device telemetry: a structured record of every meaningful step in the call
 * path, written to an app-private file, mirrored into an in-memory ring for the
 * Debug Live screen, and streamed to `beta.kalfa.me` so it can be tailed over SSH
 * while the phone rings.
 *
 * ## The one rule
 *
 * **Telemetry must never block, slow, or break the call path.** Every public entry
 * point here is non-blocking and swallows its own failures. That is not defensive
 * habit — the entire history this feature was built to end is silent failures in
 * this exact code, and a diagnostic that can itself break the thing it observes
 * would be the worst possible addition to it. Concretely:
 *
 *  - [Telemetry.emit] does one bounded `offer` onto a queue and returns. It never
 *    touches disk, network, or a lock the call path holds.
 *  - A full queue drops the event and counts the drop. It never blocks a caller.
 *  - The writer runs on its own daemon thread; the uploader on its own IO scope.
 *  - Every boundary is wrapped so a `Throwable` cannot escape into telephony.
 *
 * ## Off by default
 *
 * [TelemetrySettings.enabled] gates the file and the upload; both are false until
 * the owner turns them on from the Debug Live screen. The in-memory ring is
 * always live: it costs a bounded few hundred strings, never leaves the process,
 * and being empty at the moment someone opens the screen to find out what just
 * happened would defeat the point.
 */
class DeviceTelemetry internal constructor(
    private val settings: TelemetrySettings,
    private val logFile: TelemetryLogFile,
    private val uploader: TelemetryUploader,
    private val session: TelemetrySession = TelemetrySession(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Bounded. A device stuck in a retry loop cannot grow this, and offer() on a
    // full queue returns false immediately rather than blocking the caller — which
    // on the wake path could be the FCM delivery thread.
    private val pending = LinkedBlockingQueue<TelemetryEvent>(QUEUE_CAPACITY)
    private val queueDrops = AtomicLong(0)
    private val writtenSeq = AtomicLong(0)

    private val uploadSignal = Channel<Unit>(Channel.CONFLATED)

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Formatted lines, oldest first — the same text the server log carries. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    val processId: String get() = session.processId
    val currentSessionId: String get() = session.current

    fun uploadStatus(): TelemetryUploader.Status = uploader.status()
    fun logSizeBytes(): Long = runCatching { logFile.sizeBytes() }.getOrDefault(0L)

    /**
     * Events discarded before they were ever written, because the writer queue was
     * full. Distinct from [TelemetryUploader.Status.dropped], which counts events
     * that were written locally but never reached the server — the two failures
     * look identical in the server log and have completely different remedies, so
     * the screen shows them separately.
     */
    fun writeDrops(): Long = queueDrops.get()

    // Both setters poke uploadSignal: the upload loop parks on it while either
    // flag is false, so flipping one has to be what wakes it.
    var enabled: Boolean
        get() = settings.enabled
        set(value) {
            settings.enabled = value
            uploadSignal.trySend(Unit)
        }

    var uploadEnabled: Boolean
        get() = settings.uploadEnabled
        set(value) {
            settings.uploadEnabled = value
            if (value) uploader.resetServerDisabled()
            uploadSignal.trySend(Unit)
        }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    private fun start() {
        // Daemon: this thread must never be the reason the process stays alive.
        Thread({ writerLoop() }, "kalfa-telemetry").apply { isDaemon = true }.start()
        scope.launch { uploadLoop() }
    }

    private fun writerLoop() {
        // Seed the ring from the file BEFORE draining anything new, so the screen
        // opens showing previous processes too — which is where the interesting
        // part of a missed call usually is, since the process that handled it is
        // typically already dead by the time anyone looks.
        runCatching {
            val seeded = logFile.readTail(RING_CAPACITY)
            if (seeded.isNotEmpty()) _lines.value = seeded
        }

        val batch = ArrayList<TelemetryEvent>(64)
        while (true) {
            try {
                batch.clear()
                batch.add(pending.take())
                pending.drainTo(batch, 63)

                val rendered = batch.map(::formatTelemetryLine)
                appendToRing(rendered)
                if (settings.enabled) {
                    runCatching { logFile.append(rendered) }
                    if (settings.uploadEnabled) {
                        uploader.enqueue(batch)
                        uploadSignal.trySend(Unit)
                    }
                }
                writtenSeq.set(batch.last().seq)
            } catch (e: InterruptedException) {
                // Nothing interrupts this thread today; if something ever does,
                // stopping is the correct response and the process is going away.
                Thread.currentThread().interrupt()
                return
            } catch (t: Throwable) {
                // A telemetry writer that dies takes the diagnostic with it, so the
                // loop keeps going. Nothing here can affect the call path either way.
            }
        }
    }

    private suspend fun uploadLoop() {
        while (scope.isActive) {
            // Off means OFF. Without this the loop woke every 1.5s forever on a
            // device that had never enabled anything — a default-off diagnostic
            // has no business costing a wakeup a second. Nothing can be enqueued
            // while disabled, so parking on the signal with no timeout is safe:
            // the flip that enables uploading is what wakes it.
            if (!settings.enabled || !settings.uploadEnabled) {
                uploadSignal.receive()
                continue
            }
            val waitMs = runCatching { uploader.pumpOnce() }.getOrDefault(RETRY_ON_ERROR_MS)
            // Report drops as their own event so a `seq` gap in the server log has a
            // stated cause. Emitted rather than logged: the owner reads the log, not
            // logcat — that is the whole premise of this feature.
            val drops = runCatching { uploader.drainDropCount() }.getOrDefault(0L)
            if (drops > 0) emit(TelemetryEvents.UPLOAD_DROPPED, "n" to drops.toString())
            if (waitMs > 0) {
                withTimeoutOrNull(waitMs) { uploadSignal.receive() }
            }
        }
    }

    private fun appendToRing(rendered: List<String>) {
        val next = _lines.value + rendered
        _lines.value = if (next.size <= RING_CAPACITY) next else next.takeLast(RING_CAPACITY)
    }

    // ── emitting ──────────────────────────────────────────────────────────────

    internal fun emit(name: String, fields: List<Pair<String, String>>) {
        val event = telemetryEvent(
            atMs = nowMs(),
            sessionId = session.current,
            seq = session.nextSeq(),
            name = name,
            fields = fields,
        )
        if (!pending.offer(event)) queueDrops.incrementAndGet()
    }

    internal fun emit(name: String, vararg fields: Pair<String, String>) = emit(name, fields.asList())

    // ── call-attempt sessions ─────────────────────────────────────────────────

    /**
     * Begin tracing one call attempt, so every line from here until the leg ends
     * shares a `sid` and `grep sid=c…` yields that attempt and nothing else.
     *
     * Idempotent while an attempt is already open — a push immediately followed by
     * the SDK's own incoming call is ONE attempt, not two.
     */
    internal fun openCallSession(reason: String) {
        val alreadyOpen = session.callInFlight
        val id = session.openCall()
        if (alreadyOpen) return
        emit(TelemetryEvents.SESSION_OPEN, "reason" to reason, "sid" to id)
        // A watchdog for the case the process OUTLIVES the wake — an agent on
        // shift with the presence service up. It is the only way the "push
        // arrived, nothing else ever happened" verdict gets written when nobody
        // is calling closeCallSession. On a push-woken process that is killed
        // seconds later this never runs, which is why the FCM service also
        // reports the same verdict synchronously before it returns.
        scope.launch {
            delay(CALL_SESSION_WATCHDOG_MS)
            if (session.callInFlight && session.current == id && !session.incomingCallSeen) {
                val age = session.callAgeMs()
                emit(TelemetryEvents.CALL_NO_INCOMING_AFTER_PUSH, "ms" to (age ?: 0L).toString())
                closeCallSession("watchdog")
            }
        }
        // A SECOND, unconditional close on age, because the one above can only
        // fire when no call ever arrived. The coordinator documents a real gap: a
        // second offer replaces a still-pending first one, whose SDK Call stays
        // live and never reaches DISCONNECTED — so cleanUp never runs and nothing
        // closes the trace. Without this the `c…` id leaks, and every later
        // heartbeat and presence line is filed under a call attempt that ended
        // long ago. That is the readability property this class exists for,
        // failing in precisely the overlapping-calls case it was designed around.
        scope.launch {
            delay(CALL_SESSION_MAX_MS)
            if (session.callInFlight && session.current == id) closeCallSession("stale")
        }
    }

    internal fun noteIncomingCall() = session.noteIncomingCall()

    internal val incomingCallSeen: Boolean get() = session.incomingCallSeen

    internal fun closeCallSession(reason: String) {
        val elapsed = session.closeCall() ?: return
        emit(TelemetryEvents.SESSION_CLOSE, "reason" to reason, "ms" to elapsed.toString())
    }

    // ── flushing (the push-wake path only) ────────────────────────────────────

    /**
     * Block until everything emitted so far has reached the local file, or until
     * [timeoutMs] elapses.
     *
     * Called from exactly one place: the end of `onMessageReceived`, after all
     * telephony work is finished. A push-woken process can be torn down the
     * instant that method returns, and the writer thread is asynchronous, so
     * without this the last few lines — the ones naming the step that failed —
     * would be the ones lost. Local file I/O of a handful of lines takes about a
     * millisecond, so the timeout is a ceiling that should never be reached.
     */
    internal fun flushLocalBlocking(timeoutMs: Long) {
        if (!settings.enabled) return
        val deadline = nowMs() + timeoutMs
        val target = session.lastIssuedSeq
        while (writtenSeq.get() < target && nowMs() < deadline) {
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * Best-effort: push whatever is queued to the server within [timeoutMs].
     *
     * Deliberately tiny. `WAKE_PUSH_TIMEOUT_MS` is 9s and AGENTS.md already flags
     * that it races the SDK's own 10s internal push-registration timeout, so this
     * must not meaningfully extend the wake. If it expires, nothing is lost: the
     * local file already holds every line and the next pump ships them.
     */
    internal suspend fun flushUploadsBestEffort(timeoutMs: Long) {
        if (!settings.enabled || !settings.uploadEnabled) return
        withTimeoutOrNull(timeoutMs) {
            while (uploader.status().queued > 0) {
                if (runCatching { uploader.pumpOnce() }.getOrDefault(1L) > 0L) return@withTimeoutOrNull
            }
        }
    }

    // ── Debug Live screen actions ─────────────────────────────────────────────

    fun refreshFromFile() {
        scope.launch {
            runCatching {
                val tail = logFile.readTail(RING_CAPACITY)
                if (tail.isNotEmpty()) _lines.value = tail
            }
        }
    }

    /**
     * "Send the log" — read the tail of the local FILE and queue it for upload.
     *
     * Not merely a nudge to the in-memory queue, and the difference is the whole
     * point. On the scenario being diagnosed — a push-woken cold start with no
     * Supabase JWT yet, killed seconds later — nothing reaches the server live
     * and the queue dies with the process. Only the file survives. Without this,
     * the SSH view would be permanently empty for exactly the case it exists for.
     *
     * May re-send lines the server already has. That is deliberate: this app does
     * not track a delivered watermark, and a duplicate is self-evident (identical
     * `sid` and `seq`) and harmless in a log, whereas silently skipping a line
     * that was never actually delivered would not be.
     */
    fun requestUploadNow() {
        uploader.resetServerDisabled()
        scope.launch {
            runCatching {
                val events = logFile.readTail(UPLOAD_BACKLOG_LINES).mapNotNull(::parseTelemetryLine)
                if (events.isNotEmpty()) uploader.enqueue(events)
            }
            uploadSignal.trySend(Unit)
        }
    }

    fun clear() {
        scope.launch {
            runCatching { logFile.clear() }
            _lines.value = emptyList()
        }
    }

    companion object {
        private const val QUEUE_CAPACITY = 1024
        private const val RING_CAPACITY = 400
        private const val CALL_SESSION_WATCHDOG_MS = 45_000L

        /**
         * Longest any one call attempt may own the trace. Generous — a real
         * answered RSVP leg runs minutes — because closing early would split one
         * call across two ids, which is the failure this bound is guarding
         * against in the first place.
         */
        private const val CALL_SESSION_MAX_MS = 20 * 60_000L

        /** Lines "send the log" ships. Bounded by the uploader's own queue cap. */
        private const val UPLOAD_BACKLOG_LINES = 200
        private const val RETRY_ON_ERROR_MS = 5_000L
        private const val POLL_MS = 5L

        /**
         * Build and start the singleton. Called once, from DependencyContainer.
         *
         * `httpClient` is the caller's, so telemetry does not open a second
         * connection pool alongside the one VoxSdkAuthClient already uses.
         */
        fun create(
            context: Context,
            httpClient: HttpClient,
            getJwt: suspend () -> String?,
        ): DeviceTelemetry {
            val settings = TelemetrySettings(context)
            val logFile = TelemetryLogFile(context.applicationContext.filesDir)
            val uploader = TelemetryUploader(httpClient, getJwt)
            return DeviceTelemetry(settings, logFile, uploader).also { it.start() }
        }
    }
}

/**
 * The call-site facade.
 *
 * A global rather than an injected dependency, on purpose: the emit sites are
 * scattered through classes the platform constructs itself (a
 * `FirebaseMessagingService`, a `BroadcastReceiver`, the Voximplant SDK's own
 * listener callbacks) where there is nothing to inject into, and threading a
 * telemetry handle through all of them would be a far larger and riskier diff
 * across files other agents own than a one-line `Telemetry.emit(...)` beside an
 * existing `Log.d`.
 *
 * Uninstalled — in unit tests, and in any process where DependencyContainer has
 * not attached yet — every method is a silent no-op.
 */
object Telemetry {
    @Volatile private var impl: DeviceTelemetry? = null

    fun install(telemetry: DeviceTelemetry) { impl = telemetry }

    val instance: DeviceTelemetry? get() = impl

    /**
     * Record one step. Never throws.
     *
     * The blanket `catch (t: Throwable)` is the single place in this codebase
     * where swallowing everything is the correct behaviour, and it is here rather
     * than at each of the ~40 call sites: an emit site sits inside telephony code
     * whose failure modes are invisible and expensive, and a diagnostic that can
     * throw into `onMessageReceived` or an SDK callback would be strictly worse
     * than no diagnostic at all. `Throwable`, not `Exception`, for the same reason
     * VoxClientManager.ensureLoggedIn catches it: a failed static initialiser
     * throws an `Error`, and that is exactly the class of failure this must not
     * turn into a dropped call.
     */
    fun emit(name: String, vararg fields: Pair<String, String>) {
        try {
            impl?.emit(name, fields.asList())
        } catch (t: Throwable) {
            // Deliberately empty — see this method's kdoc.
        }
    }

    fun openCallSession(reason: String) {
        try { impl?.openCallSession(reason) } catch (t: Throwable) { }
    }

    fun noteIncomingCall() {
        try { impl?.noteIncomingCall() } catch (t: Throwable) { }
    }

    fun closeCallSession(reason: String) {
        try { impl?.closeCallSession(reason) } catch (t: Throwable) { }
    }

    fun incomingCallSeen(): Boolean = try { impl?.incomingCallSeen == true } catch (t: Throwable) { false }

    fun flushLocalBlocking(timeoutMs: Long) {
        try { impl?.flushLocalBlocking(timeoutMs) } catch (t: Throwable) { }
    }

    suspend fun flushUploadsBestEffort(timeoutMs: Long) {
        try { impl?.flushUploadsBestEffort(timeoutMs) } catch (t: Throwable) { }
    }
}
