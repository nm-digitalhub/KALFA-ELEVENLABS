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
 * `runCatching { … }.onFailure { … }` cannot tell the two apart on its own, and they
 * are not the same fact. A `LoginError` from the platform is evidence: the refresh
 * token was presented and rejected, so keeping it only buys a re-failure on every
 * future call. A cancellation is the absence of evidence — `PresenceActions
 * .ensurePushRegistration` bounds the whole login at 15s and
 * `VoxFirebaseMessagingService` at 9s, so a pocketed phone on a slow network unwinds
 * here having never heard back. The tokens may be perfectly good; nothing asked them.
 *
 * MEASURED, so the guard is not mistaken for the thing that fixes the timeout case:
 * a cancellation does NOT currently reach the store anyway. `onFailure`'s handler is
 * entered and its non-suspending statements run, but `VoxTokenStore.clearTokens` is a
 * suspend function whose body is a DataStore `edit`, and suspending on an
 * already-cancelling Job throws before the write lands. Verified with a JVM probe of
 * this exact shape (`withTimeoutOrNull` around `runCatching { delay } .onFailure {
 * withContext { … } }`): handler entered true, plain statement ran true, suspending
 * statement ran FALSE.
 *
 * So this guard changes no behaviour today. It is written anyway because the thing
 * making the timeout case safe is an accident of where the suspension point falls —
 * one non-suspending line added to the top of a cleanup function, or a cancellation
 * arriving a moment later, and it silently starts discarding live credentials on
 * every slow tick. Depending on that is not the same as deciding it.
 */
fun refreshFailureProvesTokensDead(error: Throwable): Boolean =
    error !is kotlin.coroutines.cancellation.CancellationException

// The FCM data-message signature the SDK itself checks — BYTE-VERIFIED against
// android-sdk-core 3.2.0's PushManager.handlePushNotification$lambda$9 (javap -c on
// the shipped AAR): the exact bytecode is `map.get("voximplant")` cast to String,
// logging "invalid message (not voximplant)" when absent. Mirrored here so the app
// can skip logging in and touching the SDK for a push that was never going to
// resolve to a call, instead of trusting prose-docs wording ("payload has a
// 'voximplant' signature") without checking the literal key.
fun isVoximplantPush(data: Map<String, String>): Boolean = data["voximplant"] != null
