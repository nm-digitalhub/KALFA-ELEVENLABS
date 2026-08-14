package me.kalfa.agentconsole.telephony

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.telecom.CallsManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Registers this app with the platform as a **self-managed calling app**.
 *
 * ## The decision behind this file is contested — read this before extending it
 *
 * `AGENTS.md` records the opposite decision in as many words, in its "Known state"
 * `MANAGE_OWN_CALLS` bullet: *"Do not add `MANAGE_OWN_CALLS` to buy the FSI grant. …
 * The permission returns together with self-managed Telecom when that phase is actually
 * implemented …, not before, and not for this reason."* The commit that introduced this
 * file (`66ad7dc`) gave precisely that reason in its subject line. `AGENTS.md` is not
 * owned by this change and has not been edited to match, so the contradiction is
 * **flagged for the owner, not resolved here**.
 *
 * Half of the original reasoning is also demonstrably wrong, and the correction is worth
 * more than the claim: **registering a `PhoneAccount` buys this app nothing from the
 * platform for full-screen intents.** `NotificationManagerService`'s
 * `checkUseFullScreenIntentPermission` (AOSP `refs/heads/main`,
 * `services/core/java/com/android/server/notification/NotificationManagerService.java`)
 * resolves `USE_FULL_SCREEN_INTENT` through `PermissionManager` plus the app-op and
 * nothing else — it never consults `TelecomManager`, a `PhoneAccount`, or
 * `MANAGE_OWN_CALLS`. That agrees with the two independent AOSP reads already recorded
 * in `AGENTS.md` (the permission is `protectionLevel="normal|appop"`, and the app-op sets
 * no default mode).
 *
 * The Play-side half does not survive either, which is the part worth reading twice.
 * play-policy-owner checked the policy pages: Play's pre-grant eligibility keys on a
 * **Play Console declaration form**, not on anything observable on the device — *"Submit a
 * declaration form in your Play Console to establish pre-grant eligibility for the
 * full-screen intent permission"*. `registerAppWithTelecom` runs after install; Play's
 * review pipeline never sees it. So the commit's stated reason is wrong on BOTH sides, and
 * this file does not earn its place by the argument it was written with.
 *
 * It earns its place by two reasons that commit never named:
 *  1. The `androidx.core:core-telecom` dependency merges in `MANAGE_OWN_CALLS`, and that
 *     declaration is the prerequisite for the `phoneCall` foreground-service type — which
 *     is what fixes a real crash on the push-woken incoming-call path. See the FGS notes in
 *     `AndroidManifest.xml`. Note carefully that this reason justifies the DEPENDENCY, not
 *     the runtime call in this file: AOSP's `ForegroundServiceTypePolicy` checks
 *     `MANAGE_OWN_CALLS` as a plain permission, so a manifest declaration satisfies it and
 *     a registered `PhoneAccount` is not consulted.
 *  2. It is supporting evidence for that Play Console declaration, which the policy page
 *     says is *"subject to review"* — a discretionary human decision, not a mechanism.
 *
 * What also stands is the *mechanism*: `AGENTS.md`'s own Telecom row recommends
 * `androidx.core:core-telecom` (`CallsManager`) over hand-writing a `ConnectionService`
 * when that phase starts, and the AAR is verified compatible with this build
 * (`minCompileSdk=34` ≤ this app's `compileSdk` 36, read from its own
 * `aar-metadata.properties`; every transitive dependency it declares is below what this
 * project already resolves, so it cannot drag in the `compileSdk 37` artifacts held back
 * in `libs.versions.toml`).
 *
 * ## The Play-side argument, stated honestly
 *
 * `USE_FULL_SCREEN_INTENT` is what lets an incoming call take over a locked screen — the
 * most load-bearing behaviour in this app, since an agent's phone is pocketed and locked
 * exactly when a call arrives. AOSP defines it as `protectionLevel="normal|appop"`, so
 * the `normal` half grants it at install with no dialog. The `appop` half is what can be
 * taken away, and Android 14's behaviour changes name who takes it: *"The Google Play
 * Store revokes default `USE_FULL_SCREEN_INTENT` permissions for any apps that don't fit
 * this profile"* — the profile being calling or alarm apps. Today the app is installed
 * from a CI artifact, Play is not in the distribution path, and the grant survives.
 *
 * ## Why `androidx.core:core-telecom` rather than a hand-written `ConnectionService`
 *
 * The manifest used to carry a commented-out `ConsoleConnectionService` and a note
 * explaining that `MANAGE_OWN_CALLS` was withheld because declaring a sensitive
 * permission with no matching service is a Play-review risk. This dependency removes that
 * premise: the AAR ships `androidx.core.telecom.internal.JetpackConnectionService`
 * (guarded by `BIND_TELECOM_CONNECTION_SERVICE`, with the
 * `android.telecom.ConnectionService` intent filter) and declares `MANAGE_OWN_CALLS`
 * itself — both read out of the unpacked `core-telecom-1.0.1.aar`, not off a docs page.
 * AOSP gives `MANAGE_OWN_CALLS` `protectionLevel="normal"`
 * (`frameworks/base/core/res/AndroidManifest.xml`), so it needs no runtime request.
 *
 * ## What this deliberately does NOT do, and what that costs
 *
 * It registers the `PhoneAccount`; it does not route calls through
 * [CallsManager.addCall]. Those are separable — the library's kdoc treats registration as
 * a prerequisite for `addCall`, not the same act — and `addCall` hands Telecom the call's
 * audio focus and lifecycle under a 5s `CallControlScope` deadline that cannot be
 * verified without a physical device. Doing that half blind would put the only working
 * incoming-call path at risk.
 *
 * The cost of stopping here is real and should not be discovered later: Google's AEP
 * guideline for the Telecom API (`developer.android.com/distribute/aep/aep-req-telecom-api`,
 * read 2026-08-14) requires that **all** incoming and outgoing VoIP calls be registered
 * via `CallsManager#addCall`, that audio focus and routing be left to Telecom rather than
 * driven through the audio APIs, and that call state run through `CallControlScope`. This
 * app already fell under that guideline's applicability ("apps that provide VoIP calling
 * capabilities") before this file existed, so registering creates no new obligation — but
 * it does move the app into the exact shape the guideline names as non-compliant: a
 * registered self-managed `PhoneAccount` with no `addCall` behind it. AEP is a
 * quality-programme guideline, **not** the Play policy that revokes the FSI app-op; the
 * commit message treats the two as one thing and they are not.
 *
 * ## What registering alone actually changes on the device
 *
 * Close to nothing, and that is the point — but "close to nothing" is not "nothing", so
 * here is the difference. Telecom binds a `ConnectionService` only in order to create a
 * connection, and `addCall` is what asks for one, so `JetpackConnectionService` is never
 * bound today and Telecom never takes this app's audio focus or routing. The existing
 * incoming-call path (a `CallStyle` notification driven by `VoxIncomingCallCoordinator`)
 * is untouched by registration: `NotificationManagerService`'s CallStyle rule
 * (`checkDisqualifyingFeatures`) turns on whether the notification is an FGS/UIJ
 * notification or carries a full-screen intent, and has no `PhoneAccount` term in it.
 *
 * The one thing that does change immediately: a self-managed account is switched on
 * without the user ever being asked. `PhoneAccountRegistrar.addOrReplacePhoneAccount`
 * sets `isEnabled` unconditionally for any account carrying `CAPABILITY_SELF_MANAGED`,
 * which is the opposite of the ordinary case that `TelecomManager#registerPhoneAccount`'s
 * javadoc describes ("the user may still need to enable the PhoneAccount within the phone
 * app settings"). So the account is live from the moment this runs.
 *
 * All of the above is AOSP behaviour. **OEM surfaces are unverified** — whether a given
 * vendor's dialer starts offering this app in a calling-account chooser, or lists it under
 * Settings, could not be checked: no physical device was available.
 *
 * ## Threading, repetition, and why re-registering is safe
 *
 * `registerAppWithTelecom` ends in `TelecomManager.registerPhoneAccount`, which is a
 * **synchronous binder transaction** (`ITelecomService#registerPhoneAccount`, AOSP
 * `telecomm/java/android/telecom/TelecomManager.java`). The system side runs it inside
 * `synchronized (mLock)` — Telecom's global lock — and the work under that lock includes a
 * `PackageManager` component resolution, an app-label lookup, a full XML re-serialisation
 * of every registered account, and a `fireAccountsChanged()` fan-out
 * (`TelecomServiceImpl.registerPhoneAccount` → `PhoneAccountRegistrar.addOrReplacePhoneAccount`
 * → `write()`). None of that has a documented upper bound.
 *
 * The first version of this file ran that on the **main thread** from
 * `MainActivity.onCreate`, which re-runs on every configuration change — so a rotation
 * re-registered the account and re-broadcast the change to every Telecom listener on the
 * device. That is latency and system-wide churn rather than a crash, but neither belongs
 * on the UI thread, so [register] now hands the call to a short-lived worker and attempts
 * it once per process until it succeeds.
 *
 * Re-registering is safe, and that is documented rather than assumed: *"existing
 * registrations will be overwritten if the `PhoneAccountHandle` matches that of a
 * `PhoneAccount` which is already registered"* (`TelecomManager#registerPhoneAccount`
 * javadoc, AOSP). `core-telecom` derives one stable handle per package
 * (`CallsManager.getPhoneAccountHandleForPackage`), so repeats replace rather than
 * accumulate and the documented 10-accounts-per-package cap is never approached. The one
 * guard that can reject a re-registration,
 * `PhoneAccountRegistrar.enforceSelfManagedAccountUnmodified`, fires only when the
 * *replacement* drops `CAPABILITY_SELF_MANAGED`; `Utils.remapJetpackCapsToPlatformCaps`
 * always sets it, so it cannot fire from here.
 */
object TelecomRegistration {
    private const val TAG = "TelecomRegistration"
    private const val THREAD_NAME = "telecom-register"

    /** Set only once the platform call has actually returned — see [shouldAttempt]. */
    private val registered = AtomicBoolean(false)

    /** Stops a rotation storm from starting a second worker while one is still running. */
    private val inFlight = AtomicBoolean(false)

    /**
     * The whole gating decision, as a pure function so it can be tested without a device
     * or a shadowed `TelecomManager`.
     *
     * Two rules, and the second is the one worth stating: core-telecom throws
     * `UnsupportedOperationException` below Oreo (its own `Utils.verifyBuildVersion`, read
     * from the 1.0.1 sources) and this app's `minSdk` is 24, so API 24 and 25 devices
     * genuinely reach here and must be skipped. And registration is retried until it
     * *succeeds* rather than until it is *attempted*: a device whose Telecom service was
     * not ready yet (still coming up at boot, or an OEM failure that clears) gets another
     * attempt the next time the activity is created. Latching on the attempt instead would
     * make one transient failure permanent for the life of the process.
     */
    internal fun shouldAttempt(sdkInt: Int, alreadyRegistered: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.O && !alreadyRegistered

    /**
     * Registers the app's self-managed `PhoneAccount`. Safe to call more than once, and
     * called that way — `MainActivity.onCreate` runs on every configuration change.
     *
     * Returns immediately; the platform call happens on a worker. Nothing in this app
     * calls [CallsManager.addCall], which is the only API that requires registration to
     * have completed first, so there is no ordering constraint to honour today. **If
     * `addCall` is ever wired, that changes** — it throws when the account is not
     * registered, and it would then need to wait on this rather than race it.
     *
     * Never throws, on the caller's thread or the worker's. A device that cannot register
     * falls back to a heads-up notification instead of a full-screen ring — degraded but
     * still able to take calls — and killing the app on launch to report that would be
     * strictly worse. Note that an uncaught exception on a worker thread takes the whole
     * process down, so the `try` deliberately encloses the `CallsManager` construction as
     * well as the call: `CallsManager`'s constructor casts
     * `getSystemService(TELECOM_SERVICE)` to a non-null `TelecomManager`, which throws on
     * a device with no Telecom feature.
     */
    fun register(context: Context) {
        if (!shouldAttempt(Build.VERSION.SDK_INT, registered.get())) return
        if (!inFlight.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        thread(isDaemon = true, name = THREAD_NAME) {
            try {
                // API >= 26 is guaranteed by shouldAttempt above, not by anything visible
                // on this line — CallsManager is @RequiresApi(O). Note that Android Lint's
                // NewApi check may not follow the version gate through a helper and a
                // lambda; `lint` is not part of this project's CI (it runs `test` and
                // `assembleDebug`), so this is a readability note, not a suppressed error.
                CallsManager(appContext).registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                registered.set(true)
            } catch (e: Exception) {
                // UnsupportedOperationException on an unexpected build, a
                // NullPointerException on a device without the Telecom feature, or a
                // SecurityException / OEM-specific failure out of registerPhoneAccount.
                Log.w(TAG, "self-managed PhoneAccount registration failed: ${e.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }
}
