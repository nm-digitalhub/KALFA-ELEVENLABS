package me.kalfa.agentconsole.data

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the cold-start defect described in SessionGate.kt: every console
 * read and every Realtime join fired at construction time, before the Supabase session
 * had loaded, and supabase-kt silently substituted the anon key — which holds no SELECT
 * on any relation this app reads (measured against the live project). The agent was shown
 * "הפעולה לא הושלמה. נסה שוב." over their own data, and "נסה שוב" fixed it because by then
 * the session had settled.
 *
 * Pure flow logic, so it is asserted directly with no Android, no Supabase client and no
 * network on the classpath — the same approach as PresenceShiftWatcherTest.
 */
class SessionGateTest {

    @Test
    fun `does not fire while the session is still initializing`() = runTest {
        // Exactly Auth.sessionStatus on a cold process: AuthImpl starts _sessionStatus at
        // SessionStatus.Initializing and loads the stored session in a detached coroutine.
        val signedIn = MutableStateFlow(false)
        var fetches = 0
        backgroundScope.launch { signedIn.enteredSignedIn().collect { fetches++ } }

        runCurrent()

        // This is where the anon-key reads used to go out.
        assertEquals(0, fetches)
    }

    @Test
    fun `fires once the session settles as signed in`() = runTest {
        val signedIn = MutableStateFlow(false)
        var fetches = 0
        backgroundScope.launch { signedIn.enteredSignedIn().collect { fetches++ } }

        runCurrent()
        signedIn.value = true
        runCurrent()

        assertEquals(1, fetches)
    }

    @Test
    fun `fires immediately for a collector that arrives already signed in`() = runTest {
        // The repositories ConsoleViewModel constructs are built after AuthGate has let
        // the app through, so they must not be made to wait for a transition that already
        // happened. A StateFlow replays its current value, and distinctUntilChanged always
        // passes its first emission.
        val signedIn = MutableStateFlow(true)
        var fetches = 0
        backgroundScope.launch { signedIn.enteredSignedIn().collect { fetches++ } }

        runCurrent()

        assertEquals(1, fetches)
    }

    @Test
    fun `a token refresh of an already-signed-in session does not re-fire`() = runTest {
        // Auth.sessionStatus re-emits Authenticated with a NEW UserSession on every
        // background token refresh. Each emission here (re)joins a Realtime channel, and
        // subscribe() on an already-joined channel sends a duplicate JOIN — so the
        // repeated `true`s must collapse. This is what distinctUntilChanged buys.
        assertEquals(
            listOf(Unit),
            flowOf(false, true, true, true).enteredSignedIn().toList(),
        )
    }

    @Test
    fun `signing out and back in fires again`() = runTest {
        // Not cosmetic: supabase-kt drops the Realtime socket on session loss
        // (disconnectOnSessionLoss defaults to true) and never reconnects on its own, so
        // the second sign-in is the only thing that can re-join the feed.
        assertEquals(
            listOf(Unit, Unit),
            flowOf(false, true, false, true).enteredSignedIn().toList(),
        )
    }

    // ── leftSignedIn: the clear-on-the-way-out half ───────────────────────────────────
    //
    // Gating reads governs what the NEXT agent may request; it says nothing about the
    // rows already in memory. The repositories are singletons on DependencyContainer and
    // survive a sign-out/sign-in inside one process, so the previous agent's data is
    // still in the flows when the next agent's first frame renders.

    @Test
    fun `a cold start clears nothing`() = runTest {
        // sessionStatus begins at Initializing, which maps to false. A process that has
        // never been signed in has nothing to clear, and a filter { !it } alone would
        // fire here — on the value every new collector is replayed.
        val signedIn = MutableStateFlow(false)
        var clears = 0
        backgroundScope.launch { signedIn.leftSignedIn().collect { clears++ } }

        runCurrent()

        assertEquals(0, clears)
    }

    @Test
    fun `signing out clears exactly once`() = runTest {
        val signedIn = MutableStateFlow(false)
        var clears = 0
        backgroundScope.launch { signedIn.leftSignedIn().collect { clears++ } }

        runCurrent()
        signedIn.value = true
        runCurrent()
        assertEquals(0, clears)

        signedIn.value = false
        runCurrent()

        assertEquals(1, clears)
    }

    @Test
    fun `a token refresh of a live session never clears`() = runTest {
        // The failure this guards is the ugly one: dropping the agent's own data out from
        // under them mid-shift, on the hourly refresh cadence, for no reason.
        assertEquals(
            emptyList<Unit>(),
            flowOf(false, true, true, true).leftSignedIn().toList(),
        )
    }

    @Test
    fun `each sign-out clears, and a later sign-in does not`() = runTest {
        assertEquals(
            listOf(Unit, Unit),
            flowOf(false, true, false, true, false).leftSignedIn().toList(),
        )
    }

    // ── settledSignedIn: the narrowing that keeps a lost signal from wiping the cache ──
    //
    // Initializing and RefreshFailure both mean "we do not know yet", not "signed out".
    // Mapping them to false made every one look like a sign-out to leftSignedIn and like a
    // fresh sign-in to enteredSignedIn. RefreshFailure is the one that bites: it is what a
    // phone in a pocket with no signal produces during the 80%-of-lifetime token refresh,
    // and it used to wipe cached guest phone numbers on the way through.

    @Test
    fun `an unknown status produces no emission at all, in either direction`() = runTest {
        val statuses = flowOf(
            SessionStatus.Initializing,
            SessionStatus.RefreshFailure(
                RefreshFailureCause.NetworkError(java.io.IOException("no signal")),
            ),
        )

        assertEquals(emptyList<Boolean>(), statuses.settledSignedIn().toList())
    }

    @Test
    fun `a refresh failure mid-session neither clears nor re-fetches`() = runTest {
        // The exact sequence a backgrounded phone produces when it loses signal and gets
        // it back: authenticated, refresh fails, refresh succeeds. Nothing should move.
        val statuses = flowOf(
            authenticated(),
            SessionStatus.RefreshFailure(
                RefreshFailureCause.NetworkError(java.io.IOException("no signal")),
            ),
            authenticated(),
        )

        // distinctUntilChanged collapses the two `true`s once the failure is filtered out,
        // so there is no second sign-in either -- which also avoids re-subscribing a
        // Realtime channel that was never disconnected (RealtimeImpl only disconnects on
        // NotAuthenticated).
        assertEquals(emptyList<Unit>(), statuses.settledSignedIn().leftSignedIn().toList())
        assertEquals(listOf(Unit), statuses.settledSignedIn().enteredSignedIn().toList())
    }

    @Test
    fun `a real sign-out still clears`() = runTest {
        // The 4xx refresh path calls clearSession() and so arrives as NotAuthenticated,
        // not RefreshFailure -- so this covers it as well as an explicit sign-out.
        val statuses = flowOf(
            authenticated(),
            SessionStatus.NotAuthenticated(isSignOut = true),
        )

        assertEquals(listOf(Unit), statuses.settledSignedIn().leftSignedIn().toList())
    }

    @Test
    fun `a cold start that was never signed in clears nothing`() = runTest {
        val statuses = flowOf(
            SessionStatus.Initializing,
            SessionStatus.NotAuthenticated(isSignOut = false),
        )

        assertEquals(emptyList<Unit>(), statuses.settledSignedIn().leftSignedIn().toList())
    }

    private fun authenticated() = SessionStatus.Authenticated(
        UserSession(
            accessToken = "access",
            refreshToken = "refresh",
            expiresIn = 3600,
            tokenType = "bearer",
        ),
    )
}
