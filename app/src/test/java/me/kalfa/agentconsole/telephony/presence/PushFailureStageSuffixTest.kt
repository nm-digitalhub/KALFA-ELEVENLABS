package me.kalfa.agentconsole.telephony.presence

import me.kalfa.agentconsole.telephony.vox.VoxAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The banner titled "המכשיר לא נרשם לקבלת שיחות" is published for THREE different failure
 * domains, and until now could name only two of them.
 *
 * `PresenceActions.applyStatus` reports an `ensureLoggedIn` failure through
 * `reportPushRegistrationResult` — the same banner — so a Voximplant *login* failure has
 * always been shown to the agent as a *registration* failure, with the one field that
 * distinguishes them dropped by the old `else -> ""`. The banner the owner photographed
 * on 2026-08-14 was bare, and a bare banner is exactly what that produced.
 *
 * Every literal asserted here is one this codebase itself emits; each test names where.
 */
class PushFailureStageSuffixTest {

    private val loginStage = " ההתחברות למערכת הטלפוניה נכשלה, ולכן הרישום כלל לא בוצע."

    @Test
    fun `every SDK login stage VoxClientManager tags is named as a login failure`() {
        // The five literals VoxClientManager wraps its SDK callbacks with:
        // "connect: $error" (connectSuspend), "requestOneTimeKey: $error",
        // "loginWithOneTimeKey: $error", "loginWithAccessToken: $error",
        // "refreshToken: $error".
        listOf(
            "connect: ConnectionError.NetworkIssues",
            "requestOneTimeKey: LoginError.InvalidUsername",
            "loginWithOneTimeKey: LoginError.InvalidPassword",
            "loginWithAccessToken: LoginError.TokenExpired",
            "refreshToken: LoginError.TokenExpired",
            // A login that never returned. PresenceActions bounds the heartbeat's
            // attempt with withTimeoutOrNull and reports the timeout rather than letting
            // it unwind silently -- a hung SDK callback would otherwise park the
            // heartbeat loop and freeze agent_status.updated_at, which is the exact
            // symptom the whole presence effort exists to prevent.
            "login_timeout: no SDK response in 15000ms",
        ).forEach { detail ->
            assertEquals(
                "expected a login-stage sentence for: $detail",
                loginStage,
                PresenceActions.pushFailureStageSuffix(detail),
            )
        }
    }

    @Test
    fun `the sdk-auth exchange failures are login failures too`() {
        // These fail BEFORE the Voximplant SDK is touched at all, and carry no prefix.
        // NoSession is referenced through the object so a reworded message cannot
        // silently stop matching; the rest are the literals in VoxTelephony.kt:68-70,96.
        listOf(
            VoxAuthException.NoSession.message,
            "not a console agent (sdk-auth 401)",
            "agent has no Voximplant identity (sdk-auth 409)",
            "sdk-auth HTTP 500",
            "sdk-auth 200 without a hash",
        ).forEach { detail ->
            assertEquals(
                "expected a login-stage sentence for: $detail",
                loginStage,
                PresenceActions.pushFailureStageSuffix(detail),
            )
        }
    }

    @Test
    fun `the two registration stages keep their own distinct sentences`() {
        val fcm = PresenceActions.pushFailureStageSuffix("fcm_token: SERVICE_NOT_AVAILABLE")
        val vox = PresenceActions.pushFailureStageSuffix("vox_register: Timeout")
        val alsoVox =
            PresenceActions.pushFailureStageSuffix("registerForPushNotifications: Timeout")

        assertTrue(fcm.isNotBlank())
        assertEquals(vox, alsoVox)
        // The whole point is that an agent (and whoever they relay to) can tell the three
        // domains apart. Any two of them collapsing would defeat it.
        val noIdentity =
            PresenceActions.pushFailureStageSuffix("no_device_identity: vox username unknown")
        assertEquals(4, setOf(fcm, vox, loginStage, noIdentity).size)
    }


    @Test
    fun `a device with no known identity is named as such, not as a login failure`() {
        // The fourth domain: nothing was attempted, because the device does not yet
        // know which Voximplant identity it is. PresenceActions.applyStatus emits these
        // two details from its else-branch — the branch that did not exist, and whose
        // absence made the whole defect silent.
        val noIdentity =
            PresenceActions.pushFailureStageSuffix("no_device_identity: vox username unknown")
        val noClient =
            PresenceActions.pushFailureStageSuffix("no_device_identity: telephony client unavailable")

        assertTrue(noIdentity.isNotBlank())
        assertEquals(noIdentity, noClient)
        // Must NOT collapse into the login sentence: claiming a login failed when none
        // was attempted is exactly the guess this function refuses to make.
        assertNotEquals(loginStage, noIdentity)
    }


    @Test
    fun `an unrecognised message names itself instead of going quiet`() {
        // CONTRACT CHANGED, deliberately. This used to assert "" -- "adds nothing rather
        // than guessing" -- and that was right while the alternative was inventing a
        // cause. It stopped being right once we learned the silence itself was the
        // problem: a bare banner is indistinguishable from a device that never started
        // the SDK, which is exactly what the owner was looking at for weeks.
        //
        // The new contract keeps the honest half -- it still does not guess a cause --
        // and drops the harmful half. "unrecognised" IS the truth, and the screenshot
        // is what makes it useful to whoever reads it next.
        val unrecognised = PresenceActions.pushFailureStageSuffix("something nobody tagged")
        assertTrue(unrecognised.isNotBlank())
        assertEquals(unrecognised, PresenceActions.pushFailureStageSuffix(""))
        // ...and it must not be mistaken for a cause we actually identified.
        assertNotEquals(loginStage, unrecognised)
    }
}
