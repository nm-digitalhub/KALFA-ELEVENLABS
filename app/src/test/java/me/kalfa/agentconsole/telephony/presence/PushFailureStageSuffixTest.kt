package me.kalfa.agentconsole.telephony.presence

import me.kalfa.agentconsole.telephony.vox.VoxAuthException
import org.junit.Assert.assertEquals
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
        assertEquals(3, setOf(fcm, vox, loginStage).size)
    }

    @Test
    fun `an unrecognised message still adds nothing rather than guessing`() {
        // The contract the original two branches were written under, unchanged: the new
        // branch matches only strings this codebase emits, it is not a catch-all.
        assertEquals("", PresenceActions.pushFailureStageSuffix("something nobody tagged"))
        assertEquals("", PresenceActions.pushFailureStageSuffix(null))
        assertEquals("", PresenceActions.pushFailureStageSuffix(""))
    }
}
