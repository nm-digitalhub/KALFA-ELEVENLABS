package me.kalfa.agentconsole.telephony

import android.content.Context

/**
 * Durable memory of which runtime permissions this app has ever asked for.
 *
 * Exists for one reason: `checkSelfPermission == DENIED` with
 * `shouldShowRequestPermissionRationale == false` means EITHER "never asked" OR
 * "permanently denied", and Android offers nothing that tells them apart (see
 * [classifyPermission]). Only the app's own record can, and it has to survive
 * process death — the ambiguity is invisible within a single session, where the app
 * already knows what it did.
 *
 * SharedPreferences rather than the DataStore used elsewhere in this package,
 * deliberately: the read happens inside a Composable that must decide right now
 * whether to launch a request, and DataStore is suspend-only. A boolean per
 * permission is also not the kind of data DataStore's transactional guarantees are
 * for. Nothing sensitive is stored — only "we have asked for this string before".
 */
object PermissionRequestLog {

    private const val PREFS = "permission_request_log"
    private const val PREFIX = "requested:"

    fun hasEverRequested(context: Context, permission: String): Boolean =
        prefs(context).getBoolean(PREFIX + permission, false)

    /**
     * Records that a request was launched. Must be called at LAUNCH time, not on
     * the result: a permanently-denied request produces no dialog and no user
     * decision, so waiting for a callback to record it would never record the very
     * case this log exists to detect.
     */
    fun markRequested(context: Context, permissions: Collection<String>) {
        if (permissions.isEmpty()) return
        prefs(context).edit().apply {
            for (p in permissions) putBoolean(PREFIX + p, true)
        }.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
