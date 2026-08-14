package me.kalfa.agentconsole.telemetry

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ─────────────────────────────────────────────────────────────────────────────
// The wire/line format for device telemetry, and the PII scrub that guards it.
//
// Deliberately free of every Android, Ktor and Voximplant import so the format
// and the scrub are unit-testable on a plain JVM — same separation-for-testability
// reasoning as VoxSilentLogin.kt's planSilentLogin and
// VoxIncomingCallCoordinator's planIncomingCallCleanup. The scrub in particular
// is the kind of code that must be tested rather than reviewed: it is the last
// thing standing between a guest's phone number and a log file that AGENTS.md's
// own §"Data model" says is read by people who should not see customer data.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One observed step in the call path.
 *
 * @param atMs device wall clock. NOT trusted for ordering — [seq] is. A dozing
 *   phone that resyncs NTP mid-wake can move this backwards; the server records
 *   its own receive time alongside so skew is visible rather than confusing.
 * @param sessionId the trace this line belongs to: `p…` for the process-scoped
 *   session that owns everything outside a call attempt, `c…` for one call
 *   attempt. See [TelemetrySession].
 * @param seq monotonic **per process**, starting at 1. This is what makes upload
 *   loss provable: a gap in `seq` within one process means lines were dropped in
 *   transit, and `seq` restarting at 1 means the process died and a new one
 *   started. Without it, "the app stopped at step 3" and "telemetry stopped at
 *   step 3" are the same picture — which is the exact ambiguity this whole
 *   channel exists to remove.
 * @param name a dotted, lowercase event name from [TelemetryEvents].
 * @param fields non-identifying `k=v` detail. Every value passes [scrubTelemetryValue].
 */
data class TelemetryEvent(
    val atMs: Long,
    val sessionId: String,
    val seq: Long,
    val name: String,
    val fields: List<Pair<String, String>> = emptyList(),
)

/** Longest a single field value may be before it is truncated. */
const val TELEMETRY_MAX_VALUE_CHARS = 64

/** Longest a whole formatted line may be. Bounds both the local file and the POST body. */
const val TELEMETRY_MAX_LINE_CHARS = 512

private val ISO_UTC = object : ThreadLocal<SimpleDateFormat>() {
    // SimpleDateFormat is not thread-safe and emit() is called from at least four
    // threads (main, the FCM delivery thread, the Voximplant SDK's own
    // ScheduledExecutorService, and the telemetry writer). java.time.Instant would
    // be the obvious answer and is NOT available: minSdk is 24, java.time needs 26,
    // and this module does not enable core-library desugaring (checked in
    // app/build.gradle.kts, not assumed).
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
}

fun formatTelemetryTimestamp(atMs: Long): String = ISO_UTC.get()!!.format(Date(atMs))

/**
 * Render one event as the single line that lands in the local file AND, byte for
 * byte in its event portion, in the server log the owner tails. Keeping the two
 * identical is the point: a line read on the phone and the same line read over
 * SSH must not need translating between them.
 *
 * Shape: `<iso> sid=<sid> seq=<n> <event> [k=v …]`
 */
fun formatTelemetryLine(event: TelemetryEvent): String {
    val head = "${formatTelemetryTimestamp(event.atMs)} sid=${event.sessionId} seq=${event.seq} ${event.name}"
    val line = if (event.fields.isEmpty()) {
        head
    } else {
        event.fields.joinToString(separator = " ", prefix = "$head ") { (k, v) -> "$k=$v" }
    }
    return if (line.length <= TELEMETRY_MAX_LINE_CHARS) line else line.take(TELEMETRY_MAX_LINE_CHARS)
}

/**
 * Read a line back into an event — the inverse of [formatTelemetryLine].
 *
 * Exists so "send the log" can ship the tail of the local FILE, not just
 * whatever happens to be in the in-memory upload queue. That distinction is the
 * whole deliverable on the scenario being diagnosed: a push-woken process has no
 * Supabase JWT yet and is killed seconds later, so nothing reaches the server
 * live. Without this, the SSH view would be permanently empty for exactly the
 * case it was built for, and the owner would have to read the trace off the
 * phone screen instead.
 *
 * Returns null for anything that does not round-trip, rather than guessing: a
 * half-written line at a rotation boundary is the expected malformed input, and
 * inventing a plausible event from it would be worse than dropping it.
 */
fun parseTelemetryLine(line: String): TelemetryEvent? {
    val parts = line.trim().split(' ')
    if (parts.size < 4) return null
    val atMs = runCatching { ISO_UTC.get()!!.parse(parts[0])?.time }.getOrNull() ?: return null
    val sid = parts[1].removePrefix("sid=").takeIf { it != parts[1] } ?: return null
    val seq = parts[2].removePrefix("seq=").takeIf { it != parts[2] }?.toLongOrNull() ?: return null
    val name = parts[3]
    if (!EVENT_NAME_RE.matches(name)) return null
    val fields = parts.drop(4).mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0 || i == pair.length - 1) null else pair.take(i) to pair.substring(i + 1)
    }
    // Re-scrubbed on the way back in. The file was written by this same scrub, so
    // this should be a no-op — but a file is a thing a person can edit, and the
    // uploader must not become a way to put an unscrubbed value on the wire.
    return telemetryEvent(atMs, sid, seq, name, fields)
}

private val EVENT_NAME_RE = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$")
private val FIELD_KEY_RE = Regex("^[a-z][a-z0-9_]*$")

/** Event names are a closed vocabulary in spirit; this enforces the shape mechanically. */
fun sanitizeTelemetryEventName(name: String): String {
    val trimmed = name.trim().take(48)
    return if (EVENT_NAME_RE.matches(trimmed)) trimmed else "invalid.event_name"
}

fun sanitizeTelemetryFieldKey(key: String): String {
    val trimmed = key.trim().take(24)
    return if (FIELD_KEY_RE.matches(trimmed)) trimmed else "k"
}

// "Made of digits and phone punctuation only." Deliberately NOT "any 7 digits
// anywhere": Voximplant call ids are hex-and-dash and can legitimately carry a
// long digit run, and redacting the call id would destroy the one field that
// lets two lines be tied to the same leg.
private val PHONE_SHAPED_RE = Regex("^[+()\\-. \\d]{7,}$")
private val PHONE_PUNCTUATION_RE = Regex("[+()\\-. ]")
private val OPAQUE_TOKEN_RE = Regex("^[A-Za-z0-9_:\\-]{40,}$")

/**
 * Strip anything that could identify a guest, an agent, or an account before it
 * reaches a log file.
 *
 * This is the app's half of a belt-and-braces pair — the server route runs the
 * same test again and rejects the request rather than trusting the client (see
 * `beta/src/lib/validation/agent-console.ts`). Both halves exist because either
 * one alone is a single point of failure for a rule AGENTS.md states without
 * qualification: no phone numbers, no names, no tokens, ever.
 *
 * What is redacted, and why each rule is here rather than being over-cautious:
 *  - **phone-shaped** — a value made only of digits and phone punctuation.
 *    `call.number` is the live hazard. Two thresholds, because one would be
 *    wrong in both directions: punctuation is itself strong evidence of a phone
 *    number (no field emitted here contains a `+`, a bracket or a dash), so 7
 *    digits suffices alongside it, while a bare run of digits needs 9 so that a
 *    legitimate `ms=1234567` (a 20-minute duration) is not mistaken for one.
 *    Nothing this app holds is a bare phone shorter than 9 digits — it carries
 *    E.164 (`+972…`, 13) and Israeli mobile (`05…`, 10).
 *  - **JWT / long opaque token** — anything starting `eyJ` (a base64 `{"` — every
 *    Supabase access token) or a 40+ char unbroken token-ish run (FCM
 *    registration tokens, Voximplant access/refresh tokens).
 *  - **anything containing `@`** — an email, or a Voximplant full username, which
 *    names the account and application.
 *
 * Whitespace and `=` are replaced rather than redacted: they would break the
 * `k=v` line format, and a broken line is harder to read than a mangled value.
 */
fun scrubTelemetryValue(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "-"

    // The PII tests run on the RAW value, BEFORE whitespace is flattened, and the
    // order is load-bearing rather than incidental. Flattening first turns
    // "(050) 123 4567" into "(050)_123_4567", which no longer matches the
    // phone shape — so the scrub would have passed a guest's phone number
    // through while looking like it had checked it. Caught by
    // TelemetryFormatTest, which is exactly the sort of hole a code review does
    // not see.
    if (trimmed.contains('@')) return "<redacted:handle>"
    if (trimmed.startsWith("eyJ")) return "<redacted:token>"
    if (PHONE_SHAPED_RE.matches(trimmed)) {
        val digits = trimmed.count { it.isDigit() }
        if (digits >= 9) return "<redacted:digits>"
        if (digits >= 7 && PHONE_PUNCTUATION_RE.containsMatchIn(trimmed)) return "<redacted:digits>"
    }

    // Only now flatten, because whitespace and `=` would break the `k=v` line
    // format and a broken line is harder to read than a mangled value. The
    // opaque-token test runs on the flattened form, which is what actually
    // reaches the log.
    val flattened = trimmed.replace(Regex("[\\s=|]+"), "_")
    if (OPAQUE_TOKEN_RE.matches(flattened)) return "<redacted:token>"
    return if (flattened.length <= TELEMETRY_MAX_VALUE_CHARS) {
        flattened
    } else {
        flattened.take(TELEMETRY_MAX_VALUE_CHARS)
    }
}

/**
 * A short, non-reversible fingerprint of a value that must never itself be logged.
 *
 * Exists for one job: making a credential JOINABLE without disclosing it. The
 * platform can report `push_results: []` — it holds no usable token — while the
 * device believes registration succeeded, and no device-side event can detect
 * that, because nothing device-side happens. Logging a fingerprint of the FCM
 * token turns that unobservable into a fact two systems can be joined on: *the
 * device registered token `a1b2c3d4` at time T; the platform had nothing to send
 * to at time T+n.* Neither log can say that alone.
 *
 * **A hash, deliberately, not a prefix.** A prefix of a credential is a piece of
 * the credential, and it lands in a file whose whole premise is that someone who
 * should not see secrets will read it. SHA-256 truncated to 8 hex characters is
 * ample to correlate one device's registrations over one evening and discloses
 * nothing: it is not reversible, and 32 bits of a cryptographic digest cannot be
 * walked back to a token.
 *
 * Returns `"-"` rather than throwing if the digest is unavailable — a fingerprint
 * is diagnostic garnish, and nothing here may fail into the call path.
 */
fun telemetryFingerprint(value: String): String = try {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    digest.take(4).joinToString("") { "%02x".format(it) }
} catch (t: Throwable) {
    "-"
}

/**
 * Build a scrubbed event. The ONLY constructor call sites should use — the raw
 * [TelemetryEvent] constructor exists for tests and for re-reading a line back.
 */
fun telemetryEvent(
    atMs: Long,
    sessionId: String,
    seq: Long,
    name: String,
    fields: List<Pair<String, String>>,
): TelemetryEvent = TelemetryEvent(
    atMs = atMs,
    sessionId = sessionId,
    seq = seq,
    name = sanitizeTelemetryEventName(name),
    // Cap the field count too: a caller looping over a map could otherwise widen
    // one line past the point where it is readable in a terminal.
    fields = fields.take(8).map { (k, v) -> sanitizeTelemetryFieldKey(k) to scrubTelemetryValue(v) },
)
