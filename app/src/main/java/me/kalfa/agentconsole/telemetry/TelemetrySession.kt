package me.kalfa.agentconsole.telemetry

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Which trace a line belongs to.
 *
 * There is ALWAYS a session, so every line is greppable — `grep sid=c7f3a91b`
 * over the server log yields one call attempt end to end and nothing else, which
 * is the property that makes the output readable when two calls overlap or when
 * the presence heartbeat is chattering in between.
 *
 * Two flavours, distinguishable by their first character:
 *
 *  - **`p…` process session.** Opens at process start and owns everything that is
 *    not part of a call attempt: attach, activity create, shift toggles, the
 *    heartbeat, push-token registration outside a wake. A `p…` id changing is
 *    itself a finding — it means the process died and was recreated, which on
 *    this app is usually the whole story.
 *  - **`c…` call session.** Opens when a call attempt begins — a Voximplant push
 *    arriving, or an incoming call reaching the SDK with no push having preceded
 *    it (the app was already up and logged in) — and closes when the leg ends or
 *    the attempt is abandoned.
 *
 * Ids are 7 hex characters after the prefix: short enough to read in a terminal
 * and to type into a `grep`, wide enough (2^28) that two attempts on one device
 * in one night will not collide.
 *
 * No Android imports, so the session state machine is unit-testable on a plain
 * JVM — the same reasoning [planIncomingCallCleanup] is written under.
 */
class TelemetrySession(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: (Char) -> String = ::defaultSessionId,
) {
    private val processSessionId: String = newId('p')

    private val lock = Any()
    private var callSessionId: String? = null
    private var callOpenedAtMs: Long = 0L
    private var sawIncomingCall: Boolean = false

    private val seqCounter = AtomicLong(0L)

    /** Monotonic per process, starting at 1. See [TelemetryEvent.seq] for why this matters. */
    fun nextSeq(): Long = seqCounter.incrementAndGet()

    /**
     * The highest seq handed out so far, WITHOUT consuming one.
     *
     * Exists for the flush-before-process-death path, which needs to know what to
     * wait for. Consuming a seq to find that out would punch a permanent hole in
     * the sequence on every wake, and a hole is how this design reports lost
     * lines — a diagnostic that fakes its own loss signal is worse than none.
     */
    val lastIssuedSeq: Long get() = seqCounter.get()

    val processId: String get() = processSessionId

    /** The id every event emitted right now should carry. */
    val current: String get() = synchronized(lock) { callSessionId ?: processSessionId }

    /** True while a call attempt is being traced. */
    val callInFlight: Boolean get() = synchronized(lock) { callSessionId != null }

    /**
     * Begin tracing a call attempt.
     *
     * Idempotent while one is already open: a push that arrives twice, or a push
     * immediately followed by the SDK's own incoming call, must NOT split one
     * attempt across two ids — that is precisely the interleaving this class
     * exists to prevent. Returns the id in force afterwards.
     */
    fun openCall(): String = synchronized(lock) {
        callSessionId?.let { return it }
        val id = newId('c')
        callSessionId = id
        callOpenedAtMs = nowMs()
        sawIncomingCall = false
        id
    }

    /** Record that the SDK actually delivered a call inside this attempt. */
    fun noteIncomingCall() = synchronized(lock) { sawIncomingCall = true }

    /** Whether [noteIncomingCall] has been called for the attempt currently open. */
    val incomingCallSeen: Boolean get() = synchronized(lock) { sawIncomingCall }

    /**
     * Stop tracing the current attempt and revert to the process session.
     * Returns how long it ran, or null if no attempt was open (so a caller can
     * skip emitting a close for something that never opened).
     */
    fun closeCall(): Long? = synchronized(lock) {
        if (callSessionId == null) return null
        val elapsed = nowMs() - callOpenedAtMs
        callSessionId = null
        sawIncomingCall = false
        elapsed
    }

    /** Age of the open attempt, or null when none is open. Used by the watchdog. */
    fun callAgeMs(): Long? = synchronized(lock) {
        if (callSessionId == null) null else nowMs() - callOpenedAtMs
    }
}

/**
 * `<prefix><7 hex>`. `Random.Default` rather than `SecureRandom`: this is a
 * correlation id in a diagnostic log, not a capability — nothing is authorised by
 * holding one, and the log it appears in is already behind `requireConsoleAgent`.
 */
fun defaultSessionId(prefix: Char): String {
    val n = Random.nextInt(0, 1 shl 28)
    return prefix + n.toString(16).padStart(7, '0')
}
