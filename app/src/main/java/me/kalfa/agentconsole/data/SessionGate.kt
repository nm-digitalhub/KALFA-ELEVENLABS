package me.kalfa.agentconsole.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────────────────────
// Why every console read and every Realtime join in this package is gated on a
// settled, signed-in session.
//
// supabase-kt does NOT wait for the Auth plugin before sending a request, and it does
// not fail when there is no user token — it quietly substitutes the anon key. Verified
// against the 3.1.4 sources this app actually resolves (auth-kt-3.1.4-sources.jar,
// io/github/jan/supabase/auth/AccessToken.kt):
//
//     suspend fun SupabaseClient.resolveAccessToken(
//         jwtToken: String? = null,
//         keyAsFallback: Boolean = true
//     ): String? {
//         val key = if(keyAsFallback) supabaseKey else null
//         return jwtToken ?: accessToken?.invoke()
//         ?: pluginManager.getPluginOrNull(Auth)?.currentAccessTokenOrNull() ?: key
//     }
//
// AuthenticatedSupabaseApi calls that for every postgrest request;
// Auth.currentSessionOrNull() returns null for every SessionStatus except Authenticated;
// and AuthImpl starts _sessionStatus at Initializing and loads the stored session in a
// detached authScope.launch. So on a cold start there is a window — disk read, plus a
// network refresh when the stored access token has expired — in which every read this
// app makes goes out as the anon role, with nothing anywhere waiting for it to close.
//
// MEASURED against the live project (kalfa-event-magic, 2026-08-14): `anon` holds no
// SELECT on ANY relation this app reads. has_table_privilege('anon', …, 'SELECT') is
// false, and the same call for 'authenticated' is true, for all ten of console_events,
// console_call_feed, console_rsvp_results, console_campaigns, console_campaign_targets,
// console_event_guests, console_call_analysis, console_me, agent_status and
// call_dispatch_status.
//
// So the window does not degrade to an empty list — it produces `permission denied`,
// which supabase-kt raises as PostgrestRestException: an ordinary Exception, NOT an
// IOException (HttpRequestException is the only IOException it throws), so
// Throwable.toAppFailure maps it to AppFailure.Unknown and FailureMessages renders
// "הפעולה לא הושלמה. נסה שוב." on a full-screen error — over data the agent is entitled
// to. A second later the same read succeeds, which is why pressing "נסה שוב" worked and
// why this looked like an intermittent server problem rather than a client-side race.
//
// Realtime has the same disease with a worse prognosis. Realtime.Config.accessToken
// defaults to `resolveAccessToken(realtime, keyAsFallback = false)`, so a channel joined
// too early carries no access_token at all and falls back to the anon `apikey` already
// in the socket URL. That one cannot heal: RErrorEvent only writes a log line, and
// RealtimeImpl.setAuth pushes a refreshed token ONLY to channels already in
// Status.SUBSCRIBED. A channel that joined unauthenticated is never re-authed and never
// re-joined, for the life of the process.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * "The session just became usable" — one emission per transition INTO signed-in, and
 * none for the token refreshes of a session that was already signed in.
 *
 * Deliberately a plain `Flow<Boolean>` extension with no Supabase types in its signature,
 * for the same reason as `shiftEndedAfterBeingActive` over in telephony/presence: it
 * makes the rule directly unit-testable with no Android, no network and no fake client.
 *
 * `distinctUntilChanged` is the load-bearing half. `Auth.sessionStatus` re-emits
 * `Authenticated` carrying a NEW `UserSession` on every background token refresh, and the
 * callers below use each emission to (re)join a Realtime channel — `subscribe()` on an
 * already-joined channel would send a duplicate JOIN roughly once an hour.
 */
internal fun Flow<Boolean>.enteredSignedIn(): Flow<Unit> =
    distinctUntilChanged().filter { it }.map { }

/**
 * [enteredSignedIn] over this client's own session status.
 *
 * A new collector is replayed `sessionStatus`'s current value, so a repository
 * constructed AFTER sign-in (as ConsoleViewModel's are) still fetches immediately — the
 * gate only delays the ones constructed before it, which is the whole point.
 */
internal fun SupabaseClient.signedInSessions(): Flow<Unit> =
    auth.sessionStatus.map { it is SessionStatus.Authenticated }.enteredSignedIn()

/**
 * Whether a request issued right now would carry the agent's own JWT rather than the
 * anon key. Cheap and synchronous — it reads the current `sessionStatus` value, exactly
 * as `resolveAccessToken` is about to.
 *
 * Used to gate the polling loops, which otherwise keep firing anon reads after a sign-out
 * for as long as the process lives.
 */
internal fun SupabaseClient.hasUserSession(): Boolean =
    auth.currentAccessTokenOrNull() != null

/**
 * "The session just stopped being usable" — one emission per transition OUT of signed-in,
 * and never the `false` a fresh collector is handed on subscription.
 *
 * The mirror of [enteredSignedIn], and needed because gating reads is only half of
 * session hygiene: the rows already in memory outlive the session that was allowed to
 * read them. These repositories are singletons on `DependencyContainer` and survive a
 * sign-out/sign-in inside one process, so without a clear on the way out, the next agent
 * to sign in is shown the previous agent's data.
 *
 * The window is not theoretical and it is not only the fetch round-trip.
 * `refreshEventNames` returns early when `_eventNames` is already populated, so the
 * sign-in fetch does NOT refresh it — the previous agent's event list stays on the Events
 * screen until the 60s foreground poll replaces it. Live calls, history, campaigns, RSVP
 * results and any already-requested targets/guest phone numbers are all visible for the
 * shorter fetch window.
 *
 * `dropWhile { !it }` is what keeps a cold start quiet: `sessionStatus` begins at
 * `Initializing`, which maps to `false`, and a process that has never been signed in has
 * nothing to clear. Same idiom, for the same reason, as `shiftEndedAfterBeingActive` in
 * telephony/presence — a `filter { !it }` alone would fire on the value every new
 * collector is replayed.
 */
internal fun Flow<Boolean>.leftSignedIn(): Flow<Unit> =
    distinctUntilChanged().dropWhile { !it }.filter { !it }.map { }

/**
 * [leftSignedIn] over this client's own session status.
 *
 * Note this fires for `RefreshFailure` as well as a real sign-out, which is correct for
 * clearing: a session that could not be refreshed is one whose reads would now go out as
 * `anon`, so continuing to display its rows claims a freshness the app cannot back. The
 * data returns on the next successful sign-in via [signedInSessions].
 */
internal fun SupabaseClient.signedOutSessions(): Flow<Unit> =
    auth.sessionStatus.map { it is SessionStatus.Authenticated }.leftSignedIn()
