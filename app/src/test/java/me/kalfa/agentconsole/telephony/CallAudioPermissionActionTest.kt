package me.kalfa.agentconsole.telephony

import org.junit.Assert.assertEquals
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
