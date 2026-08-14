package me.kalfa.agentconsole.telephony.vox

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Answers one question before a call ever arrives: can this device actually alert the
 * agent, and can it do so on a locked screen?
 *
 * Every one of these can be off with NO error at call time. `setFullScreenIntent` does
 * not throw or return false when the permission is missing — it silently degrades to a
 * heads-up notification, so a locked phone simply never shows the ringing screen and
 * nothing anywhere records why. Posting to a blocked channel is equally quiet. That is
 * the same failure shape as the push-token registration problem (AGENTS.md "Push
 * wake-up"): the call is missed and the agent believes they are reachable.
 *
 * Checked here rather than at ring time so the answer is known while there is still
 * someone looking at a screen to fix it.
 */
data class RingCapability(
    /** App-level notification permission — POST_NOTIFICATIONS on 13+, the settings toggle below that. */
    val notificationsEnabled: Boolean,
    /** The incoming-call channel exists and is not set to IMPORTANCE_NONE by the user. */
    val channelAlerting: Boolean,
    /**
     * USE_FULL_SCREEN_INTENT is actually held, not merely declared in the manifest.
     * Android 14 (API 34) auto-grants it only to apps whose core function is calling or
     * alarms, Play revokes the default grant for everything else, and the user can turn
     * it off afterwards regardless — so a declared permission proves nothing here.
     * Below API 34 the permission is install-time and always held.
     *
     * Do not delete this check once the Play declaration is filed. Play policy 13392821
     * requires apps not approved for the default grant to "prompt users to grant
     * permission on new installs and gracefully degrade the experience if denied" —
     * this path IS that degradation, so it is a compliance obligation rather than a
     * defensive nicety, and it is the app's only safety net if approval never arrives.
     * See AGENTS.md §"Play upload" A-1.
     */
    val fullScreenIntentAllowed: Boolean,
) {
    /** The agent can be alerted at all — any missed call beyond this is not a config problem. */
    val canAlert: Boolean get() = notificationsEnabled && channelAlerting

    /**
     * The ringing UI will actually take over a locked screen. When false the call still
     * arrives, but as a heads-up notification an agent with a pocketed phone will miss.
     */
    val canRingOnLockedScreen: Boolean get() = canAlert && fullScreenIntentAllowed
}

object RingCapabilityChecker {

    fun check(context: Context): RingCapability {
        val mgr = context.getSystemService(NotificationManager::class.java)
        return RingCapability(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelAlerting = channelAlerting(mgr),
            fullScreenIntentAllowed = fullScreenIntentAllowed(mgr),
        )
    }

    // Channels only exist from API 26. Below that there is no per-channel block, so the
    // app-level toggle above is the whole answer. A channel that has not been created
    // yet is not "blocked" — ensureChannel creates it on first use.
    private fun channelAlerting(mgr: NotificationManager?): Boolean {
        if (mgr == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = mgr.getNotificationChannel(IncomingCallNotificationBuilder.CHANNEL_ID)
            ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun fullScreenIntentAllowed(mgr: NotificationManager?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return mgr?.canUseFullScreenIntent() ?: false
    }

    /**
     * The app's own notification settings — the only place a blocked app-level
     * notification permission can be turned back on. Unlike a runtime permission
     * denied twice, where Android's guidance is to respect the choice and stop
     * asking, this is a toggle the agent is expected to reach themselves; there is
     * no dialog that could ever offer it.
     */
    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Sends the agent to the one settings screen that can grant full-screen intent.
     * Null below API 34, where there is nothing to grant — callers should treat null as
     * "no action needed", never as an error.
     */
    fun fullScreenIntentSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
