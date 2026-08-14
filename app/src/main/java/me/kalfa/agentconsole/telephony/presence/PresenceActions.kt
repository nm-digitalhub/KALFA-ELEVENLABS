package me.kalfa.agentconsole.telephony.presence

import android.content.Context
import me.kalfa.agentconsole.data.toAppFailure
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.domain.error.AppResult
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.telephony.vox.RingCapability
import me.kalfa.agentconsole.telephony.vox.RingCapabilityState
import me.kalfa.agentconsole.ui.message.AppMessageCenter
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.MessageAction
import me.kalfa.agentconsole.ui.message.MessageSeverity
import me.kalfa.agentconsole.ui.message.UiMessage
import me.kalfa.agentconsole.ui.message.toHebrewMessage

// Single source of truth for "what happens when the agent's status changes" — shared by
// ConsoleViewModel.setAgentStatus (foreground, user-initiated, has a voxUsername from
// ConsoleUiState.me) and PresenceActionReceiver (a notification shade/lock-screen
// action; may fire with no Activity or ViewModel alive at all — see
// docs/android-presence-and-call-ux.md §1). Extracted so the READY-path Voximplant
// login dance (AGENTS.md "Push wake-up") lives in exactly one place instead of being
// duplicated between the two callers, one of which has no ViewModel to put it in.
//
// Publishes failures through AppMessageCenter directly (a plain global object, not
// tied to any ViewModel — see MessageCenter.kt) rather than returning them to the
// caller: this is what lets a BroadcastReceiver with no UI of its own still surface a
// specific, actionable failure (owner requirement, see the two AppFailure/
// FailureContext members this uses: PRESENCE and PUSH_REGISTRATION).
object PresenceActions {
    private const val PRESENCE_MESSAGE_ID = "presence_sync"
    private const val PUSH_REGISTRATION_MESSAGE_ID = "push_registration"
    private const val RING_CAPABILITY_MESSAGE_ID = "ring_capability"

    // Handled in ConsoleViewModel.handleGlobalMessageAction. Public because the
    // banner and its handler live on opposite sides of the ui/telephony boundary and
    // a literal string in two files is exactly how these silently stop matching.
    const val ACTION_OPEN_NOTIFICATION_SETTINGS = "ring_open_notification_settings"
    const val ACTION_OPEN_FULL_SCREEN_INTENT_SETTINGS = "ring_open_fsi_settings"

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
     *
     * Every step's outcome is surfaced via AppMessageCenter — see this class's kdoc.
     * A push-registration failure is reported even though it doesn't block the
     * status/shift writes above it: the agent IS available in the sense the server
     * now believes, but a device that never registered for push cannot be woken once
     * the app is backgrounded or killed (AGENTS.md "Push wake-up") — a materially
     * different, and equally actionable, fact.
     */
    suspend fun applyStatus(status: AgentStatus, voxUsername: String?) {
        val presence = DependencyContainer.agentPresence
        reportPresenceResult(presence.setStatus(status))

        if (status == AgentStatus.READY) {
            reportPresenceResult(presence.setShiftActive(true))

            val vcm = DependencyContainer.voxClientManager
            if (voxUsername != null && vcm != null) {
                vcm.ensureLoggedIn(voxUsername).fold(
                    onSuccess = {
                        reportPushRegistrationResult(vcm.registerCurrentPushToken())
                    },
                    onFailure = { e -> reportPushRegistrationResult(Result.failure(e)) },
                )
            }

            // A device-configuration check, not a request that failed — deliberately
            // NOT run through AppFailure/FailureMapping (there is no operation here
            // to map a status code from). See telephony/vox/RingCapability.kt's kdoc.
            DependencyContainer.appContext?.let { context ->
                refreshAndReportRingCapability(context)
            }
        }
    }

    /**
     * The heartbeat's re-send of the current status, WITH the agent-visible banner kept
     * in step — the same split that [refreshAndReportRingCapability] exists to close.
     *
     * `PresenceForegroundService`'s heartbeat used to call `AgentPresence.setStatus`
     * directly. That writes `_syncState`, which the persistent notification observes, so
     * the notification recovered on its own every 30 seconds — but `setStatus` never
     * touches `AppMessageCenter`, and `PRESENCE_MESSAGE_ID` is published and resolved
     * only from [reportPresenceResult]. So a sync failure that healed itself on a later
     * tick left the notification correctly showing "זמין" while the in-app banner still
     * insisted the status had not reached the server, until the agent made some explicit
     * status change.
     *
     * Found by tracing the sibling messages after the identical `ring_capability` bug was
     * fixed, rather than by hitting it again in the field. Two surfaces disagreeing about
     * the same fact is the failure mode; one of them being right is not good enough.
     */
    suspend fun resendCurrentStatus() {
        val presence = DependencyContainer.agentPresence
        reportPresenceResult(presence.setStatus(presence.currentStatus.value))
    }

    /**
     * Re-attempts push-token registration on the heartbeat, but ONLY when the last
     * attempt is on record as having failed.
     *
     * Registration otherwise happens exactly once per transition to READY, so a device
     * whose registration failed for a transient reason — no network at the moment the
     * agent went available, Google Play services still waking — stayed unregistered for
     * the whole shift while reporting itself available. The server would route calls to
     * it and Voximplant would have no token to push to, which is the precise shape of the
     * incident this file's push handling keeps orbiting.
     *
     * Gated three ways so it costs nothing in the normal case: no retry unless
     * [PushRegistrationState] holds a failure, none without a client, and none unless the
     * SDK is genuinely logged in — `ensureLoggedIn` needs a vox username this service does
     * not have, and `registerCurrentPushToken` only binds a token to an identity once the
     * client is logged in anyway (Voximplant's Android push guide: *"the token is
     * registered only after the client logs in"*). A logged-out client is a job for the
     * next READY, not for the heartbeat.
     */
    suspend fun retryPushRegistrationIfFailed() {
        if (PushRegistrationState.lastFailure.value == null) return
        val vcm = DependencyContainer.voxClientManager ?: return
        if (!vcm.isLoggedIn) return
        reportPushRegistrationResult(vcm.registerCurrentPushToken())
    }

    private fun reportPresenceResult(result: AppResult<Unit>) {
        when (result) {
            is AppResult.Success -> AppMessageCenter.resolve(PRESENCE_MESSAGE_ID)
            is AppResult.Failure -> AppMessageCenter.publish(
                UiMessage(
                    id = PRESENCE_MESSAGE_ID,
                    severity = MessageSeverity.ERROR,
                    title = "הזמינות לא אושרה מול השרת",
                    body = result.reason.toHebrewMessage(FailureContext.PRESENCE),
                    // Persistent condition, persistent surface (not a snackbar the
                    // agent can dismiss while the underlying problem remains) — it
                    // clears itself via resolve() the moment a write actually
                    // succeeds, not before.
                    dismissible = false,
                    deduplicationKey = PRESENCE_MESSAGE_ID,
                ),
            )
        }
    }

    // Persists the outcome (PresenceStateStore) before touching anything in-memory:
    // PresenceForegroundService runs with no Activity/ViewModel alive, and
    // AppMessageCenter is an in-memory-only StateFlow — a process death between a
    // failed registration and the agent next opening the app would otherwise lose
    // the record entirely (the live incident this exists to close — see
    // docs/android-presence-and-call-ux.md's "Update 2026-08-14 (later)").
    private suspend fun reportPushRegistrationResult(result: Result<Unit>) {
        val nowMs = System.currentTimeMillis()
        result.fold(
            onSuccess = {
                DependencyContainer.presenceStateStore?.recordPushRegistrationOutcome(null, nowMs)
                PushRegistrationState.recordSuccess()
                AppMessageCenter.resolve(PUSH_REGISTRATION_MESSAGE_ID)
            },
            onFailure = { e ->
                val failure = e.toAppFailure()
                // WHICH step failed — VoxClientManager.registerCurrentPushToken tags
                // its two failure domains distinctly ("fcm_token: ..." vs
                // "registerForPushNotifications: ..."); AppFailure itself stays the
                // coarse, reused-everywhere taxonomy (see PersistedPushRegistrationFailure's
                // kdoc for why this travels as a separate raw string).
                val detail = e.message
                DependencyContainer.presenceStateStore?.recordPushRegistrationOutcome(failure, nowMs, detail)
                PushRegistrationState.recordFailure(failure, detail)
                AppMessageCenter.publish(
                    UiMessage(
                        id = PUSH_REGISTRATION_MESSAGE_ID,
                        severity = MessageSeverity.WARNING,
                        title = "המכשיר לא נרשם לקבלת שיחות",
                        body = failure.toHebrewMessage(FailureContext.PUSH_REGISTRATION) +
                            pushFailureStageSuffix(detail),
                        dismissible = false,
                        deduplicationKey = PUSH_REGISTRATION_MESSAGE_ID,
                    ),
                )
            },
        )
    }

    /**
     * Names WHICH half of push registration failed, in the agent-visible message.
     *
     * The distinction was already captured and persisted and then never shown, so the
     * owner saw one generic sentence for two unrelated faults. That gap cost real
     * time: the platform reported `push_results: []` and "No push notifications has
     * been sent" while the device could not say which step produced it, and the
     * answer had to be chased through telephony logs instead.
     *
     * The two halves fail for different reasons and are fixed by different people —
     * a device-local Google Play services problem versus the telephony platform
     * rejecting a token we did obtain — so the text separates them by CAUSE rather
     * than quoting the tag, which is developer shorthand and not something to put in
     * front of an agent. Anything untagged adds nothing rather than guessing.
     */
    internal fun pushFailureStageSuffix(detail: String?): String = when {
        detail == null -> ""
        detail.startsWith("fcm_token:") ->
            " התקלה במכשיר עצמו: לא התקבל מזהה משירותי Google."
        detail.startsWith("vox_register:") || detail.startsWith("registerForPushNotifications:") ->
            " המכשיר קיבל מזהה, אך מערכת הטלפוניה דחתה את הרישום."
        else -> ""
    }

    /**
     * Re-reads the device's ring capability AND republishes the agent-visible banner
     * to match it.
     *
     * The banner used to be published from exactly one place — the READY branch of
     * [applyStatus] — while `RingCapabilityState.refresh` was ALSO called on its own
     * from PresenceForegroundService (startup + every heartbeat). That split meant the
     * persistent notification tracked reality on a 30-second cadence while the in-app
     * banner was frozen at whatever was true the last time the agent set themselves
     * READY. Since the banner is `dismissible = false` and clears only via
     * `resolve(RING_CAPABILITY_MESSAGE_ID)` inside [reportRingCapability], a fixed
     * problem stayed on screen indefinitely.
     *
     * That is not hypothetical: the owner tapped the banner's own "אפשר עכשיו" button
     * (added the same day), granted full-screen-intent in Settings — device screenshot
     * confirms the system toggle ON — came back, and the app still said
     * "מסך שיחה נכנסת לא ייפתח במכשיר נעול". A fix-it button whose success cannot clear
     * the message it fixed is worse than no button: it teaches the agent the banner is
     * noise, which is exactly what it must never be.
     *
     * So refresh and report are one call now, and every caller uses it. Cheap and
     * network-free (RingCapabilityChecker only reads NotificationManager), so calling
     * it on the heartbeat cadence costs nothing.
     */
    fun refreshAndReportRingCapability(context: Context) {
        reportRingCapability(RingCapabilityState.refresh(context))
    }

    // Two distinct messages because the consequences differ (RingCapability's kdoc):
    // !canAlert means no call reaches this agent AT ALL; !canRingOnLockedScreen (with
    // canAlert still true) means calls arrive but a locked/pocketed phone will miss
    // them. Both clear via resolve() the instant a later check comes back clean —
    // there is no "fix" step to await here, just a fresh read.
    private fun reportRingCapability(capability: RingCapability) {
        when {
            !capability.canAlert -> AppMessageCenter.publish(
                UiMessage(
                    id = RING_CAPABILITY_MESSAGE_ID,
                    severity = MessageSeverity.ERROR,
                    title = "התראות למסוף חסומות",
                    body = "שיחות נכנסות לא יוצגו כלל במכשיר זה.",
                    primaryAction = MessageAction("פתח הגדרות התראות", ACTION_OPEN_NOTIFICATION_SETTINGS),
                    dismissible = false,
                    deduplicationKey = RING_CAPABILITY_MESSAGE_ID,
                ),
            )
            !capability.canRingOnLockedScreen -> AppMessageCenter.publish(
                UiMessage(
                    id = RING_CAPABILITY_MESSAGE_ID,
                    severity = MessageSeverity.WARNING,
                    title = "מסך שיחה נכנסת לא ייפתח במכשיר נעול",
                    body = "שיחות עלולות להתפספס כשהמכשיר נעול.",
                    primaryAction = MessageAction("אפשר עכשיו", ACTION_OPEN_FULL_SCREEN_INTENT_SETTINGS),
                    dismissible = false,
                    deduplicationKey = RING_CAPABILITY_MESSAGE_ID,
                ),
            )
            else -> AppMessageCenter.resolve(RING_CAPABILITY_MESSAGE_ID)
        }
    }
}
