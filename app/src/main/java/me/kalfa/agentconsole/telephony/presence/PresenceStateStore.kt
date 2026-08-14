package me.kalfa.agentconsole.telephony.presence

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.AgentStatus

// Persists just enough of "was this agent on shift" to survive a system-initiated
// process kill (NOT a force-stop — see docs/android-presence-and-call-ux.md §1,
// "System kill under memory pressure"). PresenceForegroundService writes to this on
// every status/shift change it observes and reads it back on a START_STICKY restart
// with a null Intent, when the in-memory AgentPresence StateFlows this app normally
// relies on have already reset to their process-start defaults.
//
// Same storage choice as VoxTokenStore, for the same reason (see its kdoc): plain
// DataStore Preferences, not androidx.security — this record is strictly LESS
// sensitive than the Voximplant token pair already stored at this protection level
// (an agent-status enum and a shift boolean, not a credential).
private val Context.presenceStateDataStore by preferencesDataStore(name = "presence_state")

private object Keys {
    val STATUS = stringPreferencesKey("status")
    val SHIFT_ACTIVE = booleanPreferencesKey("shift_active")
    val VOX_USERNAME = stringPreferencesKey("vox_username")
    val PUSH_REG_FAILURE = stringPreferencesKey("push_registration_failure")
    val PUSH_REG_FAILURE_AT_MS = longPreferencesKey("push_registration_failure_at_ms")
    val PUSH_REG_FAILURE_DETAIL = stringPreferencesKey("push_registration_failure_detail")
}

data class PersistedPresenceState(
    val status: AgentStatus,
    val shiftActive: Boolean,
    val voxUsername: String?,
)

/**
 * The last outcome of registerCurrentPushToken (via PresenceActions), persisted so it
 * survives a process death that happens before the agent ever reopens the app to see
 * it — see docs/android-presence-and-call-ux.md's "Update 2026-08-14 (later)" for the
 * live incident this exists to close (Voximplant's own logs: `push_results: []`,
 * "No push notifications has been sent" — the platform holding no token for this
 * agent at all — and the registration failure that caused it being silently
 * discarded). null = last known attempt succeeded (or none has happened yet).
 */
data class PersistedPushRegistrationFailure(
    val failure: AppFailure,
    val atMs: Long,
    // WHICH step failed — "fcm_token: ..." (local Google Play Services/FCM problem)
    // vs "registerForPushNotifications: ..." (Voximplant's own SDK call failed) —
    // see VoxClientManager.registerCurrentPushToken's kdoc. Deliberately a raw
    // string, not folded into AppFailure: AppFailure stays the coarse, reused-
    // everywhere-in-the-app taxonomy; this is a supplementary diagnostic detail with
    // no other consumer, and the app has no remote-logging pipeline to send it to
    // (AGENTS.md hard rule 3 — firebase-messaging is the only Firebase dependency),
    // so this record plus what the agent can read/relay IS the observability.
    val detail: String?,
)

class PresenceStateStore(private val context: Context) {

    suspend fun save(status: AgentStatus, shiftActive: Boolean, voxUsername: String?) {
        context.presenceStateDataStore.edit { prefs ->
            prefs[Keys.STATUS] = status.name
            prefs[Keys.SHIFT_ACTIVE] = shiftActive
            if (voxUsername != null) {
                prefs[Keys.VOX_USERNAME] = voxUsername
            } else {
                prefs.remove(Keys.VOX_USERNAME)
            }
        }
    }

    suspend fun load(): PersistedPresenceState? {
        val prefs = context.presenceStateDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        val statusName = prefs[Keys.STATUS] ?: return null
        val status = runCatching { AgentStatus.valueOf(statusName) }.getOrNull() ?: return null
        return PersistedPresenceState(
            status = status,
            shiftActive = prefs[Keys.SHIFT_ACTIVE] ?: false,
            voxUsername = prefs[Keys.VOX_USERNAME],
        )
    }

    // Explicit shift withdrawal (logout, or the service's own stop()): a device that
    // is no longer on shift must not resurrect presence on the next process restart.
    // Wipes the push-registration record too — a signed-out device shouldn't show a
    // stale failure from a previous session.
    suspend fun clear() {
        context.presenceStateDataStore.edit { it.clear() }
    }

    // failure == null records a SUCCESS (clears any previously stored failure) — the
    // record is "last known outcome", not "last known failure only".
    suspend fun recordPushRegistrationOutcome(failure: AppFailure?, atMs: Long, detail: String? = null) {
        context.presenceStateDataStore.edit { prefs ->
            if (failure != null) {
                prefs[Keys.PUSH_REG_FAILURE] = appFailureToPersistName(failure)
                prefs[Keys.PUSH_REG_FAILURE_AT_MS] = atMs
                if (detail != null) prefs[Keys.PUSH_REG_FAILURE_DETAIL] = detail else prefs.remove(Keys.PUSH_REG_FAILURE_DETAIL)
            } else {
                prefs.remove(Keys.PUSH_REG_FAILURE)
                prefs.remove(Keys.PUSH_REG_FAILURE_AT_MS)
                prefs.remove(Keys.PUSH_REG_FAILURE_DETAIL)
            }
        }
    }

    suspend fun loadPushRegistrationFailure(): PersistedPushRegistrationFailure? {
        val prefs = context.presenceStateDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        val name = prefs[Keys.PUSH_REG_FAILURE] ?: return null
        val atMs = prefs[Keys.PUSH_REG_FAILURE_AT_MS] ?: return null
        return PersistedPushRegistrationFailure(appFailureFromPersistName(name), atMs, prefs[Keys.PUSH_REG_FAILURE_DETAIL])
    }
}

// AppFailure's members that are realistically reachable from the presence/push paths
// (data objects, no payload) — a manual name<->instance mapping is simpler and more
// robust here than pulling kotlinx.serialization's polymorphic machinery in for one
// small, local persistence need. Anything unexpected round-trips through Unknown
// rather than crashing a DataStore read.
internal fun appFailureToPersistName(failure: AppFailure): String = when (failure) {
    AppFailure.NetworkUnavailable -> "NetworkUnavailable"
    AppFailure.Unauthorized -> "Unauthorized"
    AppFailure.NotSignedIn -> "NotSignedIn"
    AppFailure.Forbidden -> "Forbidden"
    else -> "Unknown"
}

internal fun appFailureFromPersistName(name: String): AppFailure = when (name) {
    "NetworkUnavailable" -> AppFailure.NetworkUnavailable
    "Unauthorized" -> AppFailure.Unauthorized
    "NotSignedIn" -> AppFailure.NotSignedIn
    "Forbidden" -> AppFailure.Forbidden
    else -> AppFailure.Unknown
}

// Pure resume decision, pulled to the top level so it is unit-testable with no
// Android/DataStore on the classpath (same reasoning as VoxSilentLogin.kt's
// planSilentLogin). Fail-closed: missing or stale-false evidence means don't
// resurrect anything — see docs/android-presence-and-call-ux.md §1, "System kill
// under memory pressure".
fun shouldResumeFromPersistedState(persisted: PersistedPresenceState?): Boolean =
    persisted?.shiftActive == true

