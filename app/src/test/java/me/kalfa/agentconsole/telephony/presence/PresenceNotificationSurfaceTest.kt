package me.kalfa.agentconsole.telephony.presence

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.telephony.PresenceSyncState
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.toHebrewMessage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the BUILT notification actually carries to the OS, as opposed to what the pure
 * text functions return — the two are only the same if the builder wires them up, and a
 * missing wire here is invisible in a pure test (the tap-intent bug this package already
 * has a regression test for was exactly that shape).
 *
 * @Config(sdk = [30]) matches the sibling notification tests: past API 26 so channels are
 * real, and the compat path every device in this app's 24-30 range takes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PresenceNotificationSurfaceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun manager(): NotificationManager = context.getSystemService(NotificationManager::class.java)

    /**
     * The channel used to be created only by PresenceForegroundService.onCreate — a
     * precondition living in a different file from the code that depends on it.
     *
     * Getting it wrong raises nothing: the platform logs "No Channel found for pkg=..."
     * and drops the notification. For the foreground service that means the agent's only
     * always-visible status surface silently disappears while the service reports itself
     * running, which is worse than an exception because nothing anywhere says so.
     */
    @Test
    fun `build creates its own channel, so no caller can silently post nothing`() {
        assertNull(
            "a leftover channel would make this test prove nothing",
            manager().getNotificationChannel(PresenceNotificationBuilder.CHANNEL_ID),
        )

        PresenceNotificationBuilder.build(context, AgentStatus.READY, PresenceSyncState.Synced)

        assertNotNull(manager().getNotificationChannel(PresenceNotificationBuilder.CHANNEL_ID))
    }

    /**
     * The masking documented in docs/android-presence-and-call-ux.md ("three different
     * conditions each fully mask push-registration on the notification"): a failed presence
     * sync outranks a push-registration failure in the one-line collapsed text, so the fact
     * that the device can never be woken had no way to reach this surface at all.
     */
    @Test
    fun `a push failure masked in the collapsed line still reaches the expanded view`() {
        val notification = PresenceNotificationBuilder.build(
            context,
            AgentStatus.READY,
            PresenceSyncState.Failed(AppFailure.NotSignedIn),
            pushRegistrationFailure = AppFailure.Unknown,
        )

        val expanded = notification.extras
            ?.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
            ?.toString()
            .orEmpty()

        assertTrue(
            "the expanded view must name the push-registration failure, got: $expanded",
            expanded.contains(AppFailure.Unknown.toHebrewMessage(FailureContext.PUSH_REGISTRATION)),
        )
    }

    /**
     * The other half of the contract: an expanded view is attached only when the collapsed
     * line genuinely cannot say everything. A healthy agent's notification must look exactly
     * as it did before this change.
     */
    @Test
    fun `a healthy agent gets no expanded view at all`() {
        val notification = PresenceNotificationBuilder.build(context, AgentStatus.READY, PresenceSyncState.Synced)

        assertNull(notification.extras?.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT))
    }
}
