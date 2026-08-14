package me.kalfa.agentconsole.telephony

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two rules in `TelecomRegistration.shouldAttempt`, both of which are the kind
 * that fail silently if they regress.
 *
 * The API gate is the loud one only in hindsight: `androidx.core:core-telecom` throws
 * `UnsupportedOperationException` below Oreo from its own `Utils.verifyBuildVersion`, and
 * this app's `minSdk` is 24, so API 24 and 25 devices really do reach this code.
 *
 * The retry rule is the quiet one. Registration is a synchronous binder call into
 * Telecom's globally-locked service and can fail transiently — Telecom still starting at
 * boot, an OEM hiccup. Latching the guard on the *attempt* rather than on the *success*
 * would make one such failure permanent for the life of the process, and the only symptom
 * would be that locked-screen ringing quietly stops qualifying at some future Play upload.
 * Nothing on a device would report it. That shape of bug is exactly what
 * `8aacc27` ("retry a failed registration") had to fix elsewhere in this codebase.
 *
 * This test deliberately touches no Android class other than compile-time `int` constants,
 * so it needs neither Robolectric nor a shadowed `TelecomManager` — the threading and the
 * platform call are not what is being asserted here, the decision to make them is.
 *
 * **What this file does NOT cover, stated so nobody reads more into it than is here.**
 * The retry behaviour itself is not testable from this seam. It lives in
 * `TelecomRegistration.register`, in the single fact that `registered.set(true)` sits
 * *after* `registerAppWithTelecom` inside the `try`. A pure function over
 * `(sdkInt, alreadyRegistered)` cannot observe that ordering, so moving that line above
 * the platform call would leave every test below green while deleting the retry. Checking
 * it would need a shadowed `TelecomManager`; that was judged not worth a Robolectric
 * dependency for one ordering constraint, and the ordering is called out in `register`'s
 * kdoc instead. If this file ever grows a test named for the retry, it must assert on
 * `register`, not on `shouldAttempt`.
 */
class TelecomRegistrationPolicyTest {

    @Test
    fun `skips devices below oreo, which core-telecom refuses to run on`() {
        assertFalse(TelecomRegistration.shouldAttempt(sdkInt = 24, alreadyRegistered = false))
        assertFalse(TelecomRegistration.shouldAttempt(sdkInt = 25, alreadyRegistered = false))
    }

    @Test
    fun `attempts on oreo and above`() {
        assertTrue(TelecomRegistration.shouldAttempt(sdkInt = 26, alreadyRegistered = false))
        assertTrue(TelecomRegistration.shouldAttempt(sdkInt = 36, alreadyRegistered = false))
    }

    @Test
    fun `does not re-register once the platform has accepted the account`() {
        assertFalse(TelecomRegistration.shouldAttempt(sdkInt = 36, alreadyRegistered = true))
    }
}
