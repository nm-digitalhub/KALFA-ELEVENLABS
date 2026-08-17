package me.kalfa.agentconsole.telephony.vox

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Vibrates for as long as an incoming call is actually ringing, and stops the moment
 * it is answered, declined or abandoned.
 *
 * WHY THIS EXISTS SEPARATELY FROM THE NOTIFICATION CHANNEL. A channel's
 * `vibrationPattern` plays ONCE per posted notification — it is a waveform, not a
 * loop, and `NotificationChannel` offers no repeat. The first attempt at this shipped
 * `0, 400, 800, 400` on the channel and produced exactly what that describes: two
 * buzzes and then nothing, reported as "רטט פעמיים באופן חלש וזה הכל". A phone that
 * is ringing has to keep announcing itself for the whole ring window, which on this
 * platform means the app owns the vibrator for that window.
 *
 * The channel keeps its own vibration setting anyway, and that is deliberate rather
 * than redundant: it is what makes the per-channel vibration toggle in system
 * settings mean something, and [shouldVibrate] below reads that toggle to decide
 * whether this class may run at all. An agent who turns vibration off for incoming
 * calls gets silence from both, not from one.
 *
 * RESPECTS THE RINGER, and this is the part that must not be clever. On SILENT the
 * device is being told to stay still and this does nothing. On VIBRATE it vibrates —
 * the case that started all of this. On NORMAL it also vibrates, because a ringtone
 * and a buzz together is what a phone call feels like and an agent may still have the
 * phone in a pocket.
 */
object IncomingCallVibrator {

    private const val TAG = "IncomingCallVibrator"

    /**
     * Off 0ms, buzz 800ms, rest 1000ms — then repeat from index 0, forever, until
     * something cancels it.
     *
     * Deliberately a ring cadence rather than a notification blip: long enough to
     * register through a coat pocket, with a gap long enough that it reads as a phone
     * ringing rather than a continuous alarm.
     */
    private val PATTERN = longArrayOf(0L, 800L, 1000L)
    private const val REPEAT_FROM_INDEX = 0

    @Volatile private var vibrating = false

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /**
     * Whether an incoming call may vibrate right now: the agent has not turned
     * vibration off for the call channel, and the device is not on silent.
     *
     * A channel that does not exist yet reports true — [IncomingCallNotificationBuilder]
     * creates it with vibration on, so "not created" is not a refusal. Same treatment
     * as RingCapabilityChecker gives the same case.
     */
    private fun shouldVibrate(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = mgr.getNotificationChannel(IncomingCallNotificationBuilder.CHANNEL_ID)
            ?: return true
        return channel.shouldVibrate()
    }

    /** Starts the ring vibration. Idempotent — a second offer does not stack a second waveform. */
    fun start(context: Context) {
        if (vibrating) return
        if (!shouldVibrate(context)) return
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) return

        try {
            val effect = VibrationEffect.createWaveform(PATTERN, REPEAT_FROM_INDEX)
            // USAGE_NOTIFICATION_RINGTONE for the same reason the channel declares it:
            // it tells the platform this is call audio, so the vibration follows the
            // ring stream's rules and the user's ringtone-related settings rather than
            // a generic notification's.
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            @Suppress("DEPRECATION")
            vib.vibrate(effect, attrs)
            vibrating = true
        } catch (e: Exception) {
            // Never fatal. A phone that does not buzz is the bug this fixes; a phone
            // that crashes on an incoming call is worse than the bug.
            Log.w(TAG, "ring vibration failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Stops it. Called on EVERY path out of the ringing state — answered, declined,
     * abandoned, superseded — because a repeating waveform has no natural end and
     * would otherwise outlive the call that started it.
     *
     * Safe to call when nothing is vibrating, so callers never have to track whether
     * they started it.
     */
    fun stop(context: Context) {
        if (!vibrating) return
        vibrating = false
        try {
            vibrator(context)?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "ring vibration cancel failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
