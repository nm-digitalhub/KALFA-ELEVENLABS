package me.kalfa.agentconsole.telephony

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.telecom.CallsManager

/**
 * Registers this app with the platform as a **self-managed calling app**.
 *
 * ## Why this exists, and why it is not optional
 *
 * `USE_FULL_SCREEN_INTENT` is what lets an incoming call take over a locked screen —
 * the single most load-bearing behaviour in this app, since an agent's phone is
 * pocketed and locked exactly when a call arrives. AOSP defines that permission as
 * `protectionLevel="normal|appop"` (verified in `frameworks/base/core/res/AndroidManifest.xml`),
 * so the `normal` half grants it at install with no dialog — which is why no permission
 * prompt for it has ever appeared, and why none ever should.
 *
 * The `appop` half is the part that can be taken away, and Android 14's behaviour
 * changes say who takes it: *"The Google Play Store revokes default
 * `USE_FULL_SCREEN_INTENT` permissions for any apps that don't fit this profile"* —
 * the profile being apps that provide calling or alarms. Today this app is installed
 * directly from a CI artifact, Play is not in the distribution path, and the grant
 * survives. **That reprieve ends at the first Play upload.** At that point an app that
 * has not registered as a calling app loses locked-screen ringing, silently, with the
 * only symptom being missed calls.
 *
 * So this is not preparation for a future phase. It is the thing that has to be true
 * *before* the store listing goes live, and it is cheap enough to do now.
 *
 * ## Why `androidx.core:core-telecom` rather than a hand-written `ConnectionService`
 *
 * The manifest used to carry a commented-out `ConsoleConnectionService` and a note
 * explaining that `MANAGE_OWN_CALLS` was deliberately withheld, because declaring a
 * sensitive permission with no matching service is a Play-review risk. That reasoning
 * was right, and this dependency is what removes the premise: the AAR ships
 * `androidx.core.telecom.internal.JetpackConnectionService` (guarded by
 * `BIND_TELECOM_CONNECTION_SERVICE`, with the `android.telecom.ConnectionService`
 * intent filter) and declares `MANAGE_OWN_CALLS` itself — both verified by unpacking
 * `core-telecom-1.0.1.aar`, not read off a docs page. The permission is
 * `protectionLevel="normal"` in AOSP, so it needs no runtime request.
 *
 * ## What this deliberately does NOT do
 *
 * It registers the `PhoneAccount`; it does not route calls through
 * [CallsManager.addCall]. Those are separable, and the library's own kdoc is explicit
 * that registration is a prerequisite for `addCall` rather than the same act.
 * Registration is what makes the app a calling app to the platform and to Play;
 * `addCall` hands Telecom ownership of the call's audio focus and lifecycle, and
 * carries a 5-second `CallControlScope` deadline that cannot be verified without a
 * physical device. Doing the second half blind would put the only working
 * incoming-call path at risk to buy nothing extra for the problem this solves.
 *
 * Registration alone is idempotent and cheap by inspection of the library source:
 * `registerAppWithTelecom` builds a `PhoneAccount` and calls
 * `TelecomManager.registerPhoneAccount`. Re-registering is the documented way to
 * update capabilities, and the library states there is no need to ever unregister.
 */
object TelecomRegistration {
    private const val TAG = "TelecomRegistration"

    /**
     * Registers the app's self-managed `PhoneAccount`. Safe to call more than once.
     *
     * Never throws: a device that cannot register is a device that will fall back to a
     * heads-up notification instead of a full-screen ring — degraded, but still able to
     * take calls — and crashing the app on launch to report that would be strictly
     * worse. The failure is logged rather than surfaced because there is no action an
     * agent could take about it.
     */
    fun register(context: Context) {
        // core-telecom throws UnsupportedOperationException below Oreo (its own
        // Utils.verifyBuildVersion, read from the 1.0.1 sources). This app's minSdk is
        // 24, so API 24 and 25 devices genuinely reach here and must be skipped rather
        // than allowed to throw.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            CallsManager(context.applicationContext)
                .registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        } catch (e: Exception) {
            // UnsupportedOperationException on an unexpected build, or a
            // SecurityException/OEM-specific failure from registerPhoneAccount.
            Log.w(TAG, "self-managed PhoneAccount registration failed: ${e.message}")
        }
    }
}
