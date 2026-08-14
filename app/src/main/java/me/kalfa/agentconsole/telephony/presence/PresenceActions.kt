package me.kalfa.agentconsole.telephony.presence

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import me.kalfa.agentconsole.data.toAppFailure
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.domain.error.AppResult
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.telephony.vox.RingCapability
import me.kalfa.agentconsole.telephony.vox.RingCapabilityState
import me.kalfa.agentconsole.telephony.vox.VoxAuthException
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
    private const val TAG = "PresenceActions"
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
        val current = presence.currentStatus.value
        // A heartbeat re-DECLARES the agent's own status. IN_CALL is not the agent's
        // to declare — the server sets it from a live human_agent_call_legs row and
        // POST /api/agents/status answers 400 for it by design — and currentStatus
        // holds it because fetchAgentStatus read it back from the database. Re-sending
        // it would fail every 30 seconds and raise a permanent
        // "הזמינות לא אושרה מול השרת" over a state that is not wrong.
        //
        // Deliberately NOT substituted with something sendable: this app does not track
        // what the agent declared separately from what the server observed, so any
        // substitute would be a guess. The consequence is stated rather than hidden —
        // while the server holds this agent at in_call, agent_status.updated_at stops
        // advancing and the server's 90s freshness gate stops routing to them. That is
        // the correct outcome for an agent already on a call, and it self-heals the
        // moment the server clears in_call and the next heartbeat has something honest
        // to send. If that ever needs to change, the fix is a separately-tracked
        // declared status, not a status the server rejects.
        if (!current.isAgentSettable) return
        reportPresenceResult(presence.setStatus(current))
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

    /**
     * Persists the outcome (PresenceStateStore) before touching anything in-memory:
     * PresenceForegroundService runs with no Activity/ViewModel alive, and
     * AppMessageCenter is an in-memory-only StateFlow — a process death between a
     * failed registration and the agent next opening the app would otherwise lose
     * the record entirely (the live incident this exists to close — see
     * docs/android-presence-and-call-ux.md's "Update 2026-08-14 (later)").
     *
     * The persist is BEST-EFFORT, via [persistPushRegistrationOutcome], and that is
     * load-bearing in two ways.
     *
     * `PresenceStateStore`'s reads guard IOException (`.catch { if (e is IOException) …`)
     * and its writes — `DataStore.edit` — do not, which reads as an oversight rather
     * than a decision. An IOException from that write used to escape this function
     * into `ConsoleViewModel.setAgentStatus`'s `viewModelScope.launch` and
     * `PresenceForegroundService`'s scope, neither of which installs a
     * CoroutineExceptionHandler, so it reached the thread's default handler and took
     * the process down — on the READY path, the single most-used action in the app,
     * and again on every 30s heartbeat via [retryPushRegistrationIfFailed].
     *
     * Worse than the crash: because the write ran FIRST, a failing write also skipped
     * `PushRegistrationState` and `AppMessageCenter` below — so the one code path
     * whose entire purpose is to stop a push-registration failure being silently
     * discarded would itself silently discard it, then crash. A durable record is a
     * strengthening of the in-memory report, never a precondition for it, so the
     * ordering stays (the process-death argument above still holds) and only the
     * failure mode changes.
     */
    private suspend fun reportPushRegistrationResult(result: Result<Unit>) {
        val nowMs = System.currentTimeMillis()
        result.fold(
            onSuccess = {
                persistPushRegistrationOutcome(null, nowMs, null)
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
                persistPushRegistrationOutcome(failure, nowMs, detail)
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

    // Best-effort by design — see reportPushRegistrationResult's kdoc. Logged rather
    // than surfaced: an agent cannot act on "the device could not write a diagnostic
    // record", and the fact this record was going to carry is already on its way to
    // them through PushRegistrationState + AppMessageCenter, which is what the
    // caller runs next precisely because this one may not have landed.
    private suspend fun persistPushRegistrationOutcome(
        failure: me.kalfa.agentconsole.domain.error.AppFailure?,
        nowMs: Long,
        detail: String?,
    ) {
        try {
            DependencyContainer.presenceStateStore?.recordPushRegistrationOutcome(failure, nowMs, detail)
        } catch (e: CancellationException) {
            // Not a storage failure. kotlinx's CancellationException is a subclass of
            // Exception, so a bare `catch (Exception)` here would swallow the
            // cancellation PresenceForegroundService.onDestroy's scope.cancel() sends,
            // and this coroutine would keep running after its scope was torn down.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not persist push-registration outcome: ${e.javaClass.simpleName}: ${e.message}")
        }
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
        isLoginStageFailure(detail) ->
            " ההתחברות למערכת הטלפוניה נכשלה, ולכן הרישום כלל לא בוצע."
        else -> ""
    }

    /**
     * A THIRD failure domain the two branches above cannot express: the registration was
     * never attempted, because logging in to Voximplant failed first.
     *
     * This is not a hypothetical gap. `PresenceActions.applyStatus` reports an
     * `ensureLoggedIn` failure through `reportPushRegistrationResult` — the same banner,
     * titled "המכשיר לא נרשם לקבלת שיחות" — so a login failure has always been shown to the
     * agent as a registration failure, with the one field that would distinguish them
     * silently dropped by the `else -> ""` above. The banner the owner photographed on
     * 2026-08-14 was bare, and a bare banner is exactly what this produces.
     *
     * Deliberately NOT folded into the `else`: the contract that an unrecognised message
     * adds nothing rather than guessing still holds, and is still tested. This branch
     * matches only strings this codebase itself produces, enumerated below.
     */
    private fun isLoginStageFailure(detail: String): Boolean =
        // VoxClientManager tags every SDK-boundary step it wraps. All five verified
        // against the literals there: "connect: $error" (connectSuspend),
        // "requestOneTimeKey: $error", "loginWithOneTimeKey: $error",
        // "loginWithAccessToken: $error" and "refreshToken: $error".
        LOGIN_STAGE_TAGS.any { detail.startsWith(it) } ||
            // The sdk-auth exchange, which fails BEFORE the SDK is touched at all and
            // carries no prefix. Referenced through the object rather than copied as a
            // literal so a change to the message cannot silently unmatch it.
            detail == VoxAuthException.NoSession.message ||
            // The remaining VoxAuthException cases all name the route they came from:
            // "sdk-auth HTTP $code", "sdk-auth 200 without a hash",
            // "not a console agent (sdk-auth 401)" and
            // "agent has no Voximplant identity (sdk-auth 409)" — see VoxTelephony.kt.
            detail.contains("sdk-auth")

    private val LOGIN_STAGE_TAGS = listOf(
        "connect:",
        "requestOneTimeKey:",
        "loginWithOneTimeKey:",
        "loginWithAccessToken:",
        "refreshToken:",
    )

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
