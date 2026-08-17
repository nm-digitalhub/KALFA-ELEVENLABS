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

    // A rejected token pair and a login that ran out of time reach the SAME
    // `runCatching { … }.onFailure { … }` in VoxClientManager.tryRefreshThenAccessToken,
    // and only one of them is evidence about the tokens. Both bounded callers
    // (PresenceActions' 15s heartbeat budget, VoxFirebaseMessagingService's 9s) unwind
    // through the cancellation branch on a slow network.
    //
    // NARROWED, and these assertions moved with it: this used to accept ANY
    // non-cancellation throwable as proof, including a bare IllegalStateException.
    // That is what made a network blip cost the device its identity — the tokens
    // were discarded on evidence the platform never gave. The predicate now reads
    // VoxAuthException.Sdk.credentialRejected, which VoxClientManager sets from
    // LoginError.rejectsStoredCredential(): true for TokenExpired and
    // InvalidPassword, false for NetworkIssues / Timeout / MauAccessDenied /
    // AccountFrozen / InternalError and the rest.
    //
    // Both directions are pinned deliberately. Asserting only the `true` case would
    // let the predicate widen back to "anything that is not a cancellation" without
    // a single test turning red, which is precisely the regression this narrowing
    // was written to prevent.
    @Test
    fun `only a flagged credential rejection proves the stored tokens are dead`() {
        assertTrue(
            "an SDK error the platform flagged as rejecting the credential IS evidence",
            refreshFailureProvesTokensDead(
                VoxAuthException.Sdk("refreshToken: TokenExpired", credentialRejected = true),
            ),
        )
    }

    @Test
    fun `an unflagged SDK failure proves nothing and must not discard the tokens`() {
        // The same exception TYPE as the case above, differing only in the flag —
        // a transport-level failure reaching the same catch as a real rejection.
        assertFalse(
            refreshFailureProvesTokensDead(VoxAuthException.Sdk("refreshToken: NetworkIssues")),
        )
    }

    @Test
    fun `a throwable from outside the SDK boundary proves nothing about the tokens`() {
        assertFalse(refreshFailureProvesTokensDead(IllegalStateException("anything else")))
    }

    @Test
    fun `a cancelled login attempt proves nothing and must not discard the tokens`() {
        assertFalse(
            refreshFailureProvesTokensDead(
                kotlin.coroutines.cancellation.CancellationException("timed out"),
            ),
        )
    }

    // TimeoutCancellationException is what withTimeoutOrNull actually throws, and it is
    // a SUBCLASS of CancellationException — pinned separately because an `==`-style
    // check, or a guard written against the wrong type, would pass the test above and
    // still discard live credentials on every slow heartbeat tick.
    @Test
    fun `the concrete exception withTimeoutOrNull throws is treated as a cancellation`() {
        val thrown = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(10_000) }
            }
        }.exceptionOrNull()
        assertTrue("expected withTimeout to throw", thrown != null)
        assertFalse(refreshFailureProvesTokensDead(thrown!!))
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
