package me.kalfa.agentconsole.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one rule two surfaces disagreed about.
 *
 * DashboardScreen rendered a status button per `AgentStatus.values()`, which included
 * "בשיחה". POST /api/agents/status validates against `z.enum(['ready','not_ready','dnd'])`
 * and answers 400 for `in_call` by design — the server infers busy from a live
 * human_agent_call_legs row — so that button could only ever produce a rejected write.
 * And because `setStatus` applied the status optimistically BEFORE asking, one tap left
 * `currentStatus` pinned to IN_CALL, so PresenceForegroundService's 30s heartbeat re-sent
 * it and was rejected again for the rest of the shift; `agent_status.updated_at` stopped
 * advancing and the server's 90s freshness gate stopped routing calls to a device that
 * went on showing itself as present.
 *
 * PresenceNotificationBuilder had the rule right in a private list of its own. Both now
 * read this one, and this test is what stops a third surface reintroducing the fourth
 * value by iterating the enum.
 */
class AgentStatusSettabilityTest {

    @Test
    fun `in call is observed, never declared`() {
        assertFalse(AgentStatus.IN_CALL.isAgentSettable)
        assertFalse(AgentStatus.agentSettable.contains(AgentStatus.IN_CALL))
    }

    @Test
    fun `the settable set is exactly the three the server accepts`() {
        // Mirrors beta src/lib/validation/agent-console.ts:
        //   status: z.enum(['ready', 'not_ready', 'dnd'])
        assertEquals(
            listOf(AgentStatus.READY, AgentStatus.NOT_READY, AgentStatus.DND),
            AgentStatus.agentSettable,
        )
    }

    @Test
    fun `every status the enum offers is either settable or deliberately withheld`() {
        // Guards the shape rather than the contents: a new enum value added without a
        // decision about it would otherwise be silently settable, and the first symptom
        // would be a 400 in production.
        val withheld = AgentStatus.entries.filterNot { it.isAgentSettable }
        assertEquals(listOf(AgentStatus.IN_CALL), withheld)
        assertTrue(AgentStatus.agentSettable.size + withheld.size == AgentStatus.entries.size)
    }
}
