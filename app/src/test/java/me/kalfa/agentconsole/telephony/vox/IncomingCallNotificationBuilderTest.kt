package me.kalfa.agentconsole.telephony.vox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Regression guard for a risk flagged during review: NotificationCompat.CallStyle
// .forIncomingCall(person, declineIntent, answerIntent) does not type-restrict its
// PendingIntent arguments, and a silently-dropped action button is exactly the kind
// of failure invisible without a device (docs/android-presence-and-call-ux.md §3).
// @Config(sdk = 30) deliberately exercises NotificationCompat's own compat-emulated
// CallStyle action construction (below API 31's native platform CallStyle) — the
// same path every device in this app's 24-30 minSdk range uses, and the one most
// reliably shadowed by Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class IncomingCallNotificationBuilderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // The channel behaviours behind a phone that would not vibrate. Worth pinning
    // because they are set ONCE per device and then frozen by the platform: "After
    // you create a notification channel, you can't change the notification
    // behaviors." A regression here is therefore not a bug that ships and gets
    // fixed — it is a bug that becomes permanent on every device that installs it,
    // until the channel id is versioned again.
    @Test
    fun `the incoming-call channel rings and vibrates as an actual phone call`() {
        IncomingCallNotificationBuilder.ensureChannel(context)

        val mgr = context.getSystemService(NotificationManager::class.java)!!
        val channel = mgr.getNotificationChannel(IncomingCallNotificationBuilder.CHANNEL_ID)
        assertNotNull(channel)

        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
        assertTrue("channel must vibrate", channel.shouldVibrate())
        assertNotNull("an explicit pattern, not the OEM default", channel.vibrationPattern)

        // STREAM_RING is the one the device's silent/vibrate switch governs — a call
        // channel that does not declare itself as ring audio is not what that switch
        // is deciding about, which is how a phone on vibrate stays completely still.
        val attrs = channel.audioAttributes
        assertNotNull(attrs)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, attrs!!.usage)
        assertEquals(AudioManager.STREAM_RING, attrs.volumeControlStream)
    }

    // The v1 channel must not survive alongside v2: two entries with the same Hebrew
    // name, one of them dead, in the exact settings screen an agent opens to fix
    // vibration.
    @Test
    fun `the superseded channel is removed`() {
        val mgr = context.getSystemService(NotificationManager::class.java)!!
        mgr.createNotificationChannel(
            NotificationChannel("kalfa_incoming_call", "old", NotificationManager.IMPORTANCE_HIGH),
        )

        IncomingCallNotificationBuilder.ensureChannel(context)

        assertNull(mgr.getNotificationChannel("kalfa_incoming_call"))
        assertNotNull(mgr.getNotificationChannel(IncomingCallNotificationBuilder.CHANNEL_ID))
    }

    @Test
    fun `both answer and decline actions survive into the built notification`() {
        IncomingCallNotificationBuilder.ensureChannel(context)

        val notification = IncomingCallNotificationBuilder.build(
            context,
            callId = "call-1",
            displayName = "ששון מנחם",
            number = "+972500000000",
        )

        val actionCount = NotificationCompat.getActionCount(notification)
        assertEquals(2, actionCount)
        for (i in 0 until actionCount) {
            assertNotNull(NotificationCompat.getAction(notification, i)?.actionIntent)
        }
    }

    @Test
    fun `the public version carries no caller name`() {
        val notification = IncomingCallNotificationBuilder.build(
            context,
            callId = "call-1",
            displayName = "ששון מנחם",
            number = "+972500000000",
        )

        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        val publicTitle = publicVersion.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() ?: ""
        assertTrue(!publicTitle.contains("ששון"))
    }

    // Deliberately does NOT call ensureChannel first. Until now the channel existed only
    // because VoxIncomingCallCoordinator's init block created it — a precondition owned by
    // a different file from the one that depends on it. A notification posted to a channel
    // that does not exist throws nothing: the platform logs "No Channel found for pkg=..."
    // and drops it, so the whole incoming call would simply never appear on the device and
    // nothing anywhere would say why. That is the failure shape this path exists to remove.
    @Test
    fun `build creates its own channel, so the call cannot be dropped for lack of one`() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertNull(
            "a leftover channel would make this test prove nothing",
            manager.getNotificationChannel(IncomingCallNotificationBuilder.CHANNEL_ID),
        )

        IncomingCallNotificationBuilder.build(
            context,
            callId = "call-1",
            displayName = "ששון מנחם",
            number = "+972500000000",
        )

        assertNotNull(manager.getNotificationChannel(IncomingCallNotificationBuilder.CHANNEL_ID))
    }
}
