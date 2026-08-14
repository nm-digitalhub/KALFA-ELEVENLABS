package me.kalfa.agentconsole.telephony.presence

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
}

data class PersistedPresenceState(
    val status: AgentStatus,
    val shiftActive: Boolean,
    val voxUsername: String?,
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
    suspend fun clear() {
        context.presenceStateDataStore.edit { it.clear() }
    }
}

// Pure resume decision, pulled to the top level so it is unit-testable with no
// Android/DataStore on the classpath (same reasoning as VoxSilentLogin.kt's
// planSilentLogin). Fail-closed: missing or stale-false evidence means don't
// resurrect anything — see docs/android-presence-and-call-ux.md §1, "System kill
// under memory pressure".
fun shouldResumeFromPersistedState(persisted: PersistedPresenceState?): Boolean =
    persisted?.shiftActive == true

