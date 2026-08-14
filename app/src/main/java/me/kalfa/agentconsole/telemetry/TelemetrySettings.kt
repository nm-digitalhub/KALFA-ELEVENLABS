package me.kalfa.agentconsole.telemetry

import android.content.Context

/**
 * The on/off switch, and it is OFF by default.
 *
 * **SharedPreferences, not DataStore, and that is a correctness requirement rather
 * than a style choice.** Every other persisted flag in this app uses DataStore
 * Preferences (VoxTokenStore, PresenceStateStore) and this one deliberately does
 * not: DataStore reads are asynchronous, and the two most valuable events in the
 * whole channel — [TelemetryEvents.FCM_SERVICE_CREATED] and
 * [TelemetryEvents.FCM_MESSAGE_RECEIVED] — are emitted in the first milliseconds
 * of a cold process, before any suspend function has had a chance to resolve. A
 * flag that is not readable yet reads as "off", and the events that answer the
 * owner's question are exactly the ones that would be dropped. SharedPreferences
 * loads its backing file on first access and answers synchronously afterwards.
 *
 * Sensitivity: a boolean and a counter. Strictly less than the Voximplant token
 * pair VoxTokenStore already keeps at this protection level, and less than the
 * Supabase refresh token supabase-kt keeps in plain SharedPreferences of its own.
 */
class TelemetrySettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether telemetry is recorded to the device log file and streamed to the
     * server. Default FALSE — this is a diagnostic, not a product surface, and
     * both halves stay dark until the owner turns them on from the Debug Live
     * screen.
     *
     * The in-memory ring buffer is deliberately NOT gated on this: it never
     * leaves the process, holds no PII, and costs a bounded few hundred objects.
     * Gating it would mean the screen is empty at the moment someone opens it to
     * find out what just happened.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /**
     * Whether to also POST events to the server. Separate from [enabled] so the
     * owner can record locally on a phone with no signal, or record without
     * touching the production host at all. Default FALSE, and it does nothing
     * unless [enabled] is also true.
     */
    var uploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPLOAD, false)
        set(value) { prefs.edit().putBoolean(KEY_UPLOAD, value).apply() }

    private companion object {
        const val PREFS_NAME = "device_telemetry"
        const val KEY_ENABLED = "enabled"
        const val KEY_UPLOAD = "upload_enabled"
    }
}
