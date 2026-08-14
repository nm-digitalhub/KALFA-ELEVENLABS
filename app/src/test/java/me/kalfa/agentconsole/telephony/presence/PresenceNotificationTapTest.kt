package me.kalfa.agentconsole.telephony.presence

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import me.kalfa.agentconsole.MainActivity
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.telephony.PresenceSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression guard for a bug the owner hit on a real device (2026-08-14): tapping the
 * persistent presence notification did nothing at all.
 *
 * The builder set a title, text, icon, visibility, priority and action buttons — and
 * never a content intent. A notification without one silently swallows the tap: no
 * crash, no log, nothing to notice in code review, and invisible in a pure test of
 * contentTextFor. The incoming-call notification in telephony/vox had always done
 * this correctly, which is exactly why the omission survived — the pattern existed
 * two files away.
 *
 * Robolectric because the assertion has to be made against a REAL built
 * Notification and its PendingIntent; the interesting failure is the field being
 * absent, which no pure function can express.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PresenceNotificationTapTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun build(): Notification {
        PresenceNotificationBuilder.ensureChannel(context)
        return PresenceNotificationBuilder.build(
            context,
            AgentStatus.READY,
            PresenceSyncState.Synced,
        )
    }

    @Test
    fun `the notification carries a content intent, so a tap has somewhere to go`() {
        assertNotNull("tapping this notification would do nothing", build().contentIntent)
    }

    @Test
    fun `the tap opens the console rather than an arbitrary component`() {
        val saved = shadowOf(build().contentIntent).savedIntent

        assertEquals(MainActivity::class.java.name, saved.component?.className)
        assertEquals(PresenceNotificationBuilder.ACTION_OPEN_CONSOLE, saved.action)
    }

    @Test
    fun `the tap intent is distinct from the notification-settings one`() {
        // Both are getActivity PendingIntents from the same builder. Equal request
        // code plus a matching intent would have them collapse into one under
        // FLAG_UPDATE_CURRENT, and the tap would silently start opening Settings.
        val tapIntent = shadowOf(build().contentIntent).savedIntent

        assertNotEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, tapIntent.action)
    }

    @Test
    fun `tapping must not dismiss the ongoing notification`() {
        // setAutoCancel(true) is the documented default advice for tappable
        // notifications and is wrong here: this is the foreground service's own
        // notification and the agent's only always-visible status surface. Dismissing
        // it on tap would also be futile — the next heartbeat reposts it.
        val n = build()

        assertEquals(Notification.FLAG_ONGOING_EVENT, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, n.flags and Notification.FLAG_AUTO_CANCEL)
    }
}
