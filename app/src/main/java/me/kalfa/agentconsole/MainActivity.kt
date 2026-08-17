package me.kalfa.agentconsole

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.collect
import me.kalfa.agentconsole.di.DependencyContainer
import me.kalfa.agentconsole.telemetry.Telemetry
import me.kalfa.agentconsole.telemetry.TelemetryEvents
import me.kalfa.agentconsole.telephony.EnsureCallAudioPermission
import me.kalfa.agentconsole.telephony.TelecomRegistration
import me.kalfa.agentconsole.telephony.presence.PresenceActions
import me.kalfa.agentconsole.telephony.presence.PresenceForegroundService
import me.kalfa.agentconsole.telephony.vox.IncomingCallNotificationBuilder
import me.kalfa.agentconsole.telephony.vox.VoxIncomingCallCoordinator
import me.kalfa.agentconsole.ui.*
import me.kalfa.agentconsole.ui.message.AppMessageHost
import me.kalfa.agentconsole.ui.message.AppSnackbarHost
import me.kalfa.agentconsole.ui.message.FailureContext
import me.kalfa.agentconsole.ui.message.FullScreenErrorState
import me.kalfa.agentconsole.ui.message.UiEffect
import me.kalfa.agentconsole.ui.message.toHebrewMessage
import me.kalfa.agentconsole.domain.error.RepositoryHealth
import me.kalfa.agentconsole.ui.screens.*
import me.kalfa.agentconsole.ui.theme.MyApplicationTheme
import me.kalfa.agentconsole.ui.viewmodel.ConsoleUiState
import me.kalfa.agentconsole.ui.viewmodel.ConsoleViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: ConsoleViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        me.kalfa.agentconsole.di.AppVisibility.isForeground.value = true
        // Re-read the ring-capability facts every time the app comes forward, because
        // the ONE moment they change is a moment this app is not running: the agent
        // leaves for system Settings, grants notifications or full-screen-intent, and
        // comes back.
        //
        // Until now nothing re-checked on that return. refreshAndReportRingCapability
        // had exactly three callers — PresenceForegroundService.onStartCommand, its 30s
        // heartbeat, and a READY tap — so an agent who fixed the permission and came
        // back kept staring at a banner describing a problem they had just solved, for
        // up to 30 seconds if they happened to be on shift, or indefinitely if they
        // were not. Reported live by the owner on 2026-08-17: "הבאנרים של הרשאות לא
        // מוסרים מיידית בעת מתן ההרשאות".
        //
        // The audio-permission banner (CALL_AUDIO_PERMISSION_MESSAGE_ID) already
        // recovers here for free — accompanist's own ON_RESUME check rewrites the
        // state that EnsureCallAudioPermission's LaunchedEffect is keyed on. Ring
        // capability is not a runtime permission and has no such observer, which is
        // why it, and only it, needed this.
        //
        // Cheap and synchronous by design: RingCapabilityState.refresh reads
        // NotificationManager flags, so there is no IO to move off the main thread and
        // no reason to defer it past the frame the agent is about to see.
        //
        // GUARDED, and the guard is not decoration. An Activity lifecycle callback that
        // throws takes the process down — there is no framework catch above onStart —
        // so a banner refresh, which is a diagnostic convenience, would be able to kill
        // the app on any device or OEM where a NotificationManager query behaves
        // differently than it does here. Shipped unguarded on 2026-08-17 and corrected
        // the same day after a crash was reported; whether or not this line caused that
        // particular crash, an unguarded call here was wrong on its own terms.
        //
        // Failing silently is right for THIS call specifically: the worst case is a
        // stale ring-capability banner, which the 30s heartbeat and the next READY tap
        // both still repair. Trading the whole app for that is not a trade.
        try {
            PresenceActions.refreshAndReportRingCapability(applicationContext)
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "ring-capability refresh on start failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    override fun onStop() { super.onStop(); me.kalfa.agentconsole.di.AppVisibility.isForeground.value = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Gives DependencyContainer's VoxTokenStore a Context. Also called from
        // VoxFirebaseMessagingService.onCreate for the cold-start-via-push case
        // (AGENTS.md "Push wake-up"); attach() is idempotent either order.
        DependencyContainer.attach(applicationContext, via = "activity")
        Telemetry.emit(TelemetryEvents.APP_ACTIVITY_CREATE)
        // Declares this app a self-managed calling app to the platform. Idempotent and
        // never throws — see TelecomRegistration's kdoc for why it must be in place
        // BEFORE the first Play upload rather than with the later Telecom phase: Play
        // revokes USE_FULL_SCREEN_INTENT from apps that aren't calling apps, and losing
        // it kills locked-screen ringing with no symptom except missed calls.
        TelecomRegistration.register(applicationContext)
        applyIncomingCallWindowFlagsIfNeeded(intent)
        answerFromNotificationIfRequested(intent)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                // The ring surface is read and drawn OUTSIDE AuthGate, and that
                // placement is the whole point.
                //
                // It used to sit inside AuthGate, below the authenticated content's
                // other state. But AuthGate (ui/screens/LoginScreen.kt) does not
                // render its content until the Supabase session resolves: while
                // sessionStatus is Initializing it shows a CircularProgressIndicator,
                // and on RefreshFailure — a session that exists but could not be
                // refreshed, i.e. a just-woken device with poor network — it shows the
                // LOGIN FORM. The path this surface exists for is a locked-device
                // full-screen intent after a push wake, which per AGENTS.md "Push
                // wake-up" starts from a KILLED process, so both of those states are
                // live possibilities at exactly the moment the phone rings. A
                // locked-screen FSI draws the target Activity's own content
                // (docs/android-presence-and-call-ux.md §3: "If the FSI target is
                // MainActivity showing its ordinary nav/dashboard content, a locked
                // phone that starts ringing shows the wrong thing... which is worse
                // than not wiring FSI at all") — a spinner or a login form is the same
                // failure wearing a different screen.
                //
                // An overlay in a Box rather than an `if/else` around AuthGate: the
                // else-branch would dispose AuthGate and, with it, the
                // rememberNavController() that today survives a call, turning every
                // answered call into a hard reset to the Dashboard. The docs already
                // call this "a top-level overlay"; this is that, literally.
                val incomingCallCoordinator = remember { DependencyContainer.incomingCallCoordinator }
                val noOffer = remember {
                    kotlinx.coroutines.flow.MutableStateFlow<VoxIncomingCallCoordinator.IncomingOffer?>(null)
                }
                val pendingOffer by (incomingCallCoordinator?.pendingOffer ?: noOffer).collectAsState()

                // Held while EITHER a call is ringing or one is live — not just while
                // an offer is pending, which is what this used to key on.
                //
                // That earlier condition had the right goal and the wrong moment. An
                // offer resolves in two ways, and one of them is the agent ANSWERING:
                // the coordinator clears _pendingOffer, this effect fired, and
                // setShowWhenLocked(false) took away the very permission the call
                // screen needed. On a phone with no lock code nothing showed, because
                // there was nothing to be behind. On a phone with a PIN the agent was
                // dropped behind the keyguard and had to unlock to see a call they had
                // already answered — reported 17.8: "יש קוד נעילה למכשיר אני חייב
                // לפתוח את המכשיר בכדי לראות את מסך השיחה".
                //
                // AOSP is explicit that the flag itself is enough for a secure
                // keyguard — setShowWhenLocked keeps the activity "in the resumed
                // state visible on-top of the lock screen", with no exemption for a
                // PIN (frameworks/base Activity.java, read 17.8). So this was never a
                // platform limit to work around; it was ours to stop causing.
                //
                // The original intent still holds and is still enforced: the flags
                // must not linger, or an ordinary app launch would bypass the lock
                // screen forever. They now clear one moment later — when the call is
                // actually over.
                val liveSession by DependencyContainer.callEngine.currentSession.collectAsState()
                LaunchedEffect(pendingOffer, liveSession) {
                    if (pendingOffer == null && liveSession == null) clearIncomingCallWindowFlags()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                // Declared HERE rather than beside the overlay below, because the
                // gesture that sets it now lives inside AuthGate (DashboardScreen's
                // greeting) while the overlay that reads it is drawn outside. Compose
                // lambdas capture their enclosing scope, so hoisting the state to the
                // common ancestor is all the wiring this needs — AdaptiveConsoleScaffold
                // takes `content` as a lambda, so nothing has to be threaded through it.
                var showDebugLive by rememberSaveable { mutableStateOf(false) }
                AuthGate {
                    val state by viewModel.uiState.collectAsState()
                    val navController = rememberNavController()

                    // One-time landing by permission: manage_voice -> manager events overview
                    var redirected by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(state.me?.canManageVoice) {
                        if (!redirected && state.me?.canManageVoice == true) {
                            redirected = true
                            navController.navigate(EventsRoute) {
                                popUpTo(DashboardRoute) { inclusive = true }
                            }
                        }
                    }

                    // Presence that survives — starts/stops PresenceForegroundService
                    // in lockstep with AgentPresence.shiftActive (via ConsoleUiState),
                    // since starting a Service needs a Context ConsoleViewModel
                    // deliberately doesn't have. See
                    // docs/android-presence-and-call-ux.md §1.
                    //
                    // The `shiftWasActive` latch is this file's copy of the defect
                    // PresenceForegroundService already fixed on its own side, and the
                    // reasoning in `shiftEndedAfterBeingActive`'s kdoc applies here
                    // verbatim: AgentPresence.shiftActive is a StateFlow initialised to
                    // false and never restored from storage, so the FIRST value this
                    // effect sees on any launch is the process-start default, not a
                    // shift ending — and the else-branch turned that into
                    // `PresenceForegroundService.stop()`.
                    //
                    // That is not harmless when the service is already running. The
                    // service and this Activity share a process, so a START_STICKY
                    // restart after a system kill has the service resuming from its
                    // persisted record — `resumeFromPersistedStateOrStop()` ->
                    // `PresenceActions.applyStatus()` -> `setStatus` (an awaitAuthToken
                    // of up to 3s, then an HTTPS POST) and only THEN `setShiftActive
                    // (true)`, which is what would set this flag. An agent opening the
                    // app during those seconds made this effect stop the service, whose
                    // onDestroy cancels the scope the resume was running in. The resume
                    // never reached setShiftActive, so shiftActive stayed false, this
                    // effect never re-fired, and presence stayed dead until the agent
                    // happened to tap "זמין" again.
                    //
                    // Withholding the stop costs nothing: the service already stops
                    // itself when a shift that really was active ends (its shiftWatcher
                    // job), and a service running with no on-shift record stops itself
                    // via resumeFromPersistedStateOrStop's fail-closed branch.
                    var shiftWasActive by remember { mutableStateOf(false) }
                    LaunchedEffect(state.shiftActive) {
                        if (state.shiftActive) {
                            shiftWasActive = true
                            PresenceForegroundService.start(applicationContext)
                        } else if (shiftWasActive) {
                            PresenceForegroundService.stop(applicationContext)
                        }
                    }

                    // Calls can now arrive (and need answering) before the agent ever
                    // visits the live-calls screen that used to be the only place
                    // this was requested — see docs §3, "RECORD_AUDIO /
                    // POST_NOTIFICATIONS must be requested earlier".
                    //
                    // THE ONLY call site, deliberately. It used to say a
                    // `rememberSaveable` guard made a second mount a harmless no-op;
                    // that guard no longer exists (it was replaced by the durable
                    // PermissionRequestLog, which survives process death — the whole
                    // point), and the live-calls screen carried a second
                    // EnsureCallAudioPermission() until this change. Two mounts meant
                    // two independent MultiplePermissionsState objects, each with its
                    // own ActivityResultLauncher and its own LaunchedEffect, both able
                    // to fire while the other's dialog was still pending — and Android
                    // resolves that by dropping one, silently. This composable sits
                    // inside AuthGate at the root, so it covers every authenticated
                    // screen including live-calls; nothing needs a second one, and a
                    // second one can only take the dialog away.
                    EnsureCallAudioPermission()

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        // Adaptive nav: bottom bar on compact, rail on expanded. Hidden entirely during a call.
                        AdaptiveConsoleScaffold(
                            navController = navController,
                            // Always. The connected-call surface is now a top-level
                            // overlay drawn OUTSIDE AuthGate (see the ActiveCallScreen
                            // block further down), and a full-size Material3 Surface
                            // already covers the nav and blocks touches through it — so
                            // there is nothing left for this flag to hide. It used to
                            // read `!(currentSession != null && BuildConfig.DEBUG)`,
                            // which hid the nav for a screen that could not render in
                            // release anyway.
                            showNavigation = true
                        ) {
                            val snackbarHostState = remember { SnackbarHostState() }
                            LaunchedEffect(snackbarHostState) {
                                viewModel.effects.collect { effect ->
                                    when (effect) {
                                        is UiEffect.ShowSnackbar -> {
                                            val result = snackbarHostState.showSnackbar(
                                                message = effect.message,
                                                actionLabel = effect.actionLabel,
                                                withDismissAction = true
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                effect.actionId?.let(viewModel::handleGlobalMessageAction)
                                            }
                                        }
                                    }
                                }
                            }
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                snackbarHost = { AppSnackbarHost(snackbarHostState) }
                            ) { innerPadding ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding)
                                ) {
                                    AppMessageHost(
                                        messages = state.globalMessages,
                                        onAction = viewModel::handleGlobalMessageAction,
                                        onDismiss = viewModel::dismissMessage
                                    )

                                    val contentModifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)

                                    // The DEBUG-gated InCallScreen branch that used to sit
                                    // here is GONE, and its removal is the fix this file
                                    // exists to carry.
                                    //
                                    // It read `if (session != null && BuildConfig.DEBUG)`.
                                    // That gate was written when a session could only be a
                                    // DEBUG mock, and it stayed in place after
                                    // CallEngine.attachIncomingSession made a REAL answered
                                    // call publish a real session
                                    // (docs/android-presence-and-call-ux.md §3). Measured
                                    // consequence, reported from the first live answered
                                    // call on 2026-08-14: the agent answered and the app
                                    // showed him THIS — the dashboard — with no timer, no
                                    // controls and no sign a call was in progress.
                                    //
                                    // The connected call is now drawn by ActiveCallScreen as
                                    // a top-level overlay outside AuthGate; see that block
                                    // below for why the placement, not this Column, is where
                                    // it has to live.
                                    ConsoleNavHost(
                                        navController,
                                        state,
                                        viewModel,
                                        contentModifier,
                                        onDiagnosticsUnlock = { showDebugLive = true },
                                    )
                                }
                            }
                        }
                    }
                }

                // Drawn over whatever AuthGate is showing — see the comment above the
                // coordinator for why it must not be inside it. IncomingCallScreen
                // (ui/screens/IncomingCallScreen.kt) is a full-size Surface and provides
                // its own RTL, so neither is repeated here.
                //
                // Nothing blocks touches explicitly, and that IS the considered choice.
                // Compose hit-tests a Box's children front to back and a child with no
                // pointer-input modifier is not a hit-test target at all, so taps could
                // otherwise fall through the opaque ring screen onto the dashboard's
                // status controls and the nav bar — a ringing agent changing their own
                // availability by hitting something they cannot see. Material3's Surface
                // already closes that: verified by notification-owner against
                // material3-android 1.4.0's own sources (the version compose-bom
                // 2026.06.01 actually resolves), where blocking touch propagation is
                // item 5 of Surface's documented responsibilities and is implemented as
                // a trailing `.pointerInput(Unit) {}` on its Box.
                //
                // An extra `clickable` here was tried and removed: even with
                // `indication = null` it adds onClick SEMANTICS across the whole
                // overlay, so TalkBack would offer a third, unlabelled, does-nothing
                // action beside "ענה" and "דחה" — on a locked-screen surface whose
                // entire design is two labelled buttons.
                // ── The connected call ────────────────────────────────────────────
                //
                // Declared BEFORE the ring overlay on purpose: in a Box the later child
                // draws on top, and a still-ringing second offer must be able to cover a
                // call already in progress rather than hide behind it.
                //
                // OUTSIDE AuthGate, for the reason spelled out above the coordinator: an
                // agent who answered from the notification on a push-woken process and
                // then opens the app can land on AuthGate's spinner (session
                // Initializing) or its LOGIN FORM (RefreshFailure) — with a live call in
                // their ear and no way to hang up from the screen. Inside AuthGate this
                // surface would be missing at exactly the moment it is needed most.
                //
                // Its own collectAsState rather than hoisting AuthGate's `state`: hoisting
                // would mean reading uiState above AuthGate and threading it down, which
                // changes the recomposition scope of every authenticated screen. A second
                // collector on the same StateFlow is cheap and local.
                val callState by viewModel.uiState.collectAsState()
                val callVisibility = activeCallVisibility(
                    hasSession = callState.currentSession != null,
                    state = callState.currentSessionState,
                )
                if (callVisibility != ActiveCallVisibility.HIDDEN) {
                    // Back does nothing while a call is up. There is no way back TO this
                    // screen once it is dismissed — CallForegroundService's notification
                    // carries a "נתק" action but no content intent — so letting back pop
                    // the nav stack underneath would strand an agent mid-call with the
                    // hangup button gone and no route back to it. Home still backgrounds
                    // the app normally, and returning re-enters this overlay because the
                    // session lives in the CallEngine singleton, not in screen state.
                    BackHandler { }

                    // Registered only while the call surface is on screen, and torn down
                    // with it. AudioDeviceManager is a process-global SDK object, so an
                    // unpaired listener would outlive every call.
                    val audioController = remember { me.kalfa.agentconsole.telephony.vox.VoxAudioController() }
                    val audioRoute by audioController.route.collectAsState()
                    DisposableEffect(audioController) {
                        val stopObserving = audioController.observe()
                        onDispose { stopObserving() }
                    }

                    ActiveCallScreen(
                        customerName = callState.currentSession?.customerName.orEmpty(),
                        customerPhone = callState.currentSession?.customerPhone.orEmpty(),
                        visibility = callVisibility,
                        callState = callState.currentSessionState,
                        isMuted = callState.currentSessionMuted,
                        isHeld = callState.currentSessionHeld,
                        isReconnecting = callState.currentSessionReconnecting,
                        durationSec = callState.currentSessionDuration,
                        audioRoute = audioRoute,
                        onToggleMute = { viewModel.toggleMute() },
                        onToggleHold = { viewModel.toggleHold() },
                        onSelectAudioDevice = { audioController.selectRoute(it) },
                        onHangup = { viewModel.hangupDirectly() },
                        // The three server-side handoffs need a console_calls id, and
                        // only a call that arrived carrying the X-Kalfa-Console-Call-Id
                        // header has one — see CallSession.consoleCallId. Passing the
                        // presence of that id rather than the id itself keeps the
                        // screen free of any notion of what it addresses.
                        handoffAvailable = !callState.currentSession?.consoleCallId.isNullOrBlank(),
                        transferTargets = callState.transferTargets,
                        transferTargetsLoading = callState.transferTargetsLoading,
                        transferTargetsFailed = callState.transferTargetsFailed,
                        consultRequested = callState.consultRequested,
                        conferenceRequested = callState.conferenceRequested,
                        onSendDtmf = { viewModel.sendDtmf(it) },
                        onLoadTransferTargets = { viewModel.loadTransferTargets() },
                        onTransfer = { viewModel.transferTo(it) },
                        onConsult = { viewModel.consultWith(it) },
                        onConference = { viewModel.conferenceWith(it) },
                        onConsultPhone = { viewModel.consultWithPhone(it) },
                        onConferencePhone = { viewModel.conferenceWithPhone(it) },
                        onCancelConsult = { viewModel.cancelConsult() },
                        onCompleteConsult = { viewModel.completeConsult() },
                        onRemoveFromConference = { viewModel.removeFromConference() },
                    )
                }

                // The after-hours question. A dialog rather than a snackbar because it
                // asks something — the agent has to answer before anything happens,
                // and a snackbar that disappears is not an answer.
                // Collected HERE rather than reused from inside AuthGate: this dialog
                // is drawn in the same top-level overlay as the call screens, outside
                // AuthGate entirely, so it cannot see that scope's `state`.
                val overlayState by viewModel.uiState.collectAsState()
                overlayState.outsideHoursPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissOutsideHoursPrompt() },
                        title = { Text(text = "מחוץ לשעות החיוג") },
                        text = {
                            Text(
                                text = buildString {
                                    append("השעה כעת מחוץ לשעות החיוג הרגילות (08:00–19:00, ובשישי עד 13:00)")
                                    if (prompt.who.isNotBlank()) {
                                        append(".\n\nלחייג בכל זאת אל ")
                                        append(prompt.who)
                                        append("?")
                                    } else {
                                        append(".\n\nלחייג בכל זאת?")
                                    }
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmOutsideHoursDial() }) {
                                Text(text = "חייג בכל זאת")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissOutsideHoursPrompt() }) {
                                Text(text = "ביטול")
                            }
                        },
                    )
                }

                if (pendingOffer != null) {
                    IncomingCallScreen(
                        displayName = pendingOffer?.displayName.orEmpty(),
                        displayNumber = pendingOffer?.displayNumber.orEmpty(),
                        onAnswer = { pendingOffer?.let { incomingCallCoordinator?.answer(it.callId) } },
                        onDecline = { pendingOffer?.let { incomingCallCoordinator?.decline(it.callId) } }
                    )
                }

                // "אבחון חי" — a top-level overlay, NOT a navigation destination.
                //
                // An overlay in this Box rather than a fifth entry in
                // consoleDestinations, for three reasons. It is a diagnostic, not a
                // product surface. As a child of this Box it costs ZERO layout —
                // placed inside the content Column it added 28dp of dead space to
                // every authenticated screen, which is exactly the unrelated visual
                // change AGENTS.md asks not to make. And it works OUTSIDE AuthGate,
                // which matters more than it looks: AuthGate renders a spinner while
                // the Supabase session is Initializing and a LOGIN FORM on
                // RefreshFailure, and a just-woken device with poor network is in
                // one of those states at precisely the moment someone wants to know
                // what the phone just did.
                //
                // It must work in RELEASE — CI stopped publishing a debug APK in
                // b5a11f4, so a BuildConfig.DEBUG gate would put it on the one
                // variant the owner does not install.
                //
                // HOW IT OPENS, changed 2026-08-17: ten taps on the agent's own name
                // in DashboardScreen. It used to be a long press on an invisible 28dp
                // Box pinned to this Box's TopStart corner — and that failed the only
                // test that matters: the owner could not find it. Worse, the corner it
                // sat in was not knowable from the code, because this Box is OUTSIDE
                // the RTL CompositionLocalProvider (that one lives inside AuthGate), so
                // "start" resolved from the device configuration rather than from
                // anything written here. A visible target with a documented gesture
                // replaces a hidden one whose location had to be guessed.
                if (showDebugLive) {
                    BackHandler { showDebugLive = false }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        DebugLiveScreen(onClose = { showDebugLive = false })
                    }
                }
                }
            }
        }
    }

    // Delivered when a full-screen-intent-launched call (docs §3) targets this
    // Activity while it is already running (singleTask — see AndroidManifest.xml) —
    // Android routes it here instead of a fresh onCreate.
    /**
     * Answers the call when this activity was launched by the notification's Answer
     * action.
     *
     * The answer itself still goes through the same coordinator call the in-app button
     * uses, so there is one implementation of "answer" and not two. What this adds is
     * only that the UI is now on screen when it happens — see
     * IncomingCallNotificationBuilder.answerPendingIntent for why a receiver could not
     * do that.
     *
     * Guarded: an Activity lifecycle callback that throws takes the process down, and
     * this one runs at the exact moment the agent is trying to take a call.
     */
    private fun answerFromNotificationIfRequested(intent: Intent?) {
        if (intent?.action != IncomingCallNotificationBuilder.ACTION_ANSWER) return
        val callId = intent.getStringExtra(IncomingCallNotificationBuilder.EXTRA_CALL_ID) ?: return
        try {
            DependencyContainer.incomingCallCoordinator?.answer(callId)
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "answer from notification failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        answerFromNotificationIfRequested(intent)
        setIntent(intent)
        applyIncomingCallWindowFlagsIfNeeded(intent)
    }

    // Bypasses the lock screen ONLY for the locked-device incoming-call
    // full-screen-intent launch (docs/android-presence-and-call-ux.md §3) — never for
    // an ordinary app open. setShowWhenLocked/setTurnScreenOn are API 27+; 24-26 falls
    // back to the equivalent WindowManager flags (spec's version-gating table).
    private fun applyIncomingCallWindowFlagsIfNeeded(intent: Intent?) {
        // ACTION_ANSWER joins the list: answering from the notification on a LOCKED
        // phone launches this activity, and without these flags it would sit behind
        // the keyguard — a connected call the agent can hear but not see or hang up.
        val action = intent?.action
        if (action != IncomingCallNotificationBuilder.ACTION_INCOMING_CALL_UI &&
            action != IncomingCallNotificationBuilder.ACTION_ANSWER
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun clearIncomingCallWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }
}

@Composable
private fun AdaptiveConsoleScaffold(
    navController: NavHostController,
    showNavigation: Boolean,
    content: @Composable () -> Unit
) {
    if (!showNavigation) {
        content()
        return
    }

    val backStack by navController.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val colorScheme = MaterialTheme.colorScheme

    val navigationColors = NavigationSuiteDefaults.colors(
        navigationBarContainerColor = colorScheme.surface,
        navigationBarContentColor = colorScheme.onSurfaceVariant,
        navigationRailContainerColor = colorScheme.surface,
        navigationRailContentColor = colorScheme.onSurfaceVariant,
        navigationDrawerContainerColor = colorScheme.surface,
        navigationDrawerContentColor = colorScheme.onSurfaceVariant
    )

    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = colorScheme.primary,
            selectedTextColor = colorScheme.primary,
            indicatorColor = colorScheme.primaryContainer,
            unselectedIconColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            unselectedTextColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = colorScheme.primary,
            selectedTextColor = colorScheme.primary,
            indicatorColor = colorScheme.primaryContainer,
            unselectedIconColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            unselectedTextColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        )
    )

    fun navigateTop(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteColors = navigationColors,
        navigationSuiteItems = {
            consoleDestinations.forEach { d: ConsoleDestination ->
                val selected = dest?.hierarchy?.any { h ->
                    d.routeClasses.any { rc -> h.hasRoute(rc) }
                } == true
                item(
                    selected = selected,
                    onClick = { navigateTop(d.route) },
                    alwaysShowLabel = false,
                    colors = itemColors,
                    icon = {
                        Icon(
                            imageVector = if (selected) d.selectedIcon else d.unselectedIcon,
                            contentDescription = d.label,
                            modifier = Modifier.size(if (selected) 25.dp else 22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = d.label,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                )
            }
        }
    ) {
        content()
    }
}

@Composable
private fun ConsoleNavHost(
    navController: NavHostController,
    state: ConsoleUiState,
    viewModel: ConsoleViewModel,
    contentModifier: Modifier,
    // Threaded rather than read from a global: the gesture lives on the dashboard,
    // the overlay it opens is drawn outside AuthGate, and an explicit parameter is
    // what makes that jump greppable from either end.
    onDiagnosticsUnlock: () -> Unit = {},
) {
    val filterId = state.selectedEventFilter
    val filteredLive = if (filterId == null) state.liveCalls else state.liveCalls.filter { it.eventId == filterId }
    val filteredHistory = if (filterId == null) state.callHistory else state.callHistory.filter { it.eventId == filterId }
    val filteredRsvp = if (filterId == null) state.rsvpResults else state.rsvpResults.filter { it.eventId == filterId }
    val eventOptions = state.events.map { it.id to it.name }
    val callBlockingFailure = (state.callHealth as? RepositoryHealth.Stale)
        ?.takeUnless { it.hasCachedData }
        ?.reason
    val campaignBlockingFailure = (state.campaignHealth as? RepositoryHealth.Stale)
        ?.takeUnless { it.hasCachedData }
        ?.reason
    val rsvpBlockingFailure = (state.rsvpHealth as? RepositoryHealth.Stale)
        ?.takeUnless { it.hasCachedData }
        ?.reason
    val dashboardBlockingFailure =
        callBlockingFailure ?: campaignBlockingFailure ?: rsvpBlockingFailure

    NavHost(
        navController = navController,
        startDestination = DashboardRoute
    ) {
        composable<DashboardRoute> {
            if (dashboardBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את לוח הבקרה",
                    message = dashboardBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = {
                        viewModel.handleGlobalMessageAction("retry_calls")
                        viewModel.handleGlobalMessageAction("retry_campaigns")
                        viewModel.handleGlobalMessageAction("retry_rsvp")
                    },
                    modifier = contentModifier
                )
            } else {
                DashboardScreen(
                    agentName = state.agentName,
                    agentEmail = state.agentEmail,
                    onLogout = { viewModel.logout() },
                    agentStatus = state.agentStatus,
                    activeAiCalls = state.activeAiCallsCount,
                    queueDepth = state.queueDepth,
                    rsvpResults = state.rsvpResults,
                    onStatusChange = { viewModel.setAgentStatus(it) },
                    onDiagnosticsUnlock = onDiagnosticsUnlock,
                    pendingCallbacks = state.pendingCallbacks,
                    callbacksLoading = state.callbacksLoading,
                    callbacksFailed = state.callbacksFailed,
                    onRefreshCallbacks = { viewModel.loadPendingCallbacks() },
                    onReturnCallback = { id, who -> viewModel.returnCallback(id, who) },
                    modifier = contentModifier
                )
            }
        }
        composable<LiveCallsRoute> {
            if (callBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את השיחות",
                    message = callBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = { viewModel.handleGlobalMessageAction("retry_calls") },
                    modifier = contentModifier
                )
            } else {
                // Permissions are NOT requested here. The root call site inside
                // AuthGate already covers this screen, and mounting a second
                // EnsureCallAudioPermission() gave this route its own competing
                // permission launcher — see that comment for why that suppresses the
                // dialog instead of adding one.
                LiveCallsScreen(
                    eventOptions = eventOptions,
                    selectedEventId = filterId,
                    onSelectEvent = { viewModel.setEventFilter(it) },
                    liveTranscripts = state.liveTranscripts,
                    onWhisper = { id, text -> viewModel.whisperToAi(id, text) },
                    onMuteAi = { viewModel.muteAiOnce(it) },
                    onCloseAi = { viewModel.closeAiAgent(it) },
                    onEndCall = { viewModel.endCall(it) },
                    liveCalls = filteredLive,
                    onMonitor = { viewModel.monitorCall(it) },
                    onTakeover = { viewModel.takeoverCall(it) },
                    modifier = contentModifier
                )
            }
        }
        composable<EventsRoute> {
            if (dashboardBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את האירועים",
                    message = dashboardBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = {
                        viewModel.handleGlobalMessageAction("retry_calls")
                        viewModel.handleGlobalMessageAction("retry_campaigns")
                        viewModel.handleGlobalMessageAction("retry_rsvp")
                    },
                    modifier = contentModifier
                )
            } else {
                EventsScreen(
                    summaries = state.eventSummaries,
                    onEventClick = { navController.navigate(EventDetailRoute(it)) },
                    modifier = contentModifier
                )
            }
        }
        composable<EventDetailRoute> { entry ->
            val route = entry.toRoute<EventDetailRoute>()
            val evId = route.eventId
            val eventGuests by remember(evId) { viewModel.eventGuests(evId) }.collectAsState()
            if (dashboardBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את האירוע",
                    message = dashboardBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = {
                        viewModel.handleGlobalMessageAction("retry_calls")
                        viewModel.handleGlobalMessageAction("retry_campaigns")
                        viewModel.handleGlobalMessageAction("retry_rsvp")
                    },
                    modifier = contentModifier
                )
            } else EventDetailScreen(
                summary = state.eventSummaries.firstOrNull { it.event.id == evId },
                liveCalls = state.liveCalls.filter { it.eventId == evId },
                callHistory = state.callHistory.filter { it.eventId == evId },
                rsvpResults = state.rsvpResults.filter { it.eventId == evId },
                campaigns = state.campaigns.filter { it.eventId == evId },
                guests = eventGuests,
                guestCallFailures = state.guestCallFailures,
                guestDispatchStatuses = state.guestDispatchStatuses,
                campaignFailures = state.campaignFailures,
                liveTranscripts = state.liveTranscripts,
                canManageVoice = state.me?.canManageVoice ?: false,
                onBack = { navController.popBackStack() },
                onMonitor = { viewModel.monitorCall(it) },
                onTakeover = { viewModel.takeoverCall(it) },
                onWhisper = { id, text -> viewModel.whisperToAi(id, text) },
                onMuteAi = { viewModel.muteAiOnce(it) },
                onCloseAi = { viewModel.closeAiAgent(it) },
                onToggleCampaign = { viewModel.toggleCampaign(it) },
                onEnqueueCall = { guestId -> viewModel.enqueueOutboundCall(evId, guestId) },
                onCallClick = { call ->
                    viewModel.selectCall(call)
                    navController.navigate(CallDetailRoute(call.id))
                },
                modifier = contentModifier
            )
        }
        composable<HistoryRoute> {
            val historyBlockingFailure = callBlockingFailure ?: rsvpBlockingFailure
            if (historyBlockingFailure != null) {
                FullScreenErrorState(
                    title = "לא ניתן לטעון את היסטוריית השיחות",
                    message = historyBlockingFailure.toHebrewMessage(FailureContext.GENERAL),
                    actionLabel = "נסה שוב",
                    onAction = {
                        viewModel.handleGlobalMessageAction("retry_calls")
                        viewModel.handleGlobalMessageAction("retry_rsvp")
                    },
                    modifier = contentModifier
                )
            } else {
                HistoryScreen(
                    // No event picker and no RSVP list here any more — see the
                    // HistoryScreen kdoc. The one that used to sit above this list
                    // never filtered it (this call site passed state.consoleHistory
                    // through unfiltered while filtering only the RSVP tab beside
                    // it), and the axis was wrong regardless: 28 of 1,241 inbound
                    // calls in a week carry an event.
                    filter = state.consoleHistoryFilter,
                    onFilterChange = { viewModel.setConsoleHistoryFilter(it) },
                    onDialFromHistory = { record ->
                        val eventId = record.eventId
                        val contactId = record.contactId
                        // Both or neither — ConsoleCallRecord.dialable already gates
                        // the button on this, so reaching here without them would be
                        // a bug rather than a state to handle silently.
                        if (eventId != null && contactId != null) {
                            // The name goes with it so the after-hours dialog can say
                            // WHO it is about to ring — "לחייג בכל זאת?" with no name
                            // asks the agent to confirm something they cannot see.
                            viewModel.dialContact(
                                eventId,
                                contactId,
                                who = record.name ?: record.phone ?: "",
                            )
                        }
                    },
                    onRefreshHistory = { viewModel.loadConsoleHistory() },
                    callHistory = state.consoleHistory,
                    loading = state.consoleHistoryLoading,
                    failed = state.consoleHistoryFailed,
                    modifier = contentModifier
                )
            }
        }
        composable<CallDetailRoute> { entry ->
            val route = entry.toRoute<CallDetailRoute>()
            val call = (state.callHistory + state.liveCalls).firstOrNull { it.id == route.callId }
            if (call == null) {
                // Call vanished from state (e.g. process death restore) - go back gracefully
                LaunchedEffect(route.callId) { navController.popBackStack() }
            } else {
                CallDetailScreen(
                    call = call,
                    analysisState = state.analysisState,
                    onBack = { navController.popBackStack() },
                    onRetryAnalysis = { viewModel.selectCall(call) },
                    modifier = contentModifier
                )
            }
        }
    }
}
