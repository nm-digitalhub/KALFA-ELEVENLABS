package me.kalfa.agentconsole.telephony.presence

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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

            loginAndRegisterForPush(voxUsername)

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
     * Makes sure this device is actually registered for push, on every heartbeat —
     * logging in first if the SDK is logged out, rather than giving up when it is.
     *
     * Registration otherwise happens exactly once per transition to READY, so a device
     * whose registration failed for a transient reason — no network at the moment the
     * agent went available, Google Play services still waking — stayed unregistered for
     * the whole shift while reporting itself available. The server would route calls to
     * it and Voximplant would have no token to push to, which is the precise shape of the
     * incident this file's push handling keeps orbiting.
     *
     * THE ORIGINAL DESIGN, kept because the next section is an argument against it and
     * an argument needs its opponent stated fairly. It was gated three ways so it cost
     * nothing in the normal case: no retry unless [PushRegistrationState] held a failure,
     * none without a client, and none unless the SDK was genuinely logged in —
     * `ensureLoggedIn` needs a vox username this service did not have, and
     * `registerCurrentPushToken` only binds a token to an identity once the client is
     * logged in anyway (Voximplant's Android push guide: *"the token is registered only
     * after the client logs in"*). A logged-out client was "a job for the next READY, not
     * for the heartbeat".
     *
     * Two of those three are gone. The guide's claim still holds and is still respected —
     * registration happens only after a successful login; what changed is that the
     * heartbeat now PERFORMS that login instead of waiting for someone else to.
     *
     * ---- Why the last of those three gates is gone, and the name with it ----
     *
     * `if (!vcm.isLoggedIn) return` could not fire in the one situation this function
     * exists for. `isLoggedIn` reads `Client.clientState`, which is PROCESS-LOCAL, so it
     * is false after every process death — and nothing on the heartbeat path could make
     * it true, because `ensureLoggedIn`'s only callers were a status change,
     * `VoxWakePushHandler` and the FCM service. A retry that structurally cannot run when
     * the thing it retries is broken is not a retry. The comment above ("a job for the
     * next READY") assumed a next READY would come; nothing prompts one, and the
     * dashboard tells the agent they are already available.
     *
     * The first gate is gone too, and for the reason that mattered more: a device that
     * has NEVER registered holds no failure, so `lastFailure == null` skipped exactly the
     * case the owner was in. `null` conflates "succeeded" with "never attempted", which
     * is the same nullable-as-tri-state mistake as the missing `else` next door.
     *
     * Two things had to exist before this was fixable, and both landed tonight: the
     * service can now obtain a vox username without a prior login (VoxTokenStore
     * .saveUsername), and it can obtain a Supabase JWT while backgrounded (the auth
     * lifecycle-callback change in DependencyContainer). Before either, a heartbeat login
     * would have failed at `fetchHash` every time.
     *
     * MAU cost, the reason the gate was defensible when it was written — MEASURED against
     * the live docs rather than this file's own kdoc (`getDoc` on
     * `getting-started.billing`, fetched 2026-08-14). Voximplant: *"An active user is
     * defined by a unique credential logging in at least once a month. One user logging in
     * from multiple devices counts as one MAU."* Free tier: 1,000 MAU/month, and exceeding
     * it blocks NEW user logins (`LoginMauAccessDeniedError`). So MAU counts CREDENTIALS
     * PER MONTH, not logins — this console has one agent, so the entire app costs 1 MAU a
     * month whether it logs in once or every thirty seconds. The quota argument against a
     * heartbeat login is void, and it was void when the gate was written.
     *
     * What the loop does cost, stated because it is the real risk: when login genuinely
     * fails, this attempts a connect+login every 30s for as long as the shift lasts.
     * That is the same cadence the presence heartbeat already runs at, so it is not a new
     * order of magnitude, and `ensureLoggedIn` early-returns once the client is logged in
     * so the normal case is free. If the field shows a device stuck in a failing login,
     * the answer is a backoff here — not restoring a gate that made the failure permanent
     * and silent.
     */
    suspend fun ensurePushRegistration() {
        val vcm = DependencyContainer.voxClientManager ?: run {
            reportPushRegistrationResult(
                Result.failure(
                    VoxAuthException.Sdk("no_device_identity: telephony client unavailable"),
                ),
            )
            return
        }
        // Already logged in AND nothing on record to fix — the normal steady state, and
        // the only path that must stay free.
        if (vcm.isLoggedIn && PushRegistrationState.lastFailure.value == null) return
        if (vcm.isLoggedIn) {
            reportPushRegistrationResult(vcm.registerCurrentPushToken())
            return
        }
        // BOUNDED, and this bound is the difference between fixing the push bug and
        // reintroducing the presence one.
        //
        // Nothing in VoxClientManager's login path has a timeout: eight bare
        // suspendCancellableCoroutine blocks (connect, requestOneTimeKey, both logins,
        // refreshToken, register/unregister push, the FCM token task), all under
        // ensureLoggedIn's loginMutex. If the SDK never invokes a callback — a network
        // black hole, which is exactly the pocketed-phone case this feature exists for —
        // the coroutine parks forever. PresenceForegroundService's heartbeat calls this
        // LAST in a sequential tick inside `while (isActive) { delay(30s); … }`, so a
        // park here means the loop never reaches its next delay and agent_status
        // .updated_at stops advancing: the precise symptom of 2026-08-14, re-entered
        // through a new door. failSoftly cannot catch it either — a coroutine parked on
        // a callback that never fires throws nothing.
        //
        // Precedent rather than invention: VoxFirebaseMessagingService already wraps the
        // same chain in withTimeoutOrNull(9_000), because someone had already concluded
        // this call can outlive its budget.
        //
        // 15s keeps the worst-case tick under the 30s period once resendCurrentStatus's
        // own auth-settle (3s) and POST are accounted for, so ticks stay regular instead
        // of drifting.
        //
        // Bounding HERE rather than inside VoxClientManager's wrappers is deliberate: the
        // FCM path already has its own 9s budget, and a foreground READY tap has a human
        // waiting on it who may legitimately want a longer wait. The regression is the
        // heartbeat's, so the bound is the heartbeat's. Pushing timeouts down into the
        // SDK wrappers so every caller inherits one is the better long-term shape and is
        // NOT done here.
        val finished = withTimeoutOrNull(LOGIN_ATTEMPT_TIMEOUT_MS) {
            loginAndRegisterForPush(DependencyContainer.voxTokenStore?.loadUsername())
            true
        }
        // Reported, not swallowed. withTimeoutOrNull cancels only its own child, so this
        // coroutine is still live and CAN publish — which matters, because the
        // cancellation itself cannot: ensureLoggedIn's `runCatching` catches Throwable
        // including CancellationException, so a timed-out attempt unwinds without ever
        // reaching reportPushRegistrationResult. Without this line a hung login would be
        // silent, which is the failure mode this whole file has been correcting.
        //
        // (The mutex does release: Mutex.withLock unlocks in a `finally`, which runs
        // whether the block returns normally or unwinds — so a timed-out attempt does not
        // wedge the next one, or a foreground READY tap waiting behind it.)
        if (finished == null) {
            reportPushRegistrationResult(
                Result.failure(VoxAuthException.Sdk("login_timeout: no SDK response in ${LOGIN_ATTEMPT_TIMEOUT_MS}ms")),
            )
        }
    }

    // See ensurePushRegistration for why this exists and why 15s.
    private const val LOGIN_ATTEMPT_TIMEOUT_MS = 15_000L

    /**
     * Log in to Voximplant if needed, then bind this device's push token — the one place
     * that sequence lives, so the READY path and the heartbeat cannot report it
     * differently.
     *
     * A null [voxUsername] is REPORTED rather than skipped. The `if (voxUsername != null
     * && vcm != null)` this replaces had no `else`, so a device that could not register
     * said nothing at all — no banner, no [PushRegistrationState], no persisted record.
     * "Whatever it cannot do, it must say" is the lesson of this whole class of bug, and
     * it belongs in the code rather than in a doc.
     */
    private suspend fun loginAndRegisterForPush(voxUsername: String?) {
        val vcm = DependencyContainer.voxClientManager
        if (voxUsername == null || vcm == null) {
            val reason =
                if (voxUsername == null) "no_device_identity: vox username unknown"
                else "no_device_identity: telephony client unavailable"
            reportPushRegistrationResult(Result.failure(VoxAuthException.Sdk(reason)))
            return
        }
        vcm.ensureLoggedIn(voxUsername).fold(
            onSuccess = { reportPushRegistrationResult(vcm.registerCurrentPushToken()) },
            onFailure = { e -> reportPushRegistrationResult(Result.failure(e)) },
        )
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
     * and again on every 30s heartbeat via [ensurePushRegistration].
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
        // A FOURTH domain, and the only sentence here I wrote rather than was given.
        // It is not a login failure — nothing was attempted, because the device does
        // not yet know which Voximplant identity it is. Reusing the login sentence
        // would assert an attempt that never happened, which is the "asserting a cause
        // it does not know" this function is supposed to avoid. Flagged for a wording
        // ruling; the register follows the two above it (cause, then the one action
        // that resolves it).
        detail.startsWith("no_device_identity:") ->
            " זהות הטלפוניה של המכשיר עדיין לא נקלטה — יש לבחור 'זמין' באפליקציה."
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
        // A login that never returned is a login that failed — the approved login-stage
        // sentence is accurate for it, so this adds no new agent-facing copy.
        "login_timeout:",
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
