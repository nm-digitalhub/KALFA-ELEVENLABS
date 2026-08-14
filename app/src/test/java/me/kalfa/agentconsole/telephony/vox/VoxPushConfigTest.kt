package me.kalfa.agentconsole.telephony.vox

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the silent push failure that cost a full day on 2026-08-14, and which looks
 * like the opposite of a bug in code review.
 *
 * `PushConfig`'s second parameter is a bundle id, and passing the app's own
 * `applicationId` reads as obviously correct — more precise, more explicit, clearly
 * "ours". It is the wrong value here, and it fails in the worst possible way: the SDK
 * accepts it, `registerForPushNotifications` reports success, the agent's app shows no
 * error, and the Voximplant platform simply never has a usable token. The only visible
 * symptom is on the server side, hours later, as `push_results: []` and
 * `"No push notifications has been sent"` on a call that was never delivered.
 *
 * Voximplant's own SDK v3 reference (`references.androidsdk3.android.sdk.core.pushconfig`,
 * fetched live) documents the parameter as nullable and says, verbatim:
 *
 *   "Set **only** if push notifications are going to be sent across several Android apps
 *    via a single Voximplant application or if you add several push certificates."
 *
 * As of this test: one Voximplant application (`kalfa-rsvp`, 11107202), one Android app,
 * and exactly one push certificate — `npm run voximplant -- push-credentials` returns a
 * single GOOGLE entry (#9108) carrying a `sender_id` and no bundle id. The platform
 * matches a registration to a certificate by bundle id, so registering under a bundle no
 * certificate declares leaves nothing sendable.
 *
 * **If this test ever needs to change**, it is because a second Android app or a second
 * push certificate was added to the same Voximplant application. In that case the bundle
 * id becomes required, and the certificate must be uploaded WITH the matching package
 * name at the same time. Those are one decision, not two: setting either side alone
 * silently breaks push again, in exactly this way.
 */
class VoxPushConfigTest {

    @Test
    fun `push registrations carry no bundle id`() {
        assertNull(
            "PushConfig.bundleId must stay null while this Voximplant application has a " +
                "single push certificate — see this class's kdoc before changing it",
            VoxClientManager.PUSH_BUNDLE_ID,
        )
    }
}
