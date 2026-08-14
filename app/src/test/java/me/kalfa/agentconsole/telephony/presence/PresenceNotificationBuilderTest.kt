package me.kalfa.agentconsole.telephony.presence

import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.telephony.PresenceSyncState
import me.kalfa.agentconsole.telephony.vox.RingCapability
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.toHebrewMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// contentTextFor is what the persistent presence notification actually shows — the
// ONLY surface a backgrounded agent sees (docs/android-presence-and-call-ux.md §1).
// Pure function, no Android/Robolectric needed, so the priority rules between its
// three orthogonal inputs (status, syncState, pushRegistrationFailure) are directly
// testable — same separation-for-testability reasoning as VoxSilentLogin.kt.
class PresenceNotificationBuilderTest {

    @Test
    fun `a fully synced status with no push problem shows a plain status line`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
        )
        assertEquals("סטטוס: זמין", text)
    }

    @Test
    fun `syncState Failed replaces the status line entirely with the consequence text`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Failed(AppFailure.NotSignedIn),
            pushRegistrationFailure = null,
        )
        assertEquals(AppFailure.NotSignedIn.toHebrewMessageForPresence(), text)
    }

    // The exact scenario this was built for: presence IS confirmed (status write
    // reached the server) but the device never registered for push — the notification
    // must not read as a plain working "זמין" in that case (owner requirement, and
    // the live incident: Voximplant reported "No push notifications has been sent").
    @Test
    fun `a synced status still surfaces a push-registration failure, not a bare status`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = AppFailure.Unknown,
        )
        assertNotEquals("סטטוס: זמין", text)
        assert(text.contains("שיחות") || text.contains("רישום") || text.contains("נכנסות")) {
            "expected the push-registration consequence to be named, got: $text"
        }
    }

    // syncState is the more urgent fact (the agent isn't even confirmed present at
    // all) — it must win when BOTH are wrong, not be silently dropped in favor of the
    // push-registration text.
    @Test
    fun `syncState Failed takes priority over a push-registration failure`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Failed(AppFailure.NetworkUnavailable),
            pushRegistrationFailure = AppFailure.Unknown,
        )
        assertEquals(AppFailure.NetworkUnavailable.toHebrewMessageForPresence(), text)
    }

    @Test
    fun `pending sync state is distinct from both synced and failed`() {
        val pending = PresenceNotificationBuilder.contentTextFor(AgentStatus.DND, PresenceSyncState.Pending, null)
        val synced = PresenceNotificationBuilder.contentTextFor(AgentStatus.DND, PresenceSyncState.Synced, null)
        assertNotEquals(pending, synced)
    }

    // The gap this was built for: declaring USE_FULL_SCREEN_INTENT in the manifest is
    // not the same as holding it (Android 14+ auto-revokes for non-calling apps, and
    // the user can turn it off regardless) — setFullScreenIntent degrades silently to
    // a heads-up notification with no error anywhere. A synced, fully-registered
    // agent must still see this, or a locked-phone missed call looks like nothing
    // happened at all.
    @Test
    fun `an otherwise-healthy agent still sees a locked-screen ring gap`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
            ringCapability = RingCapability(
                notificationsEnabled = true,
                channelAlerting = true,
                fullScreenIntentAllowed = false,
            ),
        )
        assertNotEquals("סטטוס: זמין", text)
    }

    // The more severe of the two RingCapability problems: notifications blocked
    // entirely means no call reaches this agent at all, not just a missed lock-screen
    // ring — it must read as more urgent than, and distinct from, the locked-screen-
    // only case above.
    @Test
    fun `notifications fully blocked reads differently from the narrower locked-screen gap`() {
        val blocked = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
            ringCapability = RingCapability(
                notificationsEnabled = false,
                channelAlerting = true,
                fullScreenIntentAllowed = true,
            ),
        )
        val lockedOnly = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
            ringCapability = RingCapability(
                notificationsEnabled = true,
                channelAlerting = true,
                fullScreenIntentAllowed = false,
            ),
        )
        assertNotEquals(blocked, lockedOnly)
    }

    // syncState is still the most urgent fact even against a RingCapability problem —
    // not being confirmed present at all outranks a device-configuration gap.
    @Test
    fun `syncState Failed takes priority over a RingCapability problem too`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Failed(AppFailure.NotSignedIn),
            pushRegistrationFailure = null,
            ringCapability = RingCapability(false, false, false),
        )
        assertEquals(AppFailure.NotSignedIn.toHebrewMessageForPresence(), text)
    }

    @Test
    fun `a fully healthy RingCapability changes nothing`() {
        val text = PresenceNotificationBuilder.contentTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
            ringCapability = RingCapability(
                notificationsEnabled = true,
                channelAlerting = true,
                fullScreenIntentAllowed = true,
            ),
        )
        assertEquals("סטטוס: זמין", text)
    }

    private fun AppFailure.toHebrewMessageForPresence() = toHebrewMessage(FailureContext.PRESENCE)
}

/**
 * The masking that docs/android-presence-and-call-ux.md calls out by name — "It is worse
 * than 'coarse' on the notification — it can be absent entirely" — and then leaves in
 * place: contentTextFor is a first-match-wins chain, so a failed sync, a pending sync, or
 * blocked notifications each hide push-registration completely.
 *
 * These pin the property that actually matters and that the contentTextFor tests above
 * cannot express: no signal that is true may be UNSAYABLE. The collapsed line still obeys
 * the documented priority (those tests are unchanged); the expanded view carries the rest.
 */
class PresenceNotificationExpandedTextTest {

    private val blocked = RingCapability(
        notificationsEnabled = false,
        channelAlerting = true,
        fullScreenIntentAllowed = true,
    )
    private val lockedScreenGapOnly = RingCapability(
        notificationsEnabled = true,
        channelAlerting = true,
        fullScreenIntentAllowed = false,
    )
    private val healthy = RingCapability(
        notificationsEnabled = true,
        channelAlerting = true,
        fullScreenIntentAllowed = true,
    )

    // The exact combination the doc describes as unreadable: the device never registered
    // for push AND the sync is failing AND notifications are blocked. Before this, the
    // notification said only the first of the three.
    @Test
    fun `every simultaneously-true signal appears, not just the highest-priority one`() {
        val text = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Failed(AppFailure.NotSignedIn),
            pushRegistrationFailure = AppFailure.Unknown,
            ringCapability = blocked,
        )

        assertTrue(text.contains(AppFailure.NotSignedIn.toHebrewMessage(FailureContext.PRESENCE)))
        assertTrue(text.contains(AppFailure.Unknown.toHebrewMessage(FailureContext.PUSH_REGISTRATION)))
        // NOTIFICATIONS_BLOCKED_TEXT is private; assert on the only thing the caller can
        // see — that the blocked-notifications line is present and distinct from the two
        // failures above, i.e. three lines rather than two.
        assertEquals(3, text.lines().size)
    }

    // A pending sync masks push-registration exactly the same way a failed one does —
    // the doc counts it as one of the three maskers, and it is the easiest to hit
    // (every heartbeat passes through Pending).
    @Test
    fun `a pending sync no longer hides a push-registration failure`() {
        val text = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Pending,
            pushRegistrationFailure = AppFailure.Unknown,
        )

        assertTrue(text.contains(AppFailure.Unknown.toHebrewMessage(FailureContext.PUSH_REGISTRATION)))
        assertEquals(2, text.lines().size)
    }

    // build() attaches the BigText style only on a multi-line result, so "one line" is
    // the contract that keeps a healthy or single-problem notification unchanged.
    @Test
    fun `a healthy agent produces a single line identical to the collapsed text`() {
        val expanded = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
            ringCapability = healthy,
        )

        assertEquals(1, expanded.lines().size)
        assertEquals(
            PresenceNotificationBuilder.contentTextFor(
                AgentStatus.READY,
                PresenceSyncState.Synced,
                pushRegistrationFailure = null,
                ringCapability = healthy,
            ),
            expanded,
        )
    }

    // canRingOnLockedScreen is canAlert && fullScreenIntentAllowed, so a blocked device
    // satisfies BOTH ring conditions. Emitting both would tell the agent to fix two
    // things when only one is wrong.
    @Test
    fun `blocked notifications do not also emit the narrower locked-screen line`() {
        val text = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
            ringCapability = blocked,
        )

        assertEquals(2, text.lines().size)
    }

    @Test
    fun `the locked-screen gap alone still gets its own line`() {
        val text = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = null,
            ringCapability = lockedScreenGapOnly,
        )

        assertEquals(2, text.lines().size)
        assertNotEquals(
            PresenceNotificationBuilder.expandedTextFor(
                AgentStatus.READY,
                PresenceSyncState.Synced,
                pushRegistrationFailure = null,
                ringCapability = blocked,
            ),
            text,
        )
    }

    // The WHICH-step tag the whole push investigation turned on. The notification never
    // rendered it (docs: "It has no access to, and never renders, the WHICH-step detail
    // string"), so the two surfaces disagreed about the one fact being chased.
    @Test
    fun `the which-step push detail changes the text, so the notification can carry it`() {
        val withoutDetail = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = AppFailure.Unknown,
        )
        val fcmStep = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = AppFailure.Unknown,
            pushRegistrationDetail = "fcm_token: boom",
        )
        val voxStep = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = AppFailure.Unknown,
            pushRegistrationDetail = "vox_register: Timeout",
        )

        assertNotEquals(withoutDetail, fcmStep)
        assertNotEquals(withoutDetail, voxStep)
        assertNotEquals(fcmStep, voxStep)
        // Same wording as the in-app banner, from the same function — the two surfaces
        // saying different things about the same failure is the bug being closed here.
        assertTrue(fcmStep.contains(PresenceActions.pushFailureStageSuffix("fcm_token: boom").trim()))
    }

    // CONTRACT CHANGED: an untagged detail used to have to leave the text exactly as it
    // was. That is no longer true and must not be -- rendering identically to "no detail
    // at all" is what let a device that never started the SDK look like one whose message
    // we simply had not tagged. Both now say something, and something different.
    @Test
    fun `an unrecognised detail says so, and does not read as no detail at all`() {
        val untagged = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = AppFailure.Unknown,
            pushRegistrationDetail = "something nobody tagged",
        )
        val none = PresenceNotificationBuilder.expandedTextFor(
            AgentStatus.READY,
            PresenceSyncState.Synced,
            pushRegistrationFailure = AppFailure.Unknown,
        )

        assertNotEquals(none, untagged)
        assertTrue(untagged.length > none.length)
    }
}
