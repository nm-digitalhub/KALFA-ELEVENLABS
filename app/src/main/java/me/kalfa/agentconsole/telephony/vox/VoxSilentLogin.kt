package me.kalfa.agentconsole.telephony.vox

// Pure decision/data helpers for the persisted-token silent-login path (push
// wake-up, AGENTS.md "Push wake-up"). Deliberately has ZERO Android/Voximplant-SDK
// imports so it is unit-testable with no device or Robolectric — same
// separation-for-testability reasoning as VoxSdkAuthClient in VoxTelephony.kt.

/** One saved Voximplant session, scoped to the identity it belongs to. */
data class StoredVoxTokens(
    val voxUsername: String,
    val accessToken: String,
    val accessTokenExpiresAtMs: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtMs: Long,
)

// Subtracted from the real expiry so a token that is ABOUT to expire is never used
// to start a login that may complete after the server has already rejected it.
const val VOX_TOKEN_EXPIRY_SAFETY_MARGIN_MS = 30_000L

// AuthParams.accessTokenTimeExpired / refreshTokenTimeExpired are typed Int with no
// unit confirmed for the Android v3 SDK specifically (Voximplant's own guide
// documents the analogous Web SDK field as "accessExpire, seconds" — a DURATION —
// but that is inference across SDKs, not a confirmed v3-Android contract, and the
// live Android API reference could not be crawled to confirm it — see the push-wake
// handoff report). Rather than guess and risk being silently wrong in one
// direction, disambiguate from the magnitude at the one place a value is saved:
// interpreted as a duration in seconds, a value over ~31.7 years is not a plausible
// token lifetime, so a value that large must already be an absolute epoch-seconds
// timestamp; anything smaller is added to "now". Guessing wrong here is never a
// security downgrade — worst case is one avoidable refresh-then-retry.
private const val IMPLAUSIBLE_DURATION_SECONDS = 1_000_000_000L // ~31.7 years

fun resolveExpiryMs(rawExpirySeconds: Int, nowMs: Long): Long {
    val raw = rawExpirySeconds.toLong()
    return if (raw > IMPLAUSIBLE_DURATION_SECONDS) {
        raw * 1000L // already an absolute epoch-seconds timestamp
    } else {
        nowMs + raw * 1000L // a duration in seconds from now
    }
}

sealed interface SilentLoginPlan {
    data object AlreadyLoggedIn : SilentLoginPlan
    data class UseAccessToken(val tokens: StoredVoxTokens) : SilentLoginPlan
    data class UseRefreshToken(val tokens: StoredVoxTokens) : SilentLoginPlan
    data object FallBackToInteractive : SilentLoginPlan
}

// The fallback chain: loginWithAccessToken -> refreshToken (+retry) -> the existing
// interactive one-time-key flow. Stored tokens for a DIFFERENT vox_username than the
// one being logged in as are never used — the guide's rule to re-register "when
// another Voximplant user logs in on the device" implies the same identity check on
// the READ side: a leftover session for someone else must not silently authenticate
// a different agent's login.
fun planSilentLogin(
    nowMs: Long,
    isLoggedIn: Boolean,
    stored: StoredVoxTokens?,
    voxUsername: String,
): SilentLoginPlan {
    if (isLoggedIn) return SilentLoginPlan.AlreadyLoggedIn
    if (stored == null || stored.voxUsername != voxUsername) return SilentLoginPlan.FallBackToInteractive
    return when {
        nowMs < stored.accessTokenExpiresAtMs - VOX_TOKEN_EXPIRY_SAFETY_MARGIN_MS ->
            SilentLoginPlan.UseAccessToken(stored)
        nowMs < stored.refreshTokenExpiresAtMs - VOX_TOKEN_EXPIRY_SAFETY_MARGIN_MS ->
            SilentLoginPlan.UseRefreshToken(stored)
        else -> SilentLoginPlan.FallBackToInteractive
    }
}

/**
 * Whether a failed silent-login attempt has actually learned that the stored tokens
 * are dead, and may therefore throw them away.
 *
 * ALLOW-LIST, not a deny-list, and that inversion is the whole design. The first
 * version of this was `error !is CancellationException`, which reasoned only about
 * the enclosing timeouts and treated everything else as proof. It is not: the SDK
 * reports ten distinct `LoginError` values through one callback, and only two of them
 * say anything about the credential we stored. Descriptions quoted from the live
 * reference (`getDoc?fqdn=references.androidsdk3.android.sdk.core.loginerror`), value
 * list byte-verified against the shipped android-sdk-core 3.2.0 AAR:
 *
 *   EVIDENCE — the server answered and refused THIS credential:
 *     TokenExpired      "Token expired"
 *     InvalidPassword   "Invalid password or token"
 *
 *   NOT EVIDENCE — the server answered, but about something else entirely:
 *     AccountFrozen     "User account is frozen"           — nothing to do with the token
 *     InvalidUsername   "Invalid username"
 *     MauAccessDenied   "MAU limit is reached. Payment is required"
 *
 *   NOT EVIDENCE — we never got an answer at all:
 *     NetworkIssues     "Login is failed due to network problem"
 *     Timeout           "Timeout"
 *     Interrupted       "Operation has been interrupted by another client operation"
 *     InternalError     "Internal error"
 *     InvalidState      "…not connected, currently logging or already logged in"
 *
 * `MauAccessDenied` is the case that settles the direction of the default. Clearing
 * there would force the next attempt down the interactive one-time-key path — which
 * is a NEW login, and a MAU ceiling is precisely what blocks new logins. Discarding
 * on it makes recovery strictly harder, on a credential the platform never said a
 * word against.
 *
 * The asymmetry that decides every ambiguous case: wrongly KEEPING a dead token costs
 * one extra failed attempt on the next wake. Wrongly CLEARING a live one costs the
 * silent-login path entirely and forces an interactive login, which needs the app
 * open — see ConsoleViewModel.loadIdentity for how little is guaranteed there. So
 * anything that is not an explicit credential rejection returns false, including the
 * two ambiguous ones.
 *
 * On the cancellation case this replaces: it is still covered, because a cancellation
 * is not a `VoxAuthException.Sdk` and so cannot be tagged. Worth keeping the
 * measurement on record — a cancellation does not currently reach the store anyway.
 * `onFailure`'s handler IS entered and its non-suspending statements run, but
 * `VoxTokenStore.clearTokens` is a suspend function whose body is a DataStore `edit`,
 * and suspending on an already-cancelling Job throws before the write lands. JVM probe
 * of that exact shape: handler entered true, plain statement ran true, suspending
 * statement ran FALSE. The SDK's OWN timeout is the one that actually mattered here —
 * `LoginError.Timeout` arrives as a plain exception through a live coroutine, where
 * nothing masks it.
 */
fun refreshFailureProvesTokensDead(error: Throwable): Boolean =
    error is VoxAuthException.Sdk && error.credentialRejected

// The FCM data-message signature the SDK itself checks — BYTE-VERIFIED against
// android-sdk-core 3.2.0's PushManager.handlePushNotification$lambda$9 (javap -c on
// the shipped AAR): the exact bytecode is `map.get("voximplant")` cast to String,
// logging "invalid message (not voximplant)" when absent. Mirrored here so the app
// can skip logging in and touching the SDK for a push that was never going to
// resolve to a call, instead of trusting prose-docs wording ("payload has a
// 'voximplant' signature") without checking the literal key.
fun isVoximplantPush(data: Map<String, String>): Boolean = data["voximplant"] != null
