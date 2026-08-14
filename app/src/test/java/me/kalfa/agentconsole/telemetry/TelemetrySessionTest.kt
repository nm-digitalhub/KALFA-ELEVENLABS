package me.kalfa.agentconsole.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session id is what makes the log readable when two call attempts overlap or
 * when the 30s presence heartbeat is chattering between them — `grep sid=c…`
 * yields one attempt and nothing else. The behaviour that matters is entirely in
 * the transitions, so it is pinned here rather than left to be observed on a
 * device nobody can attach a debugger to.
 */
class TelemetrySessionTest {

    private fun fixedIds(vararg ids: String): (Char) -> String {
        val queue = ArrayDeque(ids.toList())
        return { prefix -> queue.removeFirstOrNull() ?: "${prefix}fallback" }
    }

    @Test
    fun `outside a call, every line carries the process session`() {
        val s = TelemetrySession(newId = fixedIds("p1a2b3c4"))
        assertEquals("p1a2b3c4", s.current)
        assertEquals("p1a2b3c4", s.processId)
        assertFalse(s.callInFlight)
    }

    @Test
    fun `opening a call switches the id and closing it switches back`() {
        val s = TelemetrySession(newId = fixedIds("p1", "c9"))
        assertEquals("p1", s.current)
        assertEquals("c9", s.openCall())
        assertEquals("c9", s.current)
        assertTrue(s.callInFlight)
        s.closeCall()
        assertEquals("p1", s.current)
        assertFalse(s.callInFlight)
    }

    @Test
    fun `opening twice does NOT split one attempt across two ids`() {
        // The live case: a Voximplant push arrives and the SDK's own incoming-call
        // callback follows a moment later. That is ONE attempt. Two ids would
        // interleave it in the log — exactly what the session exists to prevent.
        val s = TelemetrySession(newId = fixedIds("p1", "c9", "cSHOULD_NOT_BE_USED"))
        val first = s.openCall()
        val second = s.openCall()
        assertEquals(first, second)
        assertEquals("c9", s.current)
    }

    @Test
    fun `closing when nothing is open reports nothing, so no phantom close is emitted`() {
        val s = TelemetrySession(newId = fixedIds("p1"))
        assertNull(s.closeCall())
    }

    @Test
    fun `a closed attempt reports how long it ran`() {
        var now = 1_000L
        val s = TelemetrySession(nowMs = { now }, newId = fixedIds("p1", "c9"))
        s.openCall()
        now = 4_500L
        assertEquals(3_500L, s.closeCall())
    }

    @Test
    fun `the incoming-call flag is per attempt and resets with it`() {
        // This flag is what fcm_wake_done's `incoming=` reports — the single
        // highest-value reading in the whole channel. A leak across attempts would
        // make a missed call look like an answered one.
        val s = TelemetrySession(newId = fixedIds("p1", "c9", "cA"))
        s.openCall()
        assertFalse(s.incomingCallSeen)
        s.noteIncomingCall()
        assertTrue(s.incomingCallSeen)
        s.closeCall()
        s.openCall()
        assertFalse(s.incomingCallSeen)
    }

    @Test
    fun `seq is monotonic from one, because a gap is how lost lines are proven`() {
        val s = TelemetrySession(newId = fixedIds("p1"))
        assertEquals(1L, s.nextSeq())
        assertEquals(2L, s.nextSeq())
        assertEquals(3L, s.nextSeq())
    }

    @Test
    fun `lastIssuedSeq does not consume a seq`() {
        // The flush-before-process-death path asks what to wait for. Consuming one
        // to find out would punch a permanent hole in the sequence on every wake —
        // a diagnostic faking its own loss signal.
        val s = TelemetrySession(newId = fixedIds("p1"))
        s.nextSeq()
        s.nextSeq()
        assertEquals(2L, s.lastIssuedSeq)
        assertEquals(2L, s.lastIssuedSeq)
        assertEquals(3L, s.nextSeq())
    }

    @Test
    fun `callAgeMs is null when no attempt is open and elapsed when one is`() {
        var now = 500L
        val s = TelemetrySession(nowMs = { now }, newId = fixedIds("p1", "c9"))
        assertNull(s.callAgeMs())
        s.openCall()
        now = 2_000L
        assertEquals(1_500L, s.callAgeMs())
    }

    @Test
    fun `generated ids carry their flavour prefix and are distinguishable`() {
        val process = defaultSessionId('p')
        val call = defaultSessionId('c')
        assertTrue(process, process.startsWith("p"))
        assertTrue(call, call.startsWith("c"))
        assertEquals(8, process.length)
        assertEquals(8, call.length)
        // Not a strong randomness claim — just that two draws are not the same
        // constant, which would silently merge every attempt into one trace.
        assertNotEquals(defaultSessionId('c'), defaultSessionId('c'))
    }
}
