package me.kalfa.agentconsole.telephony.presence

import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.domain.model.AgentStatus

// Single source of truth for "what happens when the agent's status changes" — shared by
// ConsoleViewModel.setAgentStatus (foreground, user-initiated, has a voxUsername from
// ConsoleUiState.me) and PresenceActionReceiver (a notification shade/lock-screen
// action; may fire with no Activity or ViewModel alive at all — see
// docs/android-presence-and-call-ux.md §1). Extracted so the READY-path Voximplant
// login dance (AGENTS.md "Push wake-up") lives in exactly one place instead of being
// duplicated between the two callers, one of which has no ViewModel to put it in.
object PresenceActions {

    /**
     * Sets the agent's status and, for READY specifically, declares shift and drives
     * the Voximplant silent-login + push-token registration chain — logic moved
     * verbatim from the ConsoleViewModel.setAgentStatus this replaces, not changed.
     * DND/NOT_READY do NOT withdraw shift (a short break should not drop push-wake
     * coverage for the rest of the shift); only PresenceForegroundService stopping
     * (agent logout) does that, via AgentPresence.setShiftActive(false) directly.
     * ensureLoggedIn is idempotent/cheap once already logged in, so a
     * system-restarted PresenceForegroundService re-applying the last known status
     * (docs/android-presence-and-call-ux.md §1, "System kill under memory pressure")
     * costs nothing extra beyond the one real login it needs after process death.
     */
    suspend fun applyStatus(status: AgentStatus, voxUsername: String?) {
        val presence = DependencyContainer.agentPresence
        presence.setStatus(status)
        if (status == AgentStatus.READY) {
            presence.setShiftActive(true)
            val vcm = DependencyContainer.voxClientManager
            if (voxUsername != null && vcm != null) {
                vcm.ensureLoggedIn(voxUsername).onSuccess {
                    vcm.registerCurrentPushToken()
                }
            }
        }
    }
}
