package me.kalfa.agentconsole.telephony.presence

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.Service
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.domain.model.AgentStatus

// Makes "I am on shift" a technical fact rather than a ViewModel-scoped claim that
// dies with the Activity — see docs/android-presence-and-call-ux.md §1 for the full
// design and the reasoning behind every decision below (cadence, specialUse FGS type,
// what survives what). Started/stopped by MainActivity observing
// AgentPresence.shiftActive; also self-stops if that flag flips false through any
// other path, so it never depends on the Activity being alive to notice.
class PresenceForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var observerJob: Job? = null
    private var shiftWatcherJob: Job? = null
    private lateinit var stateStore: PresenceStateStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DependencyContainer.attach(applicationContext)
        stateStore = PresenceStateStore(applicationContext)
        PresenceNotificationBuilder.ensureChannel(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() must be called promptly after onStartCommand — always do
        // it synchronously first, with a best-effort notification, before any branch
        // below that needs a suspend read (persisted-state resume) or write (a
        // notification-action status change) gets a chance to run.
        val bestEffort = DependencyContainer.agentPresence.currentStatus.value
        startForegroundCompat(PresenceNotificationBuilder.build(this, bestEffort))

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
        val persisted = stateStore.load()
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
                presence.currentStatus.collect { status ->
                    if (presence.shiftActive.value) {
                        updateNotification(status)
                        stateStore.save(status, true, currentVoxUsername())
                    }
                }
            }
        }

        if (shiftWatcherJob?.isActive != true) {
            shiftWatcherJob = scope.launch {
                presence.shiftActive.filter { !it }.collect { stopSelfCleanly() }
            }
        }

        if (heartbeatJob?.isActive != true) {
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (presence.shiftActive.value) {
                        // Re-send the CURRENT status — this is a freshness refresh,
                        // not a status change (docs §1, "What the heartbeat actually
                        // sends"). Reuses AgentPresence.setStatus deliberately rather
                        // than adding a parallel heartbeat-only write path.
                        presence.setStatus(presence.currentStatus.value)
                    }
                }
            }
        }
    }

    private fun updateNotification(status: AgentStatus) {
        val mgr = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        mgr?.notify(PresenceNotificationBuilder.NOTIFICATION_ID, PresenceNotificationBuilder.build(this, status))
    }

    private suspend fun currentVoxUsername(): String? =
        DependencyContainer.voxTokenStore?.load()?.voxUsername

    private fun stopSelfCleanly() {
        heartbeatJob?.cancel()
        observerJob?.cancel()
        scope.launch { stateStore.clear() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                PresenceNotificationBuilder.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(PresenceNotificationBuilder.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        // See docs/android-presence-and-call-ux.md §1 "Cadence: 30s, not the
        // browser's 60s" for the safety-margin arithmetic against the server's 90s
        // freshness gate (AGENT_STATUS_FRESHNESS_MS, beta/src/lib/console/presence.ts)
        // — do not "align" this back to 60s without re-reading that section.
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PresenceForegroundService::class.java))
        }

        fun updateStatus(context: Context, status: AgentStatus) {
            val intent = Intent(context, PresenceForegroundService::class.java)
                .setAction(PresenceNotificationBuilder.ACTION_SET_STATUS)
                .putExtra(PresenceNotificationBuilder.EXTRA_STATUS, status.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceForegroundService::class.java))
        }
    }
}
