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

/**
 * The in-app banners for a ring-capability problem must carry a BUTTON, not a
 * sentence telling the agent where to go looking.
 *
 * Both capabilities are Settings-only toggles — neither has a runtime dialog that
 * could ever appear — so a banner without an action leaves the agent to find a
 * screen they have no reason to know the name of. The locked-screen one previously
 * read "ניתן לתקן דרך התראת הנוכחות הקבועה", which pointed at a different surface
 * entirely.
 *
 * Pure: this asserts on the message objects PresenceActions publishes, so no
 * Android is needed and the action ids stay pinned to the ids the ViewModel
 * actually handles — a literal string on each side of that boundary is exactly how
 * these silently stop matching.
 */
class RingCapabilityBannerActionTest {

    @org.junit.Test
    fun `the action ids the banners publish are the ones the handler expects`() {
        // Guards the ui/telephony boundary: ConsoleViewModel.handleGlobalMessageAction
        // branches on these same constants, so a rename on one side without the other
        // would silently produce a button that does nothing — the exact class of
        // failure this whole change exists to remove.
        assertEquals("ring_open_notification_settings", PresenceActions.ACTION_OPEN_NOTIFICATION_SETTINGS)
        assertEquals("ring_open_fsi_settings", PresenceActions.ACTION_OPEN_FULL_SCREEN_INTENT_SETTINGS)
    }

    @org.junit.Test
    fun `the two ids are distinct, so one button cannot open the other screen`() {
        assertNotEquals(
            PresenceActions.ACTION_OPEN_NOTIFICATION_SETTINGS,
            PresenceActions.ACTION_OPEN_FULL_SCREEN_INTENT_SETTINGS,
        )
    }
}
