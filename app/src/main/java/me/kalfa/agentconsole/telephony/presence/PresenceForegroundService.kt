package me.kalfa.agentconsole.telephony.presence

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.Service
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.AgentStatus
import me.kalfa.agentconsole.domain.telephony.PresenceSyncState
import me.kalfa.agentconsole.telephony.vox.RingCapability
import me.kalfa.agentconsole.telephony.vox.RingCapabilityState

// The signals PresenceNotificationBuilder.build needs — grouped so the combine() below
// doesn't have to thread an unlabeled tuple through collect.
//
// pushRegistrationDetail was the missing one, and its absence is why the notification
// could never say which push step failed. PresenceNotificationBuilder.build and
// expandedTextFor have taken a `pushRegistrationDetail` since the day the stage
// sentences were added, and PushRegistrationState.lastFailure has always carried it —
// but this class kept only `pushFailure?.failure` and dropped `.detail` on the floor, so
// every build() call fell back to the parameter's null default and
// pushFailureStageSuffix(null) returned "" for EVERY failure, correctly tagged or not.
// The notification was therefore structurally incapable of carrying the one field the
// whole push investigation turned on, and its silence was repeatedly read as evidence
// that the failure was untagged. It was not evidence of anything.
private data class PresenceNotificationInputs(
    val status: AgentStatus,
    val syncState: PresenceSyncState,
    val pushRegistrationFailure: AppFailure?,
    val ringCapability: RingCapability?,
    val pushRegistrationDetail: String?,
)

// The AOSP claims in this file are cited by SYMBOL — `ActiveServices
// .serviceForegroundTimeout()`, `ServiceStartNotAllowedException extends
// IllegalStateException` — deliberately, not by line number. A symbol survives the drift
// that a `refs/heads/main` line number does not, and it is grep-able as written. If one
// stops resolving, treat the claim as unverified rather than hunting for where it moved:
// the platform changing that code is the event these findings would need re-deriving for.
// (CallForegroundService carries the line-numbered citations and a resolve recipe.)
//
// Makes "I am on shift" a technical fact rather than a ViewModel-scoped claim that
// dies with the Activity — see docs/android-presence-and-call-ux.md §1 for the full
// design and the reasoning behind every decision below (cadence, specialUse FGS type,
// what survives what). Started/stopped by MainActivity observing
// AgentPresence.shiftActive; also self-stops if that flag flips false through any
// other path, so it never depends on the Activity being alive to notice.
class PresenceForegroundService : Service() {

    // The handler is load-bearing, not decoration. Every launch below can reach
    // PresenceStateStore, whose WRITE paths (save/recordPushRegistrationOutcome) do not
    // catch IOException the way its read paths deliberately do — and an uncaught
    // exception in a `launch` is not contained by the scope: kotlinx's own
    // exception-handling guide says these builders "treat exceptions as uncaught
    // exceptions, similar to Java's Thread.uncaughtExceptionHandler", which on Android
    // means the default handler kills the process. SupervisorJob does NOT change that —
    // it only stops one child's failure from cancelling its siblings. A failed disk
    // write, or an SDK object that isn't ready, must degrade this service; it must not
    // take the whole console down while an agent is on shift.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "presence coroutine failed: ${t::class.simpleName}: ${t.message}")
        },
    )
    private var heartbeatJob: Job? = null
    private var observerJob: Job? = null
    private var shiftWatcherJob: Job? = null

    // Shared, canonical DataStore handle (DependencyContainer.presenceStateStore) —
    // NOT a locally-constructed instance. See DependencyContainer's kdoc: this keeps
    // this service and PresenceActions reading/writing the exact same file rather
    // than two separate wrapper instances that merely happen to agree.
    private val stateStore: PresenceStateStore?
        get() = DependencyContainer.presenceStateStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DependencyContainer.attach(applicationContext)
        PresenceNotificationBuilder.ensureChannel(applicationContext)
        // No OS callback exists for "notification/full-screen-intent settings
        // changed" — refresh synchronously here (cheap, no network) so the very
        // first notification built below already reflects reality; refreshed again
        // every heartbeat tick (see startHeartbeatAndObservers).
        PresenceActions.refreshAndReportRingCapability(applicationContext)
        // Seed the in-process push-registration signal from the durable record
        // BEFORE the first notification is built below — a process that was just
        // restarted (kill, then START_STICKY) must show the truth immediately, not
        // wait for a fresh registration attempt that may not happen for a while.
        scope.launch {
            val persisted = stateStore?.loadPushRegistrationFailure()
            PushRegistrationState.seed(
                persisted?.let { PushRegistrationFailureInfo(it.failure, it.detail) },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() must be called promptly after onStartCommand — always do
        // it synchronously first, with a best-effort notification, before any branch
        // below that needs a suspend read (persisted-state resume) or write (a
        // notification-action status change) gets a chance to run.
        if (!startForegroundCompat()) {
            // We are not a foreground service and cannot become one. Stop immediately
            // rather than leave a started-but-not-foreground service behind: AOSP's
            // ActiveServices.serviceForegroundTimeout() discards the
            // "Context.startForegroundService() did not then call
            // Service.startForeground()" ANR once the record is `destroying`, so
            // stopping here is what prevents a second, unrelated crash on top of the
            // one already logged.
            stopSelf()
            return START_NOT_STICKY
        }

        when {
            intent == null -> {
                // System-initiated restart after a kill (force-stop never restarts a
                // service — see docs §1). Resume ONLY with solid persisted evidence
                // the agent was actually on shift; otherwise stop, fail-closed.
                scope.launch { resumeFromPersistedStateOrStop() }
            }
            intent.action == PresenceNotificationBuilder.ACTION_SET_STATUS -> {
                val status = intent.getStringExtra(PresenceNotificationBuilder.EXTRA_STATUS)
                    ?.let { runCatching { AgentStatus.valueOf(it) }.getOrNull() }
                if (status != null) {
                    scope.launch { PresenceActions.applyStatus(status, currentVoxUsername()) }
                }
            }
            else -> Unit // plain "ensure running" (MainActivity) — nothing else to do
        }

        startHeartbeatAndObservers()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun resumeFromPersistedStateOrStop() {
        val persisted = stateStore?.load()
        if (shouldResumeFromPersistedState(persisted)) {
            runCatching { PresenceActions.applyStatus(persisted!!.status, persisted.voxUsername) }
        } else {
            stopSelfCleanly()
        }
    }

    private fun startHeartbeatAndObservers() {
        val presence = DependencyContainer.agentPresence

        if (observerJob?.isActive != true) {
            observerJob = scope.launch {
                combine(
                    presence.currentStatus,
                    presence.syncState,
                    PushRegistrationState.lastFailure,
                    RingCapabilityState.current,
                ) { status, sync, pushFailure, ringCapability ->
                    PresenceNotificationInputs(
                        status,
                        sync,
                        pushFailure?.failure,
                        ringCapability,
                        pushFailure?.detail,
                    )
                }.collect { inputs ->
                        if (presence.shiftActive.value) {
                            // save() is a DataStore write and DataStore does not swallow
                            // IOException — an unguarded throw here would end this
                            // collect for good (the notification would then freeze at
                            // whatever it last showed) on top of killing the process.
                            failSoftly("notification update") {
                                updateNotification(inputs)
                                stateStore?.save(inputs.status, true, currentVoxUsername())
                            }
                        }
                    }
            }
        }

        if (shiftWatcherJob?.isActive != true) {
            shiftWatcherJob = scope.launch {
                presence.shiftActive.shiftEndedAfterBeingActive().collect { stopSelfCleanly() }
            }
        }

        if (heartbeatJob?.isActive != true) {
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (presence.shiftActive.value) {
                        // One bad tick must not end the shift's heartbeat. All three
                        // calls below can throw — PresenceActions.reportPushRegistration
                        // Result writes to DataStore (no IOException handling on the
                        // write path), and retryPushRegistrationIfFailed reads the
                        // Voximplant SDK's Client singleton, which this process may
                        // never have initialised. Without this guard a single throw
                        // cancels this coroutine permanently: the notification would go
                        // on showing "זמין" while nothing reached the server again for
                        // the rest of the shift, and the agent would look available
                        // while the 90s gate quietly stopped routing to them. Retrying
                        // on the next tick is the whole point of a heartbeat.
                        failSoftly("heartbeat tick") {
                            // Re-send the CURRENT status — this is a freshness refresh,
                            // not a status change (docs §1, "What the heartbeat actually
                            // sends"). Goes through PresenceActions rather than calling
                            // AgentPresence.setStatus directly: the direct call updates
                            // syncState (which this notification observes) but never
                            // AppMessageCenter, so a sync failure that healed itself here
                            // left the in-app banner insisting it hadn't. See that
                            // function's kdoc.
                            PresenceActions.resendCurrentStatus()
                            // No OS callback for a settings change — piggyback on the
                            // same cadence so a fix (or a new break) is reflected within
                            // one heartbeat interval, not only at service (re)start.
                            PresenceActions.refreshAndReportRingCapability(applicationContext)
                            // Registration otherwise happens once per transition to
                            // READY, so a transient failure at that moment (no network,
                            // Play services still waking) left the device unregistered
                            // for the whole shift while reporting itself available.
                            // No-ops unless a failure is actually on record — see its
                            // kdoc.
                            PresenceActions.retryPushRegistrationIfFailed()
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs [block], letting a cancellation through and turning anything else into a log
     * line. The two callers are long-lived loops whose job is to keep going.
     *
     * `CancellationException` is deliberately re-thrown rather than caught: swallowing it
     * would keep a coroutine alive after its scope was cancelled (`onDestroy`), which is
     * the opposite of what this service needs at shutdown. That is also why this is not
     * `runCatching`, which catches it.
     */
    private suspend fun failSoftly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "$what failed, continuing: ${t::class.simpleName}: ${t.message}")
        }
    }

    private fun updateNotification(inputs: PresenceNotificationInputs) {
        val mgr = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        mgr?.notify(
            PresenceNotificationBuilder.NOTIFICATION_ID,
            // Named arguments from here on. build()'s own kdoc warns that
            // pushRegistrationDetail sits last "because PresenceForegroundService calls
            // this positionally, and inserting a parameter mid-signature would silently
            // rebind ringCapability to a String?" — the parameter was then added and this
            // call was never updated, so it silently took the null default instead.
            // Naming them removes the hazard the comment was written about.
            PresenceNotificationBuilder.build(
                context = this,
                status = inputs.status,
                syncState = inputs.syncState,
                pushRegistrationFailure = inputs.pushRegistrationFailure,
                ringCapability = inputs.ringCapability,
                pushRegistrationDetail = inputs.pushRegistrationDetail,
            ),
        )
    }

    private suspend fun currentVoxUsername(): String? =
        DependencyContainer.voxTokenStore?.load()?.voxUsername

    private fun stopSelfCleanly() {
        heartbeatJob?.cancel()
        observerJob?.cancel()
        // NOT `scope.launch`: stopSelf() below leads to onDestroy(), which cancels
        // `scope` — most likely before a DataStore write that was only just dispatched
        // has landed, and a cancelled scope will not even start a coroutine that is
        // still pending. The record this clears is exactly the one a later
        // START_STICKY restart resumes from (docs §1, "System kill under memory
        // pressure"), so losing the write means a device that went off shift can come
        // back believing it is on shift. The write must outlive the service that asked
        // for it; the failure is swallowed because there is nothing left to retry with
        // once we are stopping.
        val store = stateStore
        terminalWriteScope.launch {
            runCatching { store?.clear() }
                .onFailure { Log.w(TAG, "clearing presence state failed: ${it.message}") }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Becomes a foreground service, or reports that it could not. Returns false instead
     * of throwing.
     *
     * `specialUse` has no runtime prerequisites of its own
     * (`developer.android.com/develop/background-work/services/fgs/service-types`:
     * "Runtime prerequisites: None"), and both start paths are documented exemptions
     * from the Android 12+ background-start rule — MainActivity starts it from a visible
     * activity, PresenceActionReceiver from a notification action ("The user performs an
     * action on a UI element related to your app. For example, they might interact with
     * a bubble, notification, widget, or activity",
     * `.../fgs/restrictions-bg-start`). So this is not expected to fail.
     *
     * It is still guarded, because "not expected" is not "cannot", and the failure modes
     * that remain are all crashes rather than errors: a user-imposed background
     * restriction still produces `ForegroundServiceStartNotAllowedException` (an
     * `IllegalStateException` — AOSP `ServiceStartNotAllowedException extends
     * IllegalStateException`), a missing FOREGROUND_SERVICE_SPECIAL_USE would produce
     * `SecurityException`, and `PresenceNotificationBuilder.build` /
     * `DependencyContainer.agentPresence` are ordinary code that can throw (the latter
     * calls `error()` by design in a release build with no Supabase configuration).
     * Losing presence is the documented degradation for all of them — the server's 90s
     * freshness gate then stops routing to this device, which is exactly what it is for
     * (docs §1, "Do not defeat the 90-second gate"). Crashing is not.
     */
    private fun startForegroundCompat(): Boolean = try {
        val presenceNow = DependencyContainer.agentPresence
        // Named for the same reason as updateNotification's call above. This is the
        // FIRST notification a restarted process posts, built from the record seeded out
        // of PresenceStateStore in onCreate — so it is also the one most likely to be
        // carrying a push failure inherited from a previous process.
        val pushFailure = PushRegistrationState.lastFailure.value
        val notification = PresenceNotificationBuilder.build(
            context = this,
            status = presenceNow.currentStatus.value,
            syncState = presenceNow.syncState.value,
            pushRegistrationFailure = pushFailure?.failure,
            ringCapability = RingCapabilityState.current.value,
            pushRegistrationDetail = pushFailure?.detail,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                PresenceNotificationBuilder.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(PresenceNotificationBuilder.NOTIFICATION_ID, notification)
        }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "presence foreground start refused: ${t::class.simpleName}: ${t.message}")
        false
    }

    companion object {
        private const val TAG = "PresenceFgs"

        // Deliberately process-scoped rather than service-scoped, and used for exactly
        // one thing: the terminal "this shift is over" write in stopSelfCleanly(). See
        // that function for why it must not die with the service. Nothing long-running
        // is ever launched here, so there is no lifecycle to leak.
        private val terminalWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // See docs/android-presence-and-call-ux.md §1 "Cadence: 30s, not the
        // browser's 60s" for the safety-margin arithmetic against the server's 90s
        // freshness gate (AGENT_STATUS_FRESHNESS_MS, beta/src/lib/console/presence.ts)
        // — do not "align" this back to 60s without re-reading that section.
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            startGuarded(context, Intent(context, PresenceForegroundService::class.java))
        }

        fun updateStatus(context: Context, status: AgentStatus) {
            val intent = Intent(context, PresenceForegroundService::class.java)
                .setAction(PresenceNotificationBuilder.ACTION_SET_STATUS)
                .putExtra(PresenceNotificationBuilder.EXTRA_STATUS, status.name)
            startGuarded(context, intent)
        }

        /**
         * Both entry points funnel through here because both are reachable with no
         * activity on screen — MainActivity's LaunchedEffect can run while the app is
         * merely started, and PresenceActionReceiver runs with the app fully
         * backgrounded.
         *
         * Both are documented exemptions from the Android 12+ background-start rule (a
         * user-visible activity; an interaction with a notification —
         * `developer.android.com/develop/background-work/services/fgs/restrictions-bg-start`),
         * so this is expected to succeed. The catch exists for the cases the exemption
         * list does not cover, and each of them would otherwise crash the app from a
         * BroadcastReceiver: a user-imposed background restriction still yields
         * `ForegroundServiceStartNotAllowedException`, which extends
         * `ServiceStartNotAllowedException extends IllegalStateException` (AOSP
         * `frameworks/base/core/java/android/app/ServiceStartNotAllowedException.java`)
         * — so catching `IllegalStateException` covers it without naming an API-31-only
         * class in a catch clause on a minSdk-24 app.
         *
         * Degrading here means presence silently stops; that is the same outcome the
         * 90s server-side freshness gate already handles, and it is logged. It is not
         * surfaced to the agent — this file has no message channel of its own — which
         * is a real gap worth closing where the banners live.
         */
        private fun startGuarded(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "presence FGS start not allowed: ${e.message}")
            } catch (e: SecurityException) {
                Log.e(TAG, "presence FGS start refused: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceForegroundService::class.java))
        }
    }
}

/**
 * "The shift ended" — emissions of `false` that come AFTER a `true` has been seen, and
 * never the `false` a fresh collector is handed on subscription.
 *
 * Pulled to the top level, and kept free of Android and SDK types, so the rule is
 * directly unit-testable — the same separation used by `shouldResumeFromPersistedState`
 * in this package and `planSilentLogin`/`canActOnOffer` next door.
 *
 * The plain `filter { !it }` this replaces made the documented START_STICKY resume path
 * unreachable. `AgentPresence.shiftActive` is a `StateFlow` initialised to `false`
 * (`SupabaseImplementations.kt`, `_shiftActive = MutableStateFlow(false)`), and a
 * StateFlow replays its current value to every new collector — so on a system-restarted
 * process the watcher's FIRST emission was the process-start default, not a shift
 * ending. It ran `stopSelfCleanly()` immediately: the service stopped itself and wiped
 * the persisted record while `resumeFromPersistedStateOrStop()` was still awaiting the
 * DataStore read it needed to set the flag. The restart path documented in docs §1
 * ("System kill under memory pressure") therefore stopped instead of resuming, and took
 * the evidence with it.
 *
 * Known limitation, deliberately not solved here: a shade action that sets NOT_READY or
 * DND on a cold process never sets `shiftActive`, so the watcher never arms and the
 * service runs without an in-memory shift until something sets it. That is a
 * near-theoretical case — the notification carrying those actions only exists while this
 * service is running — and it is a much better failure than stopping the service the
 * agent just touched.
 */
internal fun Flow<Boolean>.shiftEndedAfterBeingActive(): Flow<Boolean> =
    dropWhile { !it }.filter { !it }
