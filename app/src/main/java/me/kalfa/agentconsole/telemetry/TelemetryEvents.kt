package me.kalfa.agentconsole.telemetry

/**
 * The event vocabulary, derived by reading the call path rather than by guessing
 * what it ought to contain.
 *
 * It exists to answer ONE question, and every name below earns its place against
 * that question: **when a call is routed to a phone that is idle in a pocket,
 * which step is the last one that happens?** Read top to bottom, the constants
 * are the steps in order, so a tail of the log can be compared against this file
 * directly and the first missing step is the answer.
 *
 * Ordering of the real path, with the file each step lives in:
 *
 *  1. FCM delivers a data message ....... VoxFirebaseMessagingService.onMessageReceived
 *  2. the process is (re)created ........ VoxFirebaseMessagingService.onCreate → DependencyContainer.attach
 *  3. a persisted identity is found ..... VoxTokenStore.load()  ← [FCM_NO_IDENTITY] ends the trace here
 *  4. the wake handler runs ............. VoxWakePushHandler.handle
 *  5. connect + login ................... VoxClientManager.ensureLoggedIn
 *  6. handlePushNotification ............ VoxClientManager.handleRawPushNotification
 *  7. re-register the push token ........ VoxClientManager.registerCurrentPushToken
 *  8. the SDK delivers the call ......... VICalls incoming-call listener → [VOX_INCOMING_CALL]
 *  9. an offer + notification ........... VoxIncomingCallCoordinator.handleIncomingCall
 * 10. the agent answers ................. VoxIncomingCallCoordinator.answer
 *
 * Step 8 is the one that has never been observed on this device, so
 * [FCM_WAKE_DONE] carries `incoming=` explicitly: it is emitted from the last
 * moment this app controls the process, and it states in one line whether the
 * SDK ever produced a call. That is the deliverable's headline reading.
 *
 * Nothing here may carry PII. Field values documented alongside each constant
 * are non-identifying by construction, and [scrubTelemetryValue] enforces it
 * again at emit time.
 *
 * ## READ THIS BEFORE CONCLUDING ANYTHING FROM AN EMPTY TRACE
 *
 * Silence has **three** readings and they have nothing in common. Treating them
 * as one is the single most likely way to misuse this channel, because the
 * obvious reading — "the app dropped the call" — is the least likely of the
 * three today.
 *
 * 1. **Nothing was routed.** An agent whose `agent_status` is `not_ready` is
 *    excluded from `ring_order` server-side, so no `callUser` is issued and no
 *    push is attempted. **The trace disambiguates this one by itself:**
 *    [PRESENCE_STATUS_SET] carries `s=`, so a log reading
 *    `presence.status_set s=not_ready` followed by silence IS this case, stated
 *    outright. Observed 2026-08-15: a call at 22:12 went straight to
 *    `no_agent` against a healthy 7-second heartbeat.
 * 2. **No call arrived.** The inbound circuit breaker refuses the caller
 *    pre-answer (`gate_refused_code_200`), kept tripped by a flood of fax
 *    retries — machines redialling a number that used to be a fax line, on a
 *    ~914s interval, which never give up and therefore defeat every limit built
 *    against a caller who does. Expires when tone-detection lands.
 * 3. **The app dropped it.** The case this channel was built for — and the only
 *    one where these events are the evidence.
 *
 * Readings 1 and 3 are separable from the device. **Readings 2 and 3 are not**:
 * both look like silence here, and only the platform side can say whether a
 * `callUser` was ever attempted. Pair an empty trace with Voximplant's own call
 * history before concluding anything — see [VOX_PUSH_REGISTER_OK] for the same
 * problem in its sharpest form, where the platform gives up 287ms after
 * `CallUser` without ever contacting the device.
 */
object TelemetryEvents {

    // ── process ───────────────────────────────────────────────────────────────
    /** DependencyContainer.attach ran for the first time in this process. `via=activity|fcm|other` */
    const val APP_ATTACH = "app.attach"

    /**
     * The last line a dying process writes, from the handler DependencyContainer.attach
     * installs. `err=` is the cause CHAIN (`A <- B <- C`), `at=` the first frame in our
     * own package, `thread=` the thread that died.
     *
     * Added because a crash previously appeared here as nothing at all: the trace simply
     * stopped mid-sequence and a new `app.attach` followed seconds later, which reads
     * identically to reading 3 above ("the app dropped it") while saying nothing about
     * why. This turns that silence into a sentence.
     */
    const val APP_CRASH = "app.crash"

    /**
     * The `console_me` read that tells the device which Voximplant user it is, failed.
     *
     * A FOURTH reading of silence, and one the doctrine above did not cover: with no
     * identity, PresenceActions returns before any `vox.*` event is emitted, so the
     * trace shows a READY tap and then nothing telephony-shaped at all. Until this
     * existed the failure was a `printStackTrace()` and therefore invisible off-device.
     */
    const val IDENTITY_LOAD_FAIL = "app.identity_load_fail"

    /**
     * What is running, and on what. Emitted once per process, immediately after
     * [APP_ATTACH].
     *
     * `app=v<name>(<code>)`, `os=Android<release>/api<sdk>`, `dev=<manufacturer>/<model>`,
     * `abi=<primary>`.
     *
     * Every question asked of this log so far has eventually needed one of these and
     * had to get it by asking the owner: which build is on the phone (three commits
     * shipped in one afternoon, and "the fix is in" is unanswerable without it), which
     * Android version (full-screen-intent, foreground-service-type and Doze rules all
     * changed by API level), and which OEM (Xiaomi/Huawei/Samsung battery managers
     * swallow pushes the platform reports as delivered).
     *
     * NON-IDENTIFYING BY CONSTRUCTION, which is the bar this file sets. Manufacturer and
     * model describe a device CLASS shared by millions; nothing here is an installation
     * id, an advertising id, a serial, or anything that survives a reinstall. Values are
     * shaped to carry a letter and stay short on purpose — `scrubTelemetryValue` redacts
     * anything that looks like a phone number (digits and punctuation only) or an opaque
     * token (40+ chars), and a version string of bare digits and dots would trip the
     * first of those.
     */
    const val APP_DEVICE = "app.device"

    /** MainActivity.onCreate. Absence of this alongside [FCM_SERVICE_CREATED] proves a headless wake. */
    const val APP_ACTIVITY_CREATE = "app.activity_create"

    // ── FCM / push wake ───────────────────────────────────────────────────────
    /** VoxFirebaseMessagingService.onCreate — the process exists because a push arrived. */
    const val FCM_SERVICE_CREATED = "fcm.service_created"

    /**
     * onMessageReceived entered. `vox=true|false` (is this a Voximplant call push),
     * `keys=<n>` (how many data keys — never their names or values: the Voximplant
     * push payload is opaque and may carry routing detail).
     */
    const val FCM_MESSAGE_RECEIVED = "fcm.message_received"

    /** onMessageReceived returned early: DependencyContainer had no VoxClientManager / VoxTokenStore. `what=` */
    const val FCM_NO_DEPENDENCY = "fcm.no_dependency"

    /**
     * onMessageReceived returned early because no Voximplant identity is persisted.
     * A real terminal state: this device never completed a login, so it was never
     * registered for push and the platform should not have had a token for it.
     */
    const val FCM_NO_IDENTITY = "fcm.no_identity"

    /** onNewToken fired. `len=<n>` only — the token itself is a credential and is never logged. */
    const val FCM_TOKEN_REFRESHED = "fcm.token_refreshed"

    /**
     * THE headline line. Emitted from the end of onMessageReceived, the last moment
     * this app is guaranteed to be alive on the wake path.
     * `ms=` total in-handler time, `timedout=true|false` (the 9s budget),
     * `incoming=true|false` — whether the SDK ever delivered a call before the
     * process handed control back.
     */
    const val FCM_WAKE_DONE = "fcm.wake_done"

    // ── wake handler (VoxWakePushHandler) ─────────────────────────────────────
    const val WAKE_START = "wake.start"

    /** The push was not a Voximplant call push, so the handler did nothing. */
    const val WAKE_NOT_VOX_PUSH = "wake.not_vox_push"

    const val WAKE_LOGIN_OK = "wake.login_ok"

    /** `err=` is an exception CLASS NAME or an SDK error enum — never a message that could quote data. */
    const val WAKE_LOGIN_FAIL = "wake.login_fail"

    const val WAKE_HANDLE_PUSH_OK = "wake.handle_push_ok"
    const val WAKE_HANDLE_PUSH_FAIL = "wake.handle_push_fail"
    const val WAKE_REGISTER_OK = "wake.register_ok"
    const val WAKE_REGISTER_FAIL = "wake.register_fail"

    // ── Voximplant SDK (VoxClientManager) ─────────────────────────────────────
    /** VICore.initialize / VICalls.initialize threw. The bug 81788b3 fixed; kept observable. */
    const val VOX_SDK_INIT_FAIL = "vox.sdk_init_fail"

    const val VOX_CONNECT_START = "vox.connect_start"
    const val VOX_CONNECT_OK = "vox.connect_ok"
    const val VOX_CONNECT_FAIL = "vox.connect_fail"

    /**
     * `plan=access|refresh|interactive|already` — which of the three login paths
     * was chosen, and the highest-value TIMING signal on the channel.
     *
     * The budget it is measured against is now a measurement rather than an
     * estimate. `analyst` timed the scenario's ring window across two sessions:
     * `Call.PushSent` → `HangupCall code=603` is **~14.84s** (session
     * `7665866994`: 10:52:04.486 → 10:52:19.326). `app/build.gradle.kts` still
     * records that as "unmeasured against the server's 15s RING_RETRY_WINDOW_MS";
     * it is measured now.
     *
     * Against that ~15s, `VoxFirebaseMessagingService` spends up to
     * `WAKE_PUSH_TIMEOUT_MS` = 9s on login alone, and the three plans have very
     * different costs: `access` is one round trip, `refresh` adds a second, and
     * `interactive` adds a round trip to beta.kalfa.me on top — reached precisely
     * when a device has been idle long enough for both tokens to lapse, i.e. the
     * pocket case. Whether `interactive` fits inside the window has never been
     * observed. **This field is what will answer that**, so it is worth reading
     * even on a wake that succeeds.
     */
    const val VOX_LOGIN_START = "vox.login_start"
    const val VOX_LOGIN_OK = "vox.login_ok"
    const val VOX_LOGIN_FAIL = "vox.login_fail"

    const val VOX_PUSH_REGISTER_START = "vox.push_register_start"

    /**
     * **NOT proof that the device is reachable — read this before concluding push
     * works.** `tok=<sha256 prefix>` `bundle=<id|null>`.
     *
     * The platform can accept a registration and still hold nothing it can send
     * to. That is the recorded bundle-id story: registration succeeded, the token
     * was filed under a bundle no certificate matched, and all 76 ring attempts
     * reported `push_results: []` while looking, from the device, exactly like a
     * registered device.
     *
     * **That state is unobservable from inside this process, by construction.**
     * Measured platform-side by `analyst` on session `7666179052`: `CallUser` at
     * T+0, `Call.Failed code=480 "User offline"` at T+96ms, `Call.PushSent
     * result={"push_results":[]}` at T+109ms, and the whole attempt abandoned by
     * T+287ms — **the device is never contacted at all.** No FCM message, no
     * callback, no throw. A trace on a device in this state shows `vox.login_ok`,
     * this event, and then nothing, which is byte-identical to a device that
     * simply had no calls. That is precisely the false negative this whole channel
     * exists to kill, and no device-side event can close it.
     *
     * So the two fields are a JOIN, not a detection. Given a platform log showing
     * `push_results: []` at time T, the device log can now state: *"I registered
     * token `a1b2c3d4` under bundle `null` at T−n."* Neither system can say that
     * alone. `tok` is a truncated SHA-256 rather than a prefix — a prefix of a
     * credential is a piece of the credential.
     */
    const val VOX_PUSH_REGISTER_OK = "vox.push_register_ok"

    /**
     * `stage=fcm_token|vox_register` — the two failure domains
     * VoxClientManager.registerCurrentPushToken already separates. Which one it is
     * decides whether the problem is Google Play services on the device or
     * Voximplant rejecting a token we did get, and those have nothing in common.
     */
    const val VOX_PUSH_REGISTER_FAIL = "vox.push_register_fail"

    /** Client session listener: `s=closed|reconnecting|reconnected`. */
    const val VOX_SESSION_STATE = "vox.session_state"

    /**
     * The SDK produced an incoming call. `hdrs=<n>` — the COUNT of SIP headers only:
     * SIP headers routinely carry caller identity, so neither keys nor values are
     * ever emitted.
     */
    const val VOX_INCOMING_CALL = "vox.incoming_call"

    /**
     * The SDK produced an incoming call and `onIncomingCall` was null, so it was
     * dropped on the floor. Was the live defect until DependencyContainer wired the
     * coordinator; if a construction-order regression ever restores it, this says so
     * in one line instead of costing another night.
     */
    const val VOX_INCOMING_NO_LISTENER = "vox.incoming_no_listener"

    // ── incoming call (VoxIncomingCallCoordinator) ────────────────────────────
    /**
     * An offer reached the coordinator. `id=` a truncated call id (an opaque
     * platform identifier, not PII), `named=true|false` and `numlen=<n>` — whether a
     * display name and number were present, never what they were.
     *
     * **Known blind spot, by construction rather than by omission.** The ring
     * phase deliberately starts no foreground service (see
     * `VoxIncomingCallCoordinator.handleIncomingCall`'s comment on why a
     * `microphone`-typed FGS cannot legally start from a push-woken background
     * process). So a low-memory kill between this event and [CALL_ANSWER] produces
     * NO event at all — the process is gone before anything can record its going.
     * That silence is not a defect in this channel and cannot be fixed from inside
     * the process; it is written down so nobody later reads it as one.
     */
    const val CALL_OFFER = "call.offer"

    /** A second offer replaced a still-pending one — the known gap in the coordinator. */
    const val CALL_OFFER_SUPERSEDED = "call.offer_superseded"

    const val CALL_NOTIFY_OK = "call.notify_ok"
    const val CALL_NOTIFY_FAIL = "call.notify_fail"

    /** `alert=`, `fsi=`, `locked_ring=` — RingCapability's read of whether this device can ring at all. */
    const val CALL_RING_CAPABILITY = "call.ring_capability"

    const val CALL_ANSWER = "call.answer"

    /** answer()/decline() was a no-op. `why=no_offer|stale` — a dead or superseded offer. */
    const val CALL_ACTION_IGNORED = "call.action_ignored"

    const val CALL_DECLINE = "call.decline"

    /** answer() declined instead, because RECORD_AUDIO is not granted. */
    const val CALL_NO_RECORD_AUDIO = "call.no_record_audio"

    const val CALL_FGS_START_OK = "call.fgs_start_ok"

    /** `err=` the exception class name. The `SecurityException` case AGENTS.md §B-2 is about. */
    const val CALL_FGS_REFUSED = "call.fgs_refused"

    /** `id=`, `s=RINGING|ACTIVE|HELD|DISCONNECTED` — the leg's own state machine. */
    const val CALL_STATE = "call.state"

    const val CALL_CLEANUP = "call.cleanup"

    /**
     * A call session opened on a push and closed WITHOUT the SDK ever delivering a
     * call. `ms=` how long it waited. Complements [FCM_WAKE_DONE] for the case where
     * the process outlives onMessageReceived (agent on shift, presence service up)
     * and the watchdog therefore gets to run.
     */
    const val CALL_NO_INCOMING_AFTER_PUSH = "call.no_incoming_after_push"

    // ── presence ──────────────────────────────────────────────────────────────
    /** `active=true|false` — POST /api/agents/shift. */
    const val PRESENCE_SHIFT = "presence.shift"

    /** `s=ready|not_ready|dnd` — POST /api/agents/status. */
    const val PRESENCE_STATUS_SET = "presence.status_set"

    /** The 30s heartbeat re-send. Its absence is what ages an agent out of the routing pool. */
    const val PRESENCE_HEARTBEAT_OK = "presence.heartbeat_ok"
    const val PRESENCE_HEARTBEAT_FAIL = "presence.heartbeat_fail"

    /** PresenceForegroundService start/stop. `s=start|stop|restart_sticky`. */
    const val PRESENCE_SERVICE = "presence.service"

    // ── auth ──────────────────────────────────────────────────────────────────
    /**
     * A Supabase access token was needed and was not available. `at=telemetry|…`.
     * On a push cold start the session loads asynchronously, so this is expected
     * early and diagnostic if it persists.
     */
    /**
     * A dial attempt failed, with the exception class that ended it.
     *
     * Added after a crash that was invisible from the server: dial-intent answered
     * 200, the app then touched the Voximplant SDK, its static initialiser threw,
     * and the process died on the main thread. Nothing in this event list covered
     * the dial path at all, so the only trace was `app.crash` — which named the line
     * but not what the agent had been doing.
     *
     * No phone number, no session id: the exception class and a clipped message.
     */
    const val DIAL_FAILED = "dial.failed"

    const val AUTH_JWT_MISSING = "auth.jwt_missing"

    // ── the channel describing itself ─────────────────────────────────────────
    /** `reason=` — a new trace opened. See TelemetrySession. */
    const val SESSION_OPEN = "tm.session_open"

    /** `reason=`, `ms=` — a trace closed and subsequent lines revert to the process session. */
    const val SESSION_CLOSE = "tm.session_close"

    /**
     * The in-memory upload queue overflowed and `n=` events were discarded before
     * they could be sent. Emitted so a `seq` gap in the server log has a stated
     * cause rather than looking like an unexplained hole.
     */
    const val UPLOAD_DROPPED = "tm.upload_dropped"
}
