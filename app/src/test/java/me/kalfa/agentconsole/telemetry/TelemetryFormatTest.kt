package me.kalfa.agentconsole.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrub is the last thing standing between a guest's phone number and a log
 * file AGENTS.md assumes is read by someone who should not see customer data, so
 * it is tested rather than reviewed. The line format is tested for the same
 * reason the server test pins it: the format IS the deliverable — it is what the
 * owner reads over SSH — and the two halves must not drift apart.
 *
 * Every rejected case below is a value that can actually reach `emit` from this
 * app's own call path (`Call.number`, a Supabase JWT, an FCM registration token,
 * a Voximplant full username), not an invented one.
 */
class TelemetryFormatTest {

    @Test
    fun `redacts an Israeli mobile number in every shape the call path can produce`() {
        for (v in listOf("+972501234567", "0501234567", "050-123-4567", "(050) 123 4567")) {
            assertEquals("failed for $v", "<redacted:digits>", scrubTelemetryValue(v))
        }
    }

    @Test
    fun `redacts a Supabase JWT`() {
        assertEquals(
            "<redacted:token>",
            scrubTelemetryValue("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abc.def"),
        )
    }

    @Test
    fun `redacts an FCM registration token and a Voximplant full username`() {
        assertEquals("<redacted:token>", scrubTelemetryValue("d" + "A9_x-".repeat(12)))
        assertEquals(
            "<redacted:handle>",
            scrubTelemetryValue("agent1@kalfa-rsvp.kalfarsvp.voximplant.com"),
        )
    }

    @Test
    fun `keeps the non-identifying values the diagnostic depends on`() {
        // A false positive here is a silently useless diagnostic: these are the
        // fields that make a line readable at all.
        for (v in listOf("true", "false", "RINGING", "fcm_token", "vox_register", "3", "9012")) {
            assertEquals("failed for $v", v, scrubTelemetryValue(v))
        }
    }

    @Test
    fun `keeps a Voximplant call id, which carries a long digit run but is not phone-shaped`() {
        assertEquals("7666179052-a1f", scrubTelemetryValue("7666179052-a1f"))
    }

    @Test
    fun `keeps a long millisecond duration, which is bare digits but too short to be a number`() {
        // ms=1234567 is a legitimate 20-minute session. The 9-digit threshold for
        // BARE digits exists precisely so this survives.
        assertEquals("1234567", scrubTelemetryValue("1234567"))
        assertEquals("45000", scrubTelemetryValue("45000"))
    }

    @Test
    fun `flattens whitespace and separators so one value can never break the line format`() {
        assertEquals("a_b", scrubTelemetryValue("a b"))
        assertEquals("a_b", scrubTelemetryValue("a=b"))
        assertEquals("a_b", scrubTelemetryValue("a\nb"))
        assertEquals("-", scrubTelemetryValue("   "))
    }

    @Test
    fun `truncates an over-long value rather than dropping it`() {
        // Deliberately NOT a long run of plain letters: that is token-shaped and
        // is redacted rather than truncated, which is the correct behaviour and
        // would make this a test of the wrong branch. The dots take it out of the
        // opaque-token character class.
        val long = "x.".repeat(300)
        assertEquals(TELEMETRY_MAX_VALUE_CHARS, scrubTelemetryValue(long).length)
    }

    @Test
    fun `redacts an unrecognised 40-plus character blob rather than truncating it`() {
        // Nothing this app emits is 40 characters of token-shaped text. Anything
        // that is, is far more likely to be a credential than a diagnostic, so it
        // is redacted rather than truncated to a 64-character prefix of itself.
        assertEquals("<redacted:token>", scrubTelemetryValue("x".repeat(500)))
    }

    @Test
    fun `renders the documented line shape`() {
        val event = TelemetryEvent(
            atMs = 1_786_000_353_412L,
            sessionId = "c7f3a91b",
            seq = 42,
            name = "fcm.message_received",
            fields = listOf("vox" to "true", "keys" to "3"),
        )
        val line = formatTelemetryLine(event)
        assertTrue(line, line.endsWith(" sid=c7f3a91b seq=42 fcm.message_received vox=true keys=3"))
        assertTrue(line, line.startsWith("2026-"))
        assertTrue(line, line.contains("Z sid="))
    }

    @Test
    fun `a line never contains a newline, so one event can never become two log lines`() {
        val event = telemetryEvent(
            atMs = 0L,
            sessionId = "p1",
            seq = 1,
            name = "call.state",
            fields = listOf("s" to "RIN\nGING"),
        )
        assertFalse(formatTelemetryLine(event).contains("\n"))
    }

    @Test
    fun `a line is bounded even when a caller supplies absurd input`() {
        val event = telemetryEvent(
            atMs = 0L,
            sessionId = "p1",
            seq = 1,
            name = "call.state",
            fields = (1..40).map { "k$it" to "v".repeat(200) },
        )
        val line = formatTelemetryLine(event)
        assertTrue(line.length <= TELEMETRY_MAX_LINE_CHARS)
        // Field count is capped too, so one event cannot widen past readability.
        assertTrue(event.fields.size <= 8)
    }

    @Test
    fun `a malformed event name is replaced rather than emitted`() {
        val event = telemetryEvent(0L, "p1", 1, "FCM Message Received", emptyList())
        assertEquals("invalid.event_name", event.name)
    }

    @Test
    fun `a malformed field key is replaced rather than emitted`() {
        val event = telemetryEvent(0L, "p1", 1, "call.state", listOf("Some Key" to "x"))
        assertEquals("k", event.fields.single().first)
    }

    @Test
    fun `the timestamp is UTC regardless of the device timezone`() {
        // The owner reads this over SSH on a server that is not necessarily on the
        // phone's timezone; a local-time stamp would silently mislead by hours.
        assertTrue(formatTelemetryTimestamp(0L).startsWith("1970-01-01T00:00:00.000Z"))
    }
}
