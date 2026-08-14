package me.kalfa.agentconsole.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// decideCallAudioPermissionAction replaces logic that classified an entire group of
// permissions from ONE aggregate reading (state.shouldShowRationale) instead of each
// permission's own status. These are the two distinct ways that aggregate went wrong
// — see the function's own kdoc for the accompanist source this was verified
// against. Pure, no Android/Robolectric needed — same pattern as
// RuntimePermissionStateTest, which this extends coverage from.
class CallAudioPermissionActionTest {

    private val MIC = android.Manifest.permission.RECORD_AUDIO
    private val NOTIFS = android.Manifest.permission.POST_NOTIFICATIONS

    @Test
    fun `a never-asked permission is still requested even though a sibling was permanently denied`() {
        // MIC: never asked (rationale=false, everRequested=false).
        // NOTIFS: denied twice (rationale=false, everRequested=true).
        // The old group logic read a single shouldShowRationale=false and a single
        // everRequested=true for the WHOLE group and showed the permanent-denial
        // banner for both — including MIC, whose dialog can still appear.
        val action = decideCallAudioPermissionAction(
            revoked = listOf(MIC to false, NOTIFS to false),
            everRequested = { it == NOTIFS },
        )

        assertEquals(CallAudioPermissionAction.Request(listOf(MIC)), action)
    }

    @Test
    fun `a never-asked permission does not drag a legitimately requestable sibling into the banner`() {
        // The narrower way the old aggregate poisoned itself: accompanist's
        // shouldShowRationale is false the instant ANY missing permission's own
        // rationale is false — true for "never asked" even with zero denial history
        // anywhere in the group. MIC alone (never asked) was enough to drag the old
        // group-level rationale to false while NOTIFS was legitimately "denied once"
        // and fully requestable.
        val action = decideCallAudioPermissionAction(
            revoked = listOf(MIC to false, NOTIFS to true),
            everRequested = { it == NOTIFS },
        )

        assertEquals(CallAudioPermissionAction.Request(listOf(MIC, NOTIFS)), action)
    }

    @Test
    fun `both permanently denied shows the banner for both, requests neither`() {
        val action = decideCallAudioPermissionAction(
            revoked = listOf(MIC to false, NOTIFS to false),
            everRequested = { true },
        )

        assertEquals(CallAudioPermissionAction.ShowPermanentDenial(listOf(MIC, NOTIFS)), action)
    }

    // --- the second defect: asking AGAIN on top of the user's answer ---------------
    //
    // A "Deny" flips that permission's own shouldShowRationale false -> true, and
    // accompanist refreshes the status straight from the request's result callback
    // (MutableMultiplePermissionsState.updatePermissionsStatus, installed 0.37.3
    // source). So the denial itself re-keys EnsureCallAudioPermission's effect, the
    // permission re-reads as DeniedOnce — still requestable — and the pre-change rule
    // ("request whenever anything is requestable") launched again immediately. Two
    // reflex taps then reach permanent denial, after which no dialog can ever appear.

    @Test
    fun `a denial does not re-request in the same process, and is not silent either`() {
        // Exactly the reading the system gives the instant both dialogs are declined.
        // It must name both permissions: this is the app's only in-app signal for a
        // postponed RECORD_AUDIO denial, which no other surface reports at all.
        val action = decideCallAudioPermissionAction(
            revoked = listOf(MIC to true, NOTIFS to true),
            everRequested = { true },
            requestedThisProcess = { true },
        )

        assertEquals(CallAudioPermissionAction.AwaitNextLaunch(listOf(MIC, NOTIFS)), action)
    }

    @Test
    fun `the postponed message says the denial is reversible, the permanent one does not`() {
        // The two states differ by exactly one fact — whether asking again can still
        // work — and the wording is the only place an agent learns which one they are
        // in. Getting these the same way round is what makes a "Deny" feel accepted
        // rather than final.
        val postponed = postponedDenialBody(listOf(MIC))
        val permanent = permanentDenialBody(listOf(MIC))

        assertTrue(postponed, postponed.contains("אפשר לאשר בכל שלב בהגדרות המכשיר"))
        assertFalse(postponed, postponed.contains("לא ניתן לבקש"))
        assertTrue(permanent, permanent.contains("לא ניתן לבקש"))
    }

    @Test
    fun `the next process launch asks again, once`() {
        // Identical system state, fresh process: the durable log still says "asked
        // before", the in-memory record does not. Denied-once still shows a dialog, so
        // waiting forever would be the opposite mistake.
        val action = decideCallAudioPermissionAction(
            revoked = listOf(MIC to true, NOTIFS to true),
            everRequested = { true },
            requestedThisProcess = { false },
        )

        assertEquals(CallAudioPermissionAction.Request(listOf(MIC, NOTIFS)), action)
    }

    @Test
    fun `a permission this process has not asked about is still requested next to one it has`() {
        val action = decideCallAudioPermissionAction(
            revoked = listOf(MIC to false, NOTIFS to true),
            everRequested = { false },
            requestedThisProcess = { it == NOTIFS },
        )

        assertEquals(CallAudioPermissionAction.Request(listOf(MIC)), action)
    }

    @Test
    fun `having asked this process never suppresses the permanent-denial banner`() {
        // The in-memory record must not be able to turn a permanent denial into
        // silence: that state has no dialog left to wait for, so staying quiet would
        // leave an agent believing they can be heard.
        val action = decideCallAudioPermissionAction(
            revoked = listOf(MIC to false, NOTIFS to false),
            everRequested = { true },
            requestedThisProcess = { true },
        )

        assertEquals(CallAudioPermissionAction.ShowPermanentDenial(listOf(MIC, NOTIFS)), action)
    }

    @Test
    fun `a single missing permission classifies exactly as it did before this change`() {
        val neverAsked = decideCallAudioPermissionAction(
            revoked = listOf(MIC to false),
            everRequested = { false },
        )
        assertEquals(CallAudioPermissionAction.Request(listOf(MIC)), neverAsked)

        val permanentlyDenied = decideCallAudioPermissionAction(
            revoked = listOf(MIC to false),
            everRequested = { true },
        )
        assertEquals(CallAudioPermissionAction.ShowPermanentDenial(listOf(MIC)), permanentlyDenied)
    }
}
