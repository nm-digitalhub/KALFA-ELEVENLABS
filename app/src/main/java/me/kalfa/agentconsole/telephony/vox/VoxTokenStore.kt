package me.kalfa.agentconsole.telephony.vox

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voximplant.android.sdk.core.AuthParams
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

// Persists the Voximplant AuthParams token pair so a push-woken app (no human
// present) can log in silently via loginWithAccessToken/refreshToken instead of the
// interactive one-time-key flow (AGENTS.md "Push wake-up").
//
// Storage choice: plain (unencrypted) DataStore Preferences, DELIBERATELY not
// androidx.security's EncryptedSharedPreferences. That library is deprecated, and
// AGENTS.md rule 6 asks that a new dependency be approved before adding it. The
// discriminating fact: this app's EXISTING dependency, supabase-kt's default Auth
// session manager, already persists the Supabase refresh token — a credential
// STRICTLY MORE POWERFUL than anything stored here, since it alone can mint a fresh
// Voximplant login hash via POST /api/agents/sdk-auth — in plain app-private
// SharedPreferences (verified: auth-kt-android 3.1.4's POM pulls
// multiplatform-settings-no-arg-android, whose Android no-arg Settings() factory
// wraps PreferenceManager.getDefaultSharedPreferences — unencrypted). Storing the
// Vox tokens at the SAME protection level as a strictly-more-powerful credential
// this app already ships is not a downgrade; wrapping only the WEAKER credential in
// stronger encryption while the more powerful one sits in plain storage next to it
// would be security theater, not defense in depth. androidx-datastore-preferences
// is already in the version catalog (unused until this change) — no new dependency.
private val Context.voxAuthDataStore by preferencesDataStore(name = "vox_auth")

private object Keys {
    val VOX_USERNAME = stringPreferencesKey("vox_username")
    val ACCESS_TOKEN = stringPreferencesKey("access_token")
    val ACCESS_EXPIRES_AT_MS = longPreferencesKey("access_expires_at_ms")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val REFRESH_EXPIRES_AT_MS = longPreferencesKey("refresh_expires_at_ms")
}

class VoxTokenStore(private val context: Context) {

    suspend fun save(voxUsername: String, authParams: AuthParams, nowMs: Long = System.currentTimeMillis()) {
        context.voxAuthDataStore.edit { prefs ->
            prefs[Keys.VOX_USERNAME] = voxUsername
            prefs[Keys.ACCESS_TOKEN] = authParams.accessToken
            prefs[Keys.ACCESS_EXPIRES_AT_MS] = resolveExpiryMs(authParams.accessTokenTimeExpired, nowMs)
            prefs[Keys.REFRESH_TOKEN] = authParams.refreshToken
            prefs[Keys.REFRESH_EXPIRES_AT_MS] = resolveExpiryMs(authParams.refreshTokenTimeExpired, nowMs)
        }
    }

    suspend fun load(): StoredVoxTokens? {
        val prefs = context.voxAuthDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        val voxUsername = prefs[Keys.VOX_USERNAME] ?: return null
        val accessToken = prefs[Keys.ACCESS_TOKEN] ?: return null
        val refreshToken = prefs[Keys.REFRESH_TOKEN] ?: return null
        return StoredVoxTokens(
            voxUsername = voxUsername,
            accessToken = accessToken,
            accessTokenExpiresAtMs = prefs[Keys.ACCESS_EXPIRES_AT_MS] ?: 0L,
            refreshToken = refreshToken,
            refreshTokenExpiresAtMs = prefs[Keys.REFRESH_EXPIRES_AT_MS] ?: 0L,
        )
    }

    /**
     * Records WHICH Voximplant identity this device belongs to, independently of
     * whether it has ever managed to log in as that identity.
     *
     * This exists because the identity used to be obtainable only as a by-product of
     * the login it is needed FOR. [save] is called exclusively after a successful
     * login step (all four call sites in VoxClientManager), [load] returns null unless
     * a full token pair is present, and `PresenceForegroundService.currentVoxUsername`
     * read `load()?.voxUsername`. So before the first successful login the service had
     * no username, and `PresenceActions.applyStatus`'s `voxUsername != null` guard
     * skipped login, registration AND the reporting — silently, on every
     * service-driven path, forever. Measured: across 2724 Voximplant sessions in three
     * days, no Android client has ever connected to this account.
     *
     * Written from `console_me.vox_username` when ConsoleViewModel reads identity, so
     * the source is the server's record of who the agent is rather than the residue of
     * a login. Deliberately a SEPARATE key pair from [load]/[save] rather than a
     * relaxation of them: `StoredVoxTokens` means "a complete, usable session", and
     * `planSilentLogin` depends on that meaning — a username with no tokens must still
     * plan `FallBackToInteractive`, which it does, because [load] still returns null.
     */
    suspend fun saveUsername(voxUsername: String) {
        context.voxAuthDataStore.edit { prefs ->
            prefs[Keys.VOX_USERNAME] = voxUsername
        }
    }

    /** The identity recorded by [saveUsername] or [save]. See [saveUsername]. */
    suspend fun loadUsername(): String? =
        context.voxAuthDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[Keys.VOX_USERNAME]

    // Explicit sign-out (ConsoleViewModel.logout): a signed-out device must not
    // remain silently loggable-in via a leftover Voximplant token, and must not
    // keep being included in the push-wake audience for an agent who signed out.
    // Clears the identity from saveUsername too — a different agent may sign in next.
    suspend fun clear() {
        context.voxAuthDataStore.edit { it.clear() }
    }
}
