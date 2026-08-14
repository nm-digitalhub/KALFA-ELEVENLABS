package me.kalfa.agentconsole.telephony.vox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Covers the two pieces of the push-wake-up silent-login chain that are pure
// logic: which AuthParams-expiry field value means what (resolveExpiryMs), and
// which of loginWithAccessToken / refreshToken / the interactive one-time-key
// flow to attempt for a given stored-token state (planSilentLogin) — plus the
// byte-verified push predicate (isVoximplantPush). See AGENTS.md "Push wake-up".
class VoxSilentLoginTest {
    private val now = 1_700_000_000_000L // fixed reference instant, ms

    @Test
    fun `resolveExpiryMs treats a small value as a duration in seconds from now`() {
        val oneHourSeconds = 3600
        assertEquals(now + 3600 * 1000L, resolveExpiryMs(oneHourSeconds, now))
    }

    @Test
    fun `resolveExpiryMs treats an implausibly large value as an absolute epoch-seconds timestamp`() {
        // now/1000 is itself ~1.7 billion — already over the duration threshold, so
        // using it directly (plus a small offset) exercises the "absolute" branch.
        val absoluteEpochSeconds = (now / 1000L).toInt() + 3600
        assertEquals(absoluteEpochSeconds.toLong() * 1000L, resolveExpiryMs(absoluteEpochSeconds, now))
    }

    @Test
    fun `planSilentLogin returns AlreadyLoggedIn when already logged in`() {
        assertEquals(
            SilentLoginPlan.AlreadyLoggedIn,
            planSilentLogin(now, isLoggedIn = true, stored = null, voxUsername = "agent1"),
        )
    }

    @Test
    fun `planSilentLogin falls back to interactive when nothing is stored`() {
        assertEquals(
            SilentLoginPlan.FallBackToInteractive,
            planSilentLogin(now, isLoggedIn = false, stored = null, voxUsername = "agent1"),
        )
    }

    @Test
    fun `planSilentLogin falls back to interactive for a leftover different vox_username`() {
        val tokens = tokensFor("agent1")
        assertEquals(
            SilentLoginPlan.FallBackToInteractive,
            planSilentLogin(now, isLoggedIn = false, stored = tokens, voxUsername = "agent2"),
        )
    }

    @Test
    fun `planSilentLogin uses the access token while well within its lifetime`() {
        val tokens = tokensFor("agent1", accessExpiresAtMs = now + 60_000, refreshExpiresAtMs = now + 3_600_000)
        assertEquals(
            SilentLoginPlan.UseAccessToken(tokens),
            planSilentLogin(now, isLoggedIn = false, stored = tokens, voxUsername = "agent1"),
        )
    }

    @Test
    fun `planSilentLogin refreshes when the access token is inside the expiry safety margin`() {
        val tokens = tokensFor("agent1", accessExpiresAtMs = now + 1_000, refreshExpiresAtMs = now + 3_600_000)
        assertEquals(
            SilentLoginPlan.UseRefreshToken(tokens),
            planSilentLogin(now, isLoggedIn = false, stored = tokens, voxUsername = "agent1"),
        )
    }

    @Test
    fun `planSilentLogin falls back to interactive once the refresh token has also expired`() {
        val tokens = tokensFor("agent1", accessExpiresAtMs = now - 10_000, refreshExpiresAtMs = now - 1_000)
        assertEquals(
            SilentLoginPlan.FallBackToInteractive,
            planSilentLogin(now, isLoggedIn = false, stored = tokens, voxUsername = "agent1"),
        )
    }

    @Test
    fun `isVoximplantPush matches only the SDK's own signature key`() {
        assertTrue(isVoximplantPush(mapOf("voximplant" to "1")))
        assertFalse(isVoximplantPush(mapOf("some_other_key" to "1")))
        assertFalse(isVoximplantPush(emptyMap()))
    }

    private fun tokensFor(
        voxUsername: String,
        accessExpiresAtMs: Long = now + 60_000,
        refreshExpiresAtMs: Long = now + 3_600_000,
    ) = StoredVoxTokens(
        voxUsername = voxUsername,
        accessToken = "access-$voxUsername",
        accessTokenExpiresAtMs = accessExpiresAtMs,
        refreshToken = "refresh-$voxUsername",
        refreshTokenExpiresAtMs = refreshExpiresAtMs,
    )
}
