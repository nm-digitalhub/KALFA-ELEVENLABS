# Android presence & incoming-call UX — spec

Status: **implemented 2026-08-14.** Every claim below is tagged **[verified]** (checked
against the live AAR/Maven artifact, live Android docs, or a file:line in this repo) or
**[inference]** (reasoning from verified primitives, no device to confirm against).
Read `AGENTS.md` "Known state" and "Push wake-up" first — this doc assumes that context
and does not repeat it.

## Scope

Two problems, kept deliberately separate because they have different failure modes and
different Android mechanisms:

1. **Presence.** `AgentPresence.setStatus` posts once and never again
   (`data/SupabaseImplementations.kt:738-763`). The server's routing gate needs
   `agent_status.updated_at` inside 90s (`AGENT_STATUS_FRESHNESS_MS`,
   `beta/src/lib/console/presence.ts`). No timer anywhere resends it, so an agent is
   routable for at most ~90s after tapping "זמין". Fix: a foreground service that
   re-sends the status on a cadence that survives the screen turning off.
2. **Incoming call.** `VoxClientManager.onIncomingCall` is declared and forwarded to by
   the SDK's `IncomingCallListener`, but nothing in this repo ever assigns it
   (`grep onIncomingCall` — the only assignment is the SDK-facing one *inside*
   `VoxClientManager` itself, `VoxClientManager.kt:87-95`). A push-woken app logs in and
   registers for push but a delivered call has nowhere to go. Fix: wire it to a
   notification-driven answer/decline surface, through `CallEngine`/`CallSession`.

Both are additive: no change to `beta`, no change to the DTMF `OutCall` rule, no change
to the `RSVPAgent` bridge scenario. **`CallEngine.monitorCall`/`.takeoverCall`/
`.startOutboundCall` still throw `UnsupportedOperationException` on purpose** — that
phase (full Voximplant SDK wiring for agent-initiated/monitor/takeover legs) is
unchanged and still open. This change gives the *inbound-to-human* path (retry-wave
`callUser` → FCM push → answered call) a place to land; it does not touch the other
three call-initiation paths.

## Decision: `android.telecom` adoption — recommended, but not implemented this change

The brief asked which is currently recommended for a self-managed VoIP app: framework
`ConnectionService` or the Jetpack `androidx.core:core-telecom` `CallsManager` API.

**[verified, from the live docs and the live artifact, not memory]:**
- `developer.android.com/guide/topics/connectivity/telecom/voip-app` states plainly:
  *"Use the Telecom Jetpack library to offer the best video and audio experiences to
  your users... The new Jetpack library adds support for call streaming and transfer,
  Android Auto and Wear OS integration, backward compatibility."* Google's own current
  guidance is the Jetpack library, not raw `ConnectionService`.
- `core-telecom` is stable at **1.0.1** (Maven metadata,
  `dl.google.com/android/maven2/androidx/core/core-telecom/maven-metadata.xml`;
  1.1.0-alpha06 is the newest prerelease, so 1.0.1 is the correct pin, not a stale one).
- **minSdk 23** — read directly out of the shipped AAR's own manifest
  (`core-telecom-1.0.1.aar!/AndroidManifest.xml`: `<uses-sdk android:minSdkVersion="23"/>`),
  not inferred from the general "AndroidX default is 23" policy text. This app's
  `minSdk 24` is comfortably above it — minSdk is not a blocker.
- The AAR **declares its own internal service**,
  `androidx.core.telecom.internal.JetpackConnectionService`, wired via manifest merger
  with `BIND_TELECOM_CONNECTION_SERVICE`. Adopting `core-telecom` therefore does **not**
  mean writing `ConsoleConnectionService` by hand — the library supplies the
  `ConnectionService` internally for API ≤ 33 and uses the platform's native
  foreground-service-type path on API 34+. This directly answers the "do not default to
  the old framework `ConnectionService`" steer: there would be nothing to hand-write.
- It still declares `MANAGE_OWN_CALLS`, `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS` in
  its own manifest, and `CallsManager.addCall(...)` requires posting a notification
  within 5 seconds and answering the platform's `CallControlScope` callbacks within a
  5-second deadline per call
  (`developer.android.com/guide/topics/connectivity/telecom/voip-app/telecom`).

**Recommendation: adopt `core-telecom` when this app takes on self-managed Telecom** —
not raw `ConnectionService`. **Not implemented in this change**, for reasons tied
directly to what is already decided in `AGENTS.md` hard rule 4 and the phase table:

- Telecom adoption is already scoped as its own deferred phase in this repo
  (`ConsoleConnectionService` commented out in the manifest with a `TODO(voximplant-phase)`;
  `MANAGE_OWN_CALLS`/`FOREGROUND_SERVICE_PHONE_CALL` deliberately undeclared — "declaring
  them with nothing behind them is a Play-review risk, not a shortcut"). Adopting
  `core-telecom` means declaring `MANAGE_OWN_CALLS` for real, which is exactly the
  bundled-permission-with-working-backing discipline that section already commits to —
  it belongs in the same change as the rest of that phase, not bolted onto presence work.
- The 5-second `CallControlScope` callback deadline and the `phoneCall` FGS type's
  Android 14+ requirement are real correctness risks that need a physical device to
  verify (see "What could not be verified"). This change has none.
- `CallForegroundService` already exists as a `microphone`-typed FGS
  (`telephony/CallForegroundService.kt`) and does not need Telecom to keep an answered
  leg's process alive — see "Incoming call" below. The incoming-call surface required by
  this task (notification, FSI, answer/decline, working two-way audio) is fully
  deliverable without Telecom.

Net effect: this change answers the "which one" question with evidence and leaves a
clean adoption point (`attachIncomingSession`/`clearAttachedSession` on `CallEngine`,
below) for whichever change implements Telecom next.

## Part 1 — presence foreground service

### Foreground service type: `specialUse`, not `phoneCall`, not `dataSync`

**[verified, `developer.android.com/develop/background-work/services/fg-service-types`]**
The presence heartbeat has no audio, no location, no data transfer in the "bulk
sync/backup" sense `dataSync` is meant for (and `dataSync`-typed FGS carries execution
time limits unsuited to an all-shift service). `phoneCall` requires exactly the Telecom
backing this change deliberately does not add (previous section). `specialUse` is
Android's designated escape hatch for "a real use case with no closer-fitting type," and
is what this service declares:

```xml
<service
    android:name=".telephony.presence.PresenceForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Keeps a call-center agent's on-shift presence heartbeat alive so the server can route inbound and AI-escalated calls to this device while the app is backgrounded" />
</service>
```

**Owner action required, not done by this change:** Google Play requires a
`specialUse`-typed FGS to be justified in the Play Console's **Policy → App content**
review before a build using it can be distributed
(`developer.android.com/about/versions/14/changes/fgs-types-required`). This app is not
on Play yet (`AGENTS.md` "Build & CI" — no upload keystore provisioned), so this is not
a blocker today, but it is required before any Play release, internal testing track
included, once `specialUse` code ships.

### Cadence: 30s, not the browser's 60s — deliberate, not an oversight

Server gate is 90s. One missed beat at a 30s cadence is a 60s gap — still under the
gate with margin; two consecutive missed beats (90s gap) sits exactly on the boundary.
The browser console's 60s cadence is a *foreground tab* on a *wired/Wi-Fi-typical*
connection; a backgrounded phone on cellular is a strictly less reliable delivery
environment, so this deliberately runs tighter than the browser, not the same. If a
future reader is tempted to "align" it back to 60s, don't — the safety math above is why
it isn't.
`PresenceForegroundService.HEARTBEAT_INTERVAL_MS = 30_000L`.

### What the heartbeat actually sends: the existing dual-write `setStatus`, on purpose

`AgentPresence.setStatus` does two writes per call — `POST /api/agents/status` **and** a
direct `postgrest` upsert into `agent_status` (`SupabaseImplementations.kt:738-763`).
The heartbeat re-invokes this same method (`setStatus(currentStatus.value)`) every 30s
rather than adding a POST-only heartbeat path, which doubles that write rate for the
life of a shift. This is a deliberate choice, not an oversight: the two writes go
through different layers (server route vs. RLS-scoped client upsert) and nothing in this
repo establishes which one the routing gate actually reads, so reusing the existing,
already-correct dual-write is the conservative option — a single new code path is one
fewer thing to get subtly wrong. If the extra write is ever shown to be meaningful load,
a POST-only heartbeat variant is a small, separate, measurable change — not bundled here.

### Lifecycle: what starts it, what stops it, what survives what

- **Starts** when `AgentPresence.shiftActive` (new `StateFlow<Boolean>`, mirrors the
  `dispatchStatuses`-style default-getter pattern already used on `CallEngine`) becomes
  `true` — i.e. the first time the agent taps "זמין" in a session, since
  `ConsoleViewModel.setAgentStatus(READY)` already calls `setShiftActive(true)`.
  Watched from `MainActivity` via `LaunchedEffect(state.shiftActive)`, because starting
  a `Service` needs a `Context` and `ConsoleViewModel` deliberately has none (it is a
  plain `ViewModel`, not `AndroidViewModel` — not changing that base class for this).
- **Stops** when `shiftActive` becomes `false` — today that is only explicit
  `ConsoleViewModel.logout()`. DND/NOT_READY do **not** stop it (existing comment on
  `setAgentStatus`: "a short break mid-shift should not drop push-wake coverage for the
  rest of the day") — the service keeps heartbeating through breaks, matching what
  `console_agent_shift`/the retry-wave audience already assumes.
- **Network loss:** each tick's `setStatus` call already catches and swallows its own
  exceptions (existing code); the loop does not back off or stop — it just tries again
  in `HEARTBEAT_INTERVAL_MS`. A prolonged outage self-heals the moment connectivity
  returns, and the server-side 90s gate is exactly the mechanism that should degrade the
  agent's routability in the meantime — this service does not try to hide that from the
  server, by design (see "Do not defeat the 90-second gate" below).
- **Doze:** a running foreground service is exempt from Doze's CPU/network deferral for
  its own process **[inference — standard documented Android FGS behavior, not
  device-verified here]**. It is *not* exempt from OEM battery-management killers
  (Xiaomi/Huawei/Samsung aggressive modes, which kill processes FGS status does not
  protect against on some skins) — **[unverifiable without the specific device/OEM
  build; flagged, not solved]**. Requesting
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` would help but is a Play-policy-sensitive
  permission requiring its own justification and is **left as an owner decision**, not
  added here — see "Owner actions."
- **Force-stop:** Android kills the process and does not restart force-stopped
  components until the user manually reopens the app — this is correct, desired
  behavior (`agent_status.updated_at` ages past 90s on its own and the agent correctly
  stops being routed). Nothing in this change works around it.
- **System kill under memory pressure (not force-stop):** the service returns
  `START_STICKY`. On a system-initiated restart (`onStartCommand` with a `null` intent),
  it reads a small persisted record — `PresenceStateStore` (DataStore Preferences,
  same pattern as `VoxTokenStore`) holding the last known `AgentStatus`, `shiftActive`,
  and `voxUsername` — and resumes heartbeating **only if** that record says
  `shiftActive == true`. If the record is missing or says `false`, the service calls
  `stopSelf()` rather than guessing — fail-closed, matching the rest of this app's
  fail-closed conventions. A resumed service also re-runs
  `PresenceActions.applyStatus(...)`, which re-triggers the Voximplant silent-login
  chain — necessary because process death also killed the SDK's `Client` session, not
  optional.

**Do not defeat the 90-second gate.** Nothing here tries to keep the server believing a
dead device is live. The service's only job is to make a *genuinely alive, on-shift*
device *stay* reflected as such; every failure path above resolves to "stop trying and
let the gate do its job," never to fabricating freshness.

### Notification (Part 2): status + shade/lock-screen actions

Channel `kalfa_agent_presence`, `IMPORTANCE_LOW` (ongoing/informational, no sound —
matches the existing `CallForegroundService` channel's choice, not a new convention).
Content updates reactively from `AgentPresence.currentStatus`, so it reflects a change
made from *either* the Dashboard status control or a notification action — both paths
go through the same `PresenceActions.applyStatus` (below), so there is exactly one
place "what happens when status changes" is implemented.

Three actions, exactly mirroring `AgentStatus` — **no new states invented**:
זמין (READY) / לא זמין (NOT_READY) / נא לא להפריע (DND). `IN_CALL` is never offered as an
action — the interface comment on `AgentPresence.setStatus` already says the app must
never write it; a notification button is user input, same rule applies. Each action is
a `PendingIntent.getBroadcast` into `PresenceActionReceiver`, which calls
`PresenceActions.applyStatus(status, voxUsername)` directly — chosen over routing
through the (possibly-absent) `MainActivity`/`ConsoleViewModel` specifically so the
action works with the app fully backgrounded or the Activity destroyed.

**Lock-screen visibility:** `VISIBILITY_PUBLIC`. The presence notification carries only
the agent's own status label — no guest name, phone, or call content ever reaches it —
so there is nothing here that "leaking customer information" could mean. (Contrast with
the incoming-call notification below, which does carry a real phone number and is
handled differently for exactly that reason.)

**What if `POST_NOTIFICATIONS` is denied?** **[verified,
`developer.android.com/develop/ui/views/notifications/notification-permission`]**: *"Apps
don't need to request the `POST_NOTIFICATIONS` permission in order to launch a
foreground service."* The heartbeat keeps running either way — denial only means the
agent loses the shade/lock-screen status control and sees the FGS notice in the system
Task Manager instead of the drawer. Not a silent failure of the thing that actually
matters (staying routable); documented so it doesn't get mistaken for one.

## Part 3 — incoming call

### End-to-end flow

1. Guest calls; primary ring excludes this agent (stale/no heartbeat) or this **is**
   the retry wave. Voximplant's `callUser` (already deployed with
   `require(Modules.PushService)` per `AGENTS.md`) sends the FCM data push because the
   device is backgrounded/killed.
2. `VoxFirebaseMessagingService.onMessageReceived` → `VoxWakePushHandler.handle` runs
   the existing three steps (silent login → `handlePushNotification` → re-register push
   token) — **unchanged by this work.**
3. `handlePushNotification` causes the SDK to fire `IncomingCallListener.onIncomingCall`
   → forwarded to `VoxClientManager.onIncomingCall`, which **this change assigns for the
   first time**, in `DependencyContainer`, to `VoxIncomingCallCoordinator::handleIncomingCall`.
4. The coordinator wraps the SDK `Call` in the **existing** `VoxCallSession` immediately
   (state starts `RINGING`), so a caller-abandons-before-answer disconnect is observed
   through the same `CallListener` path that already exists — no separate "is this offer
   still live" bookkeeping. It starts `CallForegroundService` (already-built,
   `microphone`-typed — retitled "שיחה נכנסת..."; this is the *only* FGS involved here,
   nothing new-typed is added) and posts a `CallStyle` notification (below).
5. **Answer**, from either the notification action or the on-screen ring surface (below),
   calls `session.answer()` — a new default method on `CallSession` (default no-op, so
   every other implementer is unaffected) — which `VoxCallSession` implements as
   `call.answer(CallSettings())`. On success the coordinator calls the new
   `CallEngine.attachIncomingSession(session)`, which `SupabaseCallEngineImpl` overrides
   to set `_currentSession.value = session` — the **same** `StateFlow` `ConsoleViewModel`
   already merges into `ConsoleUiState.currentSession` (`ConsoleViewModel.kt` combine
   block, unchanged). **`InCallScreen` and its `BuildConfig.DEBUG` gate in
   `MainActivity` are deliberately untouched by this change** — see "What this change
   does not do" below for why, and what that means for what an answered call looks
   like today.
6. **Decline**, from either surface, calls `session.decline()` (new default method,
   `VoxCallSession` implements as `call.reject(RejectMode.Decline, emptyMap())`).
7. Whichever way the leg ends — declined, hung up after answer, remote party hangs up,
   SDK failure — `VoxCallSession.state` reaches `DISCONNECTED` through its **existing**
   `CallListener`. The coordinator observes that single transition once and does all
   cleanup from it (cancel notification, stop `CallForegroundService`,
   `CallEngine.clearAttachedSession()`) — one cleanup path regardless of which of the
   four ways the call ended, instead of four separate teardown call sites.

### Why a (minimal) new screen is added here, and `InCallScreen` is not touched

The brief's own constraint allows a new screen "if a full-screen incoming-call surface
genuinely requires one." It does, for a reason specific to **locked-device** full-screen
intents: `setFullScreenIntent`'s locked-device behavior does not draw a system-provided
call UI on your behalf — **[verified against
`source.android.com/docs/core/permissions/fsi-limits` and the FSI implementation
guidance surveyed for this change]** it launches *your* `PendingIntent`'s target
`Activity` full-screen over the keyguard, and *that activity's own content* is what the
agent sees. If the FSI target is `MainActivity` showing its ordinary nav/dashboard
content, a locked phone that starts ringing shows the wrong thing — normal app UI, not
an answer/decline prompt — which is worse than not wiring FSI at all.

So: **`ui/screens/IncomingCallScreen.kt`** is new, and deliberately minimal — caller
label + Answer/Decline, nothing else. No mute/hold/DTMF/keypad, no RSVP-capture form.
It is rendered by `MainActivity` as a top-level overlay when
`VoxIncomingCallCoordinator.pendingOffer` is non-null — a new, separate condition from
the existing `state.currentSession != null && BuildConfig.DEBUG` branch, which this
change does not modify.

**This is a deliberate, narrower choice than reusing `InCallScreen`, made after
reconsidering a first draft of this plan that would have extended `InCallScreen` with a
RINGING/answer-decline branch and removed its `BuildConfig.DEBUG` gate.** That direction
was dropped because (a) it would make `InCallScreen` — and the RSVP-capture "שמור ונתק"
button that `AGENTS.md` "Known state" #3 documents as an intentional no-op
(`saveRsvpResult` is an empty body by design) — reachable in production for the first
time, which is a materially bigger, production-visible change than "presence and
incoming-call UX" asks for; and (b) the brief is explicit that "the owner has been
explicit that they do not want the app's visible UI growing" and "the notification IS
the UI for presence" — the same discipline should default to *not* growing the call
surface either, and a minimal purpose-built ring screen satisfies the FSI requirement
without inheriting either of those two costs. **Consequence, stated plainly: this change
delivers working two-way audio and full notification-based control (answer, decline,
and — once answered — hang up, via an action added to `CallForegroundService`'s
notification) for an incoming call, but no in-app "connected call" screen.** The
`attachIncomingSession`/`clearAttachedSession` pair on `CallEngine` exists so that the
next phase (full `CallEngine` wiring, already tracked as **OPEN** in `AGENTS.md`) has
a real `CallSession` to render against when it decides what that screen should be —
this change does not make that decision for it.

### Incoming-call notification: `CallStyle`, and lock-screen redaction

`NotificationCompat.CallStyle.forIncomingCall(person, declineIntent, answerIntent)`
**[verified, `developer.android.com/develop/ui/compose/notifications/call-style`]**,
API 31+ native, compat-emulated below that via the same `NotificationCompat` builder —
no version branch needed in this app's code, `NotificationCompat` handles it. Channel
`kalfa_incoming_call`, `IMPORTANCE_HIGH`, ringtone + vibration
(`AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST`), category `CATEGORY_CALL`.
Answer/decline are `PendingIntent.getBroadcast` into `IncomingCallActionReceiver` (not
`getActivity`) — deliberately, so both actions execute directly against the coordinator
without depending on any Activity existing or being resumed; the `Call` object and its
`answer()`/`reject()` are pure SDK-and-network operations that need no UI. Each intent
carries the offering call's `id` as an extra; the receiver and the coordinator both
check it against the *current* `pendingOffer` before acting, so a stale action (the
offer already disconnected, or a second call replaced it) is a no-op instead of calling
`answer()`/`reject()` on a dead or wrong `Call` — `Call.answer` is declared
`throws CallException` in the SDK precisely for the dead-call case, and the guard avoids
ever hitting that from a `BroadcastReceiver`.

**Lock-screen redaction — the one place this differs from presence.** Unlike
`console_call_feed` (deliberately PII-free per `AGENTS.md` #4), an SDK `Call` delivered
directly by Voximplant carries the real caller number (`Call.number`,
`VoxCallSession.customerPhone`) — this is genuine PII reaching the device outside the
app's own PII-free views. The notification is built `VISIBILITY_PRIVATE` with
`.setPublicVersion(...)` set to a redacted notification carrying no name or number
("שיחה נכנסת למסוף KALFA") — so a locked screen a bystander can see shows only that a
call is incoming, never who it's from, while the full `CallStyle` presentation (caller
label) is available once unlocked or in-app.

**Full-screen intent — always set, OS decides when to use it.**
`setFullScreenIntent(contentIntent, true)` is set unconditionally; the OS shows it
full-screen only on a locked/off device and otherwise treats it as the heads-up
banner's tap target — this app does not need to detect lock state itself.
`USE_FULL_SCREEN_INTENT` is already declared (`AndroidManifest.xml`, present since
before this change). **Android 14 nuance, verified against
`developer.android.com/develop/ui/compose/notifications/call-style`:** apps installed
*after* a device is already on Android 14 do not get this permission auto-granted and
must be pointed at Settings (`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` /
`NotificationManagerCompat.canUseFullScreenIntent()`); this app was already declaring
the permission before this change (for the pre-existing `USE_FULL_SCREEN_INTENT`
manifest entry), so existing installs are unaffected, but a **fresh install on an
Android 14+ device may have it silently withheld**. Not handled defensively in this
change (no settings-deeplink UI added, to keep the "no new screens" scope) — **flagged
as an owner-visible risk**: if FSI silently doesn't fire on a specific Android 14+
device, this is the first thing to check (`canUseFullScreenIntent()`), and the fallback
(heads-up banner with working Answer/Decline buttons) still functions either way.

### RECORD_AUDIO / POST_NOTIFICATIONS must be requested earlier than they are today

`EnsureCallAudioPermission()` (`telephony/CallAudioPermissions.kt`) was only composed on
the live-calls screen — a screen an agent might never visit before their first inbound
call now arrives via push. This change also composes it once at the top level of
`MainActivity`'s authenticated content (its own `rememberSaveable` guard already
prevents a duplicate prompt at the existing call site), so the prompt fires as soon as
the agent is signed in, not only once they navigate somewhere specific. Without
`RECORD_AUDIO` granted, `CallForegroundService.start()` would throw `SecurityException`
when claiming the `microphone` FGS type — the coordinator checks
`ContextCompat.checkSelfPermission(RECORD_AUDIO)` before starting it and before calling
`session.answer()`; if not granted, the notification still shows (so the agent sees the
call and can open the app, which now prompts immediately) but the service is not
started and `answer()` is refused with a clear failure rather than crashing.

## Manifest changes, each with its justification

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```
Required alongside the `specialUse` service type (Part 1).

```xml
<service
    android:name=".telephony.presence.PresenceForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>
</service>

<receiver android:name=".telephony.presence.PresenceActionReceiver" android:exported="false" />
<receiver android:name=".telephony.vox.IncomingCallActionReceiver" android:exported="false" />
```
Not exported — only this app's own `PendingIntent`s target them; no external caller
should be able to flip agent status or answer/decline a call.

```xml
<activity android:name=".MainActivity" android:launchMode="singleTask" ...>
```
Added so a full-screen-intent launch while the app is already backgrounded (not
destroyed) is delivered to the same instance via `onNewIntent` instead of stacking a
second `MainActivity`. Single-activity app — no back-stack semantics change from this.

No new **runtime-dangerous** permissions. `RECORD_AUDIO`/`POST_NOTIFICATIONS` were
already declared; this change only moves *when* they're requested (previous section).
`MANAGE_OWN_CALLS`/`FOREGROUND_SERVICE_PHONE_CALL` remain deliberately undeclared — see
the Telecom decision above.

## Version-gating (minSdk 24 → targetSdk 36)

| Concern | Below 26 (O) | 26–30 | 31+ (S, CallStyle) | 33+ (Notif. permission) | 34+ (FGS types required) |
|---|---|---|---|---|---|
| Notification channels | N/A, no channels — `NotificationCompat` degrades gracefully | required | required | required | required |
| `startForeground` type param | ignored pre-Q; passed unconditionally, matches existing `CallForegroundService` pattern | `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` from Q (29) | same | same | **mandatory** — `MissingForegroundServiceTypeException` if absent, already declared for both new services |
| `CallStyle` | compat-emulated by `NotificationCompat` (custom action layout) | same | native platform `CallStyle` | same | same |
| `setShowWhenLocked`/`setTurnScreenOn` on the FSI target | not available (API 27+ only) — falls back to `WindowManager.LayoutParams` flags (`FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`, `FLAG_DISMISS_KEYGUARD`) for 24–26, version-gated in `MainActivity` | native APIs (27+) | same | same | same |
| `POST_NOTIFICATIONS` | N/A, always shown | N/A | N/A | **runtime permission**; FGS still runs if denied (Part 1) | same |
| FSI auto-grant | N/A | N/A | granted at install | same | **withheld for fresh installs on an already-14+ device** (flagged above) |

## Files changed / added

- New: `docs/android-presence-and-call-ux.md` (this file)
- New: `app/src/main/java/me/kalfa/agentconsole/telephony/presence/PresenceForegroundService.kt`
- New: `.../telephony/presence/PresenceActions.kt`
- New: `.../telephony/presence/PresenceStateStore.kt`
- New: `.../telephony/presence/PresenceActionReceiver.kt`
- New: `.../telephony/presence/PresenceNotificationBuilder.kt`
- New: `.../telephony/vox/VoxIncomingCallCoordinator.kt`
- New: `.../telephony/vox/IncomingCallNotificationBuilder.kt`
- New: `.../telephony/vox/IncomingCallActionReceiver.kt`
- New: `.../ui/screens/IncomingCallScreen.kt`
- Changed: `domain/telephony/Telephony.kt` — `CallSession.answer()`/`.decline()` default
  methods; `CallEngine.attachIncomingSession()`/`.clearAttachedSession()` default
  methods; `AgentPresence.shiftActive: StateFlow<Boolean>` default property.
- Changed: `telephony/vox/VoxCallSession.kt` — implements `answer()`/`decline()`.
- Changed: `data/SupabaseImplementations.kt` (`SupabaseCallEngineImpl`) — implements the
  three new methods/property above; `setAgentStatus`'s READY-path logic (declare shift +
  Voximplant login + push-token registration) is extracted to `PresenceActions` so both
  `ConsoleViewModel` and `PresenceActionReceiver` call the same code.
- Changed: `telephony/CallForegroundService.kt` — adds a "נתק" (hang up) notification
  action for an answered incoming leg (the only in-shade control for an active call,
  consistent with "the notification IS the UI" for this change's scope).
- Changed: `telephony/CallAudioPermissions.kt` — no logic change; composed from an
  additional call site (previous section).
- Changed: `di/DependencyContainer.kt` — constructs `VoxIncomingCallCoordinator` and
  `PresenceStateStore`, assigns `VoxClientManager.onIncomingCall`.
- Changed: `ui/viewmodel/ConsoleViewModel.kt` — `ConsoleUiState.shiftActive`; delegates
  to `PresenceActions`.
- Changed: `MainActivity.kt` — `LaunchedEffect` starting/stopping
  `PresenceForegroundService`; renders `IncomingCallScreen` overlay; FSI lock-bypass
  window flags; top-level `EnsureCallAudioPermission()`.
- Changed: `AndroidManifest.xml` — see "Manifest changes" above.
- Changed: `AGENTS.md` — phase table + a new subsection recording this change,
  superseding the "onIncomingCall has no listener" statements it previously made.

## What could not be verified (no physical device in this environment)

- Actual notification rendering (`CallStyle` layout, action button placement, redaction
  in practice) on a real device or a specific OEM skin.
- Lock-screen behavior end to end: does the FSI actually bypass the keyguard on a real
  locked device, is the screen turned on, does `setShowWhenLocked` behave identically
  across OEMs.
- Full-screen intent on Android 14+ specifically for a **fresh install** — whether it is
  silently withheld as documented, and whether `canUseFullScreenIntent()` correctly
  reports it.
- Doze and OEM battery-manager behavior for the `specialUse` FGS over a multi-hour real
  shift — whether Xiaomi/Huawei/Samsung aggressive battery modes kill the process despite
  FGS status.
- Whether audio is actually audible/two-way once `session.answer()` succeeds — this
  change wires the same `VoxCallSession`/`CallForegroundService` path the existing
  (unwired) monitor/takeover code already relied on, but that path itself has never been
  exercised against a live call in this repo either.
- The cold-start timing budget flagged in `AGENTS.md` "Push wake-up" (FCM delivery →
  connect → silent login → `onIncomingCall` → answer, inside `RING_RETRY_WINDOW_MS`,
  15s) remains unmeasured; this change adds work (notification build + FGS start) inside
  that same window and does not shrink the risk — if anything, it is one more reason a
  live-device timing test is needed before this path is trusted in the primary ring.

## Owner actions required

1. **Play Console `specialUse` justification** (Policy → App content) before any Play
   distribution — not blocking today (no upload keystore provisioned yet), but required
   before it is.
2. **Decide on `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.** Not added by this change
   (Play-policy-sensitive, needs its own justification); OEM battery killers are a real,
   unverified risk to the presence heartbeat without it.
3. **A physical device (ideally two OEMs) for the verification list above** before this
   path is trusted for a real inbound call, per `AGENTS.md`'s own standing rule that a
   feature isn't verified until live-call audio is checked.
4. Everything already listed as owner-side in `AGENTS.md` "Push wake-up" (the cold-start
   timing test) is unchanged and still outstanding — this change does not resolve it.

## Update 2026-08-14 (later the same day): sync-state tracking and error surfacing

Live platform evidence gathered after the work above landed, from Voximplant's own
call history and session logs (`agent_1bbe74dc-...`):

- **[measured]** `push_results: []` on every `Call.PushSent` event for this agent today
  — the platform had zero devices registered to push to. `registerCurrentPushToken()`'s
  failure was completely invisible: called as
  `vcm.ensureLoggedIn(...).onSuccess { vcm.registerCurrentPushToken() }` with the
  returned `Result` discarded, wrapping a `runCatching` that swallowed everything.
- **[measured]** When the app IS connected, the ring genuinely reaches it — the SDK leg
  reached `Ringing`, ran for ~14s (the scenario's own ring window), then the scenario
  hung up with `603` because nothing answered. Direct confirmation that the
  `onIncomingCall` wiring above (§3) is the right fix and the transport already works.
- **[measured]** A separate incident, same day: an agent reinstalled the app (clearing
  the Supabase session), tapped "זמין", and the app showed Ready. Three DB samples a
  minute apart confirmed the write never reached the server — `AgentPresence.setStatus`
  was `fun setStatus(status) { _currentStatus.value = status; scope.launch { ... catch
  { printStackTrace() } } }`: it flipped the UI to Ready BEFORE attempting the network
  call, and swallowed the resulting 401 (empty JWT, no session) with no trace anywhere
  the agent could see.

**Not addressed by this update, and out of this repo's boundary (owner is handling):**
the scenario's ring window (~14–15s) is tighter than the platform's own
`pushNotificationTimeout` (clamped to a default 20000ms) — a server/scenario-side
mismatch, reported, not changed here.

**Root cause for the missing push token has two candidates, and this update only fixes
one of them:** (a) the swallowed-failure bug above — now fixed; (b) the specific APK
installed on the device may have been built by a CI run that finished *before*
`GOOGLE_SERVICES_JSON_BASE64` was set (10:01:15Z vs. 10:09:47Z the same day) and
therefore has no working Firebase config regardless of app-side logic — which build is
actually on the device could not be determined from here. (a) is fixed unconditionally
below; (b), if it is the actual cause, needs a fresh install from a run after
10:09:47Z, which is an owner/ops step, not a code change.

### `PresenceSyncState`: requested vs. confirmed-by-server

New sealed type (`domain/telephony/Telephony.kt`): `Synced` / `Pending` /
`Failed(AppFailure)`. `AgentPresence.currentStatus`/`.shiftActive` still update
**optimistically** (immediate UI responsiveness — e.g. the Dashboard's status
buttons highlight instantly); `AgentPresence.syncState` is the truth about whether the
last write actually reached and was accepted by the server. One shared `syncState`
covers both `setStatus` and `setShiftActive` deliberately (not an oversight): they are
always fired together for the case that matters most (declaring READY, via
`PresenceActions`), and a UI showing one combined "is presence in a known-good state"
signal is simpler and more honest than reconciling two that could disagree.

`AgentPresence.setStatus`/`.setShiftActive` are now `suspend fun ... : AppResult<Unit>`
— matching the existing convention already used by `CallEngine.sendAgentCommand`/
`.endCall`/`.enqueueOutboundCall`, not a new parallel pattern. Both check for an empty
JWT (`getJwt().isEmpty()`) **before** attempting the network call and return the new
`AppFailure.NotSignedIn` directly, rather than sending an unauthenticated request and
generically mapping its 401 to `Unauthorized` — see next section for why that
distinction matters. On any failure, `syncState` becomes `Failed(failure)` immediately
(not after some threshold of repeated failures) — the conservative, simpler choice: a
UI showing degraded state on the FIRST failure is strictly more honest than waiting to
see if it happens again.

**The presence notification (§1) now renders `syncState`, not just `currentStatus`:**
`Synced` → `"סטטוס: <status>"`; `Pending` → `"...(מעדכן מול השרת...)"`; `Failed` → the
specific `AppFailure.toHebrewMessage(FailureContext.PRESENCE)` text, replacing the
status line entirely. This is the fix for the reinstall incident specifically: the
ONE surface a backgrounded agent sees can no longer show a bare "זמין" the server never
confirmed.

### Wired into the EXISTING error-surfacing infrastructure, not a new one

`domain/error/AppFailure.kt`, `data/FailureMapping.kt`,
`ui/message/FailureMessages.kt`/`toHebrewMessage(FailureContext)`, and
`ui/message/MessageComponents.kt`/`AppMessageBanner` already existed and are
already used by the rest of the app (`ConsoleViewModel`'s repository-health messages).
Presence/push-registration simply weren't wired into them. This change:

- Adds `AppFailure.NotSignedIn`, distinct from `AppFailure.Unauthorized` — a session
  that never existed (fresh install, signed out) is a different, differently-actionable
  fact from one that expired. Conflating them told an agent who had never signed in
  that their session had "expired," which was measured as simply false.
- Adds `FailureContext.PRESENCE` and `FailureContext.PUSH_REGISTRATION`, with Hebrew
  text that names the **consequence** ("שיחות לא יגיעו" / "המכשיר לא נרשם לקבלת
  שיחות") rather than the mechanism ("הפעולה נכשלה") — the owner's specific,
  verbatim objection to generic failure text.
- `telephony/presence/PresenceActions.applyStatus` — already the single place the
  READY-path logic lives (shared by `ConsoleViewModel` and the notification's
  `BroadcastReceiver`, neither of which can hand results back to a caller UI directly)
  — now publishes every step's outcome via `AppMessageCenter.publish`/`.resolve`
  directly. This is a plain global object (`ui/message/MessageCenter.kt`), not tied to
  any ViewModel, so it works from a `BroadcastReceiver` with no Activity alive exactly
  as well as from `ConsoleViewModel`. Messages are `dismissible = false` — a persistent
  condition ("you may not be reachable") gets a persistent surface
  (`AppMessageBanner`, already rendered by `MainActivity` via
  `ConsoleUiState.globalMessages`), not a snackbar the agent can swipe away while the
  underlying problem remains; each clears itself via `resolve()` the moment a write
  actually succeeds.
- Push-token registration failures (`VoxClientManager.registerCurrentPushToken`,
  `Result<Unit>`) are mapped through the same `Throwable.toAppFailure()`
  (`data/FailureMapping.kt`) already used everywhere else, and reported through
  `FailureContext.PUSH_REGISTRATION` — this is the app-side fix for Finding 1's
  candidate cause (a).

**Superseded by the next section, below — kept here rather than silently deleted, so
the reversal is visible:** this paragraph originally said the push-registration
failure should surface via `AppMessageBanner` only, not the persistent notification,
reasoning that it only fires on the interactive READY tap while the agent has the app
open. Reversed within hours: a process death between a failed registration and the
agent's next app-open loses an `AppMessageBanner`-only record entirely (it's
in-memory only), and the persistent notification must not read as a plain working
"זמין" while push registration is broken — see the next section.

### Files touched by this update

`domain/error/AppFailure.kt` (`NotSignedIn`), `domain/telephony/Telephony.kt`
(`PresenceSyncState`, suspend/`AppResult` signatures), `ui/message/FailureMessages.kt`
(`FailureContext.PRESENCE`/`.PUSH_REGISTRATION` + their text), `data/SupabaseImplementations.kt`
(`SupabaseCallEngineImpl.setStatus`/`.setShiftActive` rewritten), `data/mock/MockImplementations.kt`
(signature match), `telephony/presence/PresenceActions.kt` (result surfacing),
`telephony/presence/PresenceNotificationBuilder.kt` (renders `syncState`),
`telephony/presence/PresenceForegroundService.kt` (observes `syncState` too),
`ui/viewmodel/ConsoleViewModel.kt` (`logout()` moved into its coroutine — `setShiftActive`
is suspend now). New tests: `ui/message/FailureMessagesTest.kt` gained cases asserting
`NotSignedIn` reads differently from an expired session and that PRESENCE/
PUSH_REGISTRATION text diverges from the generic fallback.

## Update 2026-08-14 (even later): durable push-registration failure, and the platform said it outright

Two more live calls after the section above, from the SAME agent
(`agent_1bbe74dc-5721-48e9-9092-fd9e3c6e6b21`) — narrowing from inference to the
platform's own words:

- **[measured, session 7665916994]** `Call.Failed reason = "No push notifications has
  been sent" code = 480`, alongside `Call.PushSent result = {"push_results": []}`.
  Voximplant is stating directly that it holds **no registered push token** for this
  user — four ring attempts in ten minutes, all identical.
- **[measured, by elimination]** Every other candidate cause was ruled out live:
  `ensureLoggedIn` works (a separate session reached `Call.Ringing` over a connected,
  logged-in client); Supabase writes work (`agent_status` was 39s fresh);
  `console_me` genuinely exposes `vox_username` (checked against the live view); the
  installed APK genuinely carries a valid `google-services.json` (`google_app_id`
  extracted directly from the APK) — ruling out root cause (b) from the previous
  section for the specific build tested. That leaves
  `VoxClientManager.registerCurrentPushToken()` as the step that never completes —
  confirmed, not merely suspected.
- **[measured]** The installed APK is from CI run `31791889800` (`ccb7bc5` + the icon
  commits) — **not** `354d531` or anything from this doc's own work, which had not
  reached a device build at the time these three sessions were captured. The next
  build the owner installs is the first chance to see this app-side fix run for real.

**Not this repo's to change, restated because it's easy to mis-size around:** the
scenario's ~15s ring window vs. the platform's ~20s `pushNotificationTimeout` is
server-side and is being widened to accommodate a pushed device — do not tune
anything here to fit 15s; assume 30s once that lands, and it remains unverified
end-to-end either way.

### What changed here, on top of `PresenceSyncState`

**`VoxClientManager.registerCurrentPushToken()` now tags WHICH of two failure domains
occurred**, instead of one `runCatching` around both steps: a local Google Play
Services/FCM problem fetching the token (`"fcm_token: ..."`) vs. Voximplant's own
`registerForPushNotifications` SDK call failing (`"registerForPushNotifications:
..."`, already tagged at its source). Both throw the existing `VoxAuthException.Sdk`
type — no new exception type — so every other caller is unaffected; only the message
distinguishes the two, because the two point an investigation in different
directions and conflating them was exactly what made the last two hours of remote
diagnosis (reading Voximplant's platform logs instead of this app's own state) take
as long as it did.

**The outcome is now durable, not just in-memory.** `AppMessageCenter` is a plain
`MutableStateFlow` — it survives backgrounding but not process death, and
`PresenceForegroundService` runs with no Activity/ViewModel alive, so a failure that
happens there and is never seen before the process dies was being lost entirely.
`PresenceStateStore` gained `recordPushRegistrationOutcome`/
`loadPushRegistrationFailure` (failure category + the WHICH-step detail string +
timestamp; `null` failure = last known attempt succeeded). A new plain global object,
`PushRegistrationState` (mirrors `AppMessageCenter`/`AppVisibility`'s pattern), holds
the in-process reactive mirror so the notification doesn't need to poll DataStore on
every tick; `PresenceForegroundService.onCreate` seeds it from the persisted record
**before** the first notification is built, so a freshly restarted process shows the
truth immediately rather than waiting for a new registration attempt that might not
happen for a while.

**The persistent presence notification now ALSO reflects push-registration failure —
reversing the previous section's "deliberately not done".** `syncState` still takes
priority when both are wrong (not being confirmed present at all is the more urgent
fact), but a `Synced` status with a failed push registration no longer reads as a
plain working "זמין": `PresenceNotificationBuilder.contentTextFor` (pulled out as a
pure, directly-testable function) now takes both signals. `PresenceActions` persists
and updates `PushRegistrationState` **before** touching `AppMessageCenter`, so the
durable record is written first regardless of whether anything is currently
collecting the in-memory one.

### Files touched by this update

`telephony/vox/VoxClientManager.kt` (`registerCurrentPushToken` — two tagged steps),
`telephony/presence/PresenceStateStore.kt` (`PersistedPushRegistrationFailure`,
`recordPushRegistrationOutcome`/`loadPushRegistrationFailure`,
`appFailureToPersistName`/`appFailureFromPersistName`), new
`telephony/presence/PushRegistrationState.kt`, `telephony/presence/PresenceActions.kt`
(persists before publishing), `telephony/presence/PresenceNotificationBuilder.kt`
(`contentTextFor` extracted, third parameter), `telephony/presence/PresenceForegroundService.kt`
(shared `DependencyContainer.presenceStateStore` instead of its own instance, seeds
`PushRegistrationState` in `onCreate`, three-way `combine`), `di/DependencyContainer.kt`
(`presenceStateStore` property, same non-sticky-null pattern as `voxTokenStore`). New
tests: `telephony/presence/PresenceNotificationBuilderTest.kt` (the priority rules
between `syncState` and `pushRegistrationFailure`, pure function, no Robolectric),
`telephony/presence/PresenceStateStoreTest.kt` gained an `AppFailure` persist-name
round-trip case plus an unrecognized-name-falls-back-to-Unknown case.
`./gradlew testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL, 55/55 tests pass.

## Update 2026-08-14 (still later): `RingCapability` — declaring a permission is not holding it

A manifest audit (by the owner, independently) found the gap directly: `USE_FULL_SCREEN_INTENT`
is declared and `IncomingCallNotificationBuilder.kt` calls `.setFullScreenIntent(...,
true)` — the only reference anywhere. Nothing checked whether the app actually *held*
the permission. Per the Android 14 behavior-change doc: only apps whose core function
is calling/alarms get it auto-granted; Play revokes the default grant for everything
else; the user can turn it off regardless, permanently. `setFullScreenIntent` does not
fail when the permission is absent — it silently degrades to a heads-up notification.
Same failure shape as the push-token problem above, in the one code path whose entire
purpose is to work on a locked screen.

**Detection was built separately** (`telephony/vox/RingCapability.kt` +
`RingCapabilityChecker`, `notificationsEnabled` / `channelAlerting` /
`fullScreenIntentAllowed`, derived `canAlert` / `canRingOnLockedScreen`;
`fullScreenIntentSettingsIntent()` for the one-tap fix, `null` below API 34 where
there's nothing to grant). Kept deliberately separate from `AppFailure`/
`FailureContext`: this is a device-configuration snapshot, not an attempted
operation's outcome, and there's no HTTP status or exception to map — forcing it into
that taxonomy would be the wrong shape.

**Wired here:**
- `PresenceNotificationBuilder.contentTextFor` gained a fourth input.
  Priority when more than one signal is wrong: `syncState` first (not even confirmed
  present outranks everything); then `!canAlert` (blocks every other channel too,
  including this notification's own future updates); then push-registration (defeats
  being *woken*, but a live app can still show its own incoming-call notification);
  then the narrower `!canRingOnLockedScreen` gap. Two distinct Hebrew texts, matching
  the consequence difference: `!canAlert` — "התראות למסוף חסומות — שיחות נכנסות לא
  יוצגו כלל"; `!canRingOnLockedScreen` — "מסך שיחה נכנסת לא ייפתח במכשיר נעול —
  שיחות עלולות להתפספס".
- One fix-it notification action, mutually exclusive by construction
  (`canRingOnLockedScreen` implies `canAlert`): `ACTION_APP_NOTIFICATION_SETTINGS`
  when notifications are blocked entirely, `fullScreenIntentSettingsIntent()`'s
  `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` when only the locked-screen path is
  blocked. The general notification-settings action is one addition beyond the
  literal ask (only the FSI-specific action was requested) — small, consistent, same
  "give a way to fix it" principle applied to the more severe case.
- `telephony/vox/RingCapabilityState`, a plain global object (mirrors
  `PushRegistrationState`): no OS callback exists for "notification settings
  changed", so `PresenceForegroundService` refreshes it once in `onCreate` (before
  the first notification is built) and again every heartbeat tick — a fix or a new
  break is reflected within one 30s interval, not only at service restart.
- `PresenceActions.applyStatus`'s READY branch also publishes an `AppMessageCenter`
  banner (new `di/DependencyContainer.appContext` — a narrow, documented escape
  hatch for the one case where neither `ConsoleViewModel` nor
  `PresenceActionReceiver` has a `Context` of their own) — the in-app surface for the
  moment the agent is actually looking at the screen, pointing them at the
  notification's own fix-it action for the locked-screen case.

New tests: `RingCapabilityTest.kt` (5 tests, the owner's own — not touched) plus four
new cases in `PresenceNotificationBuilderTest.kt` covering the priority rules against
`RingCapability`. `./gradlew testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL,
59/59 tests pass.

**Still unverified, same reason as everything else in this doc:** no physical device.
Whether `canUseFullScreenIntent()` reports correctly, whether the settings deep links
actually land on the right screen, and whether the fix is durable after the user
grants it, are all open per "What could not be verified" above.

## Update 2026-08-14 (latest): RETRACTED as the cause of today's incident — timeline
## refutes it. Real mechanism, wrong incident. Kept below, corrected, not deleted.

**The section that follows this note originally claimed the mis-signed-debug-APK /
Firebase-API-key-restriction mechanism explained the empty `push_results` observed in
session 7665916994 and earlier the same day. It does not, and the claim was wrong —
caught by a timeline check the original write-up skipped.**

```
push_results:[] first observed (session logs, from)     2026-08-14 10:31 UTC
push_results:[] observed, session 7665916994             2026-08-14 11:24:29 UTC
Firebase Android API key restricted (gcloud updateTime)  2026-08-14 12:47:46 UTC
```

The restriction — verified via `gcloud services api-keys describe
10b5313d-d8dd-4f6b-b9f4-36f1ba07cb15 --project=kalfa-rsvp --format='value(updateTime)'`
— was applied **83 minutes after** the session this doc already cited as evidence, and
over two hours after the earliest identical failures the same day. No Android
application restriction existed on that API key before 12:47:46 UTC. A restriction
that does not yet exist cannot reject a request that already failed. The mechanism
described below is real, but it is not what caused the failures this doc attributed it
to, and the section's original "confirmed"/"probable root cause" framing (and the
matching bullet added to `AGENTS.md`'s "Push wake-up" section) was wrong to assert.

**What still stands, and is worth keeping:**
- The mechanism itself (debug builds signed by a keystore CI regenerates every run;
  the API key restriction, now live, only allow-lists the release upload key's SHA-1)
  is real and is now a genuine forward-looking trap **from 12:47:46 UTC onward** — any
  debug-signed install attempting FCM registration after that timestamp will fail this
  way. `b5a11f4` (already on `main` before this correction) already prevents CI from
  publishing an installable debug APK for exactly this reason. Nothing here changes
  that; it is good, still-valid, defensive work — just not an explanation for events
  that happened before the trap existed.
- The corroborating detail — the exact Hebrew string the owner reported,
  "המכשיר לא נרשם לקבלת שיחות כשהאפליקציה סגורה" with no trailing sentence — is
  `AppFailure.Unknown` under `FailureContext.PUSH_REGISTRATION`
  (`ui/message/FailureMessages.kt:44-48`), not `AppFailure.NetworkUnavailable`
  (`:7-11`, which appends "בדוק את החיבור ונסה שוב"). That still narrows the search:
  whatever throws is not a `java.io.IOException` (`data/FailureMapping.kt:6-10`). It
  does not, on its own, distinguish *which* non-`IOException` failure it was — see the
  next section.
- Installing `kalfa-release-apk` (latest green run, `31816353447`/`b134ac4`) is still
  worth doing regardless — it carries the visibility fixes (the `fcm_token:` /
  `registerForPushNotifications:` tagging) that the next section explains do not
  reliably reach the surface the owner may have been reading from. It is a genuine
  diagnostic upgrade even though it is not confirmed to be *the* fix.

**What is now open again, unconfirmed:** why `push_results` has been empty since
10:31 UTC, hours before the API key was touched. See the next section for a
code-derived narrowing (not yet device-verified) and the remaining candidate
directions from `AGENTS.md`'s original task framing.

### Note on the persistent notification vs. the in-app banner — a distinction the
### retracted section did not check, and the next investigator needs

`PresenceNotificationBuilder.contentTextFor` (`telephony/presence/
PresenceNotificationBuilder.kt:80-94`) renders push-registration failure as
`pushRegistrationFailure.toHebrewMessage(FailureContext.PUSH_REGISTRATION)` — the
coarse `AppFailure` only. It has no access to, and never renders, the WHICH-step
`detail` string (`"fcm_token: ..."` / `"registerForPushNotifications: ..."`) that
`VoxClientManager.registerCurrentPushToken` tags. Only `PresenceActions.
reportPushRegistrationResult`'s `AppMessageCenter` banner
(`telephony/presence/PresenceActions.kt:111-142`, via `pushFailureStageSuffix`)
appends that detail, as a second sentence.

Consequence: **if the owner's report of the exact text came from the persistent
notification (the always-visible surface this whole design centers on), the absence
of a "the device itself..."/"the telephony system rejected..." second sentence proves
nothing about which step failed** — the notification never shows that sentence
either way. Only if the text came from the in-app banner does a bare, unsuffixed
sentence mean `registerCurrentPushToken()` was never reached at all (i.e.
`VoxClientManager.ensureLoggedIn()` failed first — none of its own exception messages,
`"no Supabase session"` / `"agent has no Voximplant identity (sdk-auth 409)"` /
`"not a console agent (sdk-auth 401)"` / `"sdk-auth HTTP ..."` / `"connect: ..."` /
`"requestOneTimeKey: ..."` / `"loginWithOneTimeKey: ..."` / `"loginWithAccessToken:
..."` / `"refreshToken: ..."`, start with `fcm_token:` or `registerForPushNotifications:`
either). This has not been established either way — which surface produced the text
the owner reported is unknown from this environment and needs to be asked, not
assumed.

**The ground truth that settles it without needing the UI at all:**
`PresenceStateStore.recordPushRegistrationOutcome` persists the tagged `detail` string
durably (`telephony/presence/PresenceStateStore.kt:104-116`, DataStore Preferences,
file `presence_state.preferences_pb` under the app's `files/datastore/` directory). If
the device is reachable even briefly: `adb shell run-as me.kalfa.agentconsole cat
/data/data/me.kalfa.agentconsole/files/datastore/presence_state.preferences_pb` (works
only if the installed build is debuggable — `run-as` requires it) would show the exact
persisted `push_registration_failure_detail` key, which is the actual answer,
independent of any Hebrew-text inference. If that is not possible, `adb logcat`
captured while the agent taps "זמין" — filtered for the Voximplant SDK's own `Logger`
output and this app's package — is the next-best evidence: the SDK's `PushManager`
(byte-verified via `javap`) logs its own failures (`PushTokenError`, connection-state
transitions) independently of anything this app reports.

**Correction to the "even later" section above.** That section ruled out root cause
(b) — "no working Firebase config" — by extracting `google_app_id` from the installed
APK and confirming `google-services.json` was baked in. That check answers a different
question than the one that matters: whether the APK's **signing certificate** is one
Google will accept requests from. A build can carry a perfectly valid
`google-services.json` and still have every FCM token request rejected at Google's API
layer if it is signed with a certificate that isn't on the API key's allow-list.
Conflating the two was the gap.

**[measured, git history + `gh run view`]** On 2026-08-14, the Firebase Android API
key for project `kalfa-rsvp` (`10b5313d-d8dd-4f6b-b9f4-36f1ba07cb15`) was restricted
(ops action, recorded in `AGENTS.md` "Build & CI") to package `me.kalfa.agentconsole` +
SHA-1 `e011b737d04d91d3488c991ca96d089117b8734c` — the certificate of
`my-upload-key.jks`, the release signing key. `app/build.gradle.kts`'s `buildTypes`
block (`app/build.gradle.kts:81-88`) declares a `signingConfig` for `release` only;
`debug` gets AGP's own default debug config, and this repo's CI workflow generates that
keystore **fresh on every run** (`.github/workflows/android-build.yml`, "Create
temporary debug keystore" step, `keytool -genkeypair` with no persisted/cached
keystore path) — a new, random certificate every build, none of them the upload key's.
Restriction enforcement happens on Google's servers at request time, not at build or
install time, so this applies to every debug build regardless of when it was compiled
relative to the restriction being added.

**[measured, `gh run view 31791889800`]** The CI run this doc's own "even later"
section identifies as the source of the installed APK (`ccb7bc5` + the icon commits)
published three artifacts: `kalfa-debug-apk`, `kalfa-release-apk`, `kalfa-release-aab`
— confirming a `kalfa-debug-apk` genuinely existed from that run and was available to
install. **[inference, not confirmed from this environment]** which of the two APK
variants from that run is the one actually on the device could not be determined
without device access; the strong circumstantial case for the debug variant is that
(a) it was the default, no-extra-steps download throughout this project's ad-hoc
testing (there is no Play distribution yet), and (b) the same-day fix below exists
specifically because someone connected this mechanism to a real, currently-broken
install — not as a hypothetical hygiene concern.

**[measured, byte-for-byte, corroborating]** The exact Hebrew string the owner
reported seeing, "המכשיר לא נרשם לקבלת שיחות כשהאפליקציה סגורה" (no trailing
"בדוק את החיבור ונסה שוב"), is `FailureContext.PUSH_REGISTRATION` under
`AppFailure.Unknown` specifically (`ui/message/FailureMessages.kt:44-48`), not
`AppFailure.NetworkUnavailable` (`:7-11`, which appends that trailing sentence).
`Throwable.toAppFailure()` (`data/FailureMapping.kt:6-10`) maps to `NetworkUnavailable`
only for `java.io.IOException`; anything else — including the
`RuntimeExecutionException`/`FirebaseException`-shaped errors Google Play services
throws for an API-key/certificate rejection, which are not `IOException` — falls to
`Unknown`. The specific message the owner saw is consistent with an API-key rejection
and inconsistent with a plain connectivity failure, though the exact exception type has
never been read off a device.

**This was already fixed prospectively, same day, before this note:** commit `b5a11f4`
stopped CI from publishing `kalfa-debug-apk` as an installable artifact, specifically
because of this mechanism (see that commit's own message — it states the certificate
mismatch outright). It does not fix a device that already has the old debug build
installed; nothing in this repo can push new bits to that device.

**The unblock, verified as available right now:** the latest green run on `main`
(`31816353447`, commit `b134ac4`) reached `ready=true` in "Check release readiness"
and published `kalfa-release-apk` / `kalfa-release-aab`, signed with the upload key
whose SHA-1 is the one the API key allow-list holds. **Uninstalling the current app and
installing that release APK is the fix** — no code change is needed for this root
cause, because there is no app-code bug in it: the platform-side rejection happens
before any of this app's own registration logic runs. Uninstall first — a
signature-mismatch reinstall over the existing (differently-signed) app will be
refused by the OS.

**To confirm which certificate is actually on the device**, before or after
reinstalling: `apksigner verify --print-certs <the-apk-file>` (run against a copy of
whatever was installed, if one is available) or, with `adb` reachable,
`adb shell dumpsys package me.kalfa.agentconsole | grep -A2 signatures` — compare the
SHA-1 against `e011b737d04d91d3488c991ca96d089117b8734c`. Neither could be run from
this environment (no device attached).

**What this finding does NOT establish:** that `registerCurrentPushToken()` succeeds
end to end on a correctly-signed build, or that a push actually wakes the device inside
the ring window. Both remain unverified per "What could not be verified" above. The
"fcm_token:"/"registerForPushNotifications:" tag on the next registration attempt from
a `kalfa-release-apk` install is the next real signal to read.

### A second, independent finding: the wake-path timeout can silently swallow the
### push-registration outcome — flagged, not fixed here

**[measured, byte-verified via `javap` on the shipped `android-sdk-core-3.2.0.aar`]**
`PushManager.registerPushToken$android_sdk_core_release` sends the registration
request immediately if the SDK's own internally-tracked client state is already
`LoggedIn`, otherwise queues it and flushes on the next login transition; either way,
a per-request response timeout is scheduled via `createTimeoutFutureForRequest` at a
hard-coded `PUSH_MANAGER.PUSH_TOKEN_TIMEOUT = 10_000L` (10 seconds) — not
configurable from this app's side.

`VoxFirebaseMessagingService.WAKE_PUSH_TIMEOUT_MS = 9_000L`
(`telephony/vox/VoxFirebaseMessagingService.kt:93`) wraps the **entire** three-step
sequence (`ensureLoggedIn` → `handlePushNotification` → `registerPushToken`) in one
`withTimeoutOrNull`. Because 9s < the SDK's own 10s internal timeout, if
`registerPushToken()` is still in flight when the outer 9s deadline fires, the whole
coroutine — including the `suspendCancellableCoroutine` awaiting the SDK's
`RegisterPushTokenCallback` — is cancelled before the SDK's own timeout could ever
have delivered `onFailure`. The result: no success, no failure, nothing recorded via
`PushRegistrationState`/`PresenceStateStore` — a push-wake re-registration attempt
that runs long produces a genuinely silent non-outcome, structurally the same shape of
bug `db49786` fixed for the interactive path.

**Not the cause of today's symptom:** the interactive READY-tap registration path
(`ConsoleViewModel.setAgentStatus` → `PresenceActions.applyStatus`) has no such
timeout, and the platform's own logs show the failure starting from the very first
registration attempt of the day — before any push-wake cycle could have run at all.

**Not fixed in this change, deliberately:** the "right" number depends on how long
`ensureLoggedIn`'s three login paths and `handlePushNotification` actually take on a
real device — exactly the still-open, still-unmeasured cold-start timing test this doc
and `AGENTS.md` "Push wake-up" already flag repeatedly. Widening the outer timeout
without that data is another guess stacked on an already-flagged guess (the original
9s was itself "a judgment call... NOT a measured figure" per its own comment); shrinking
the registration step's effective budget further would make the race worse, not
better. Fix together with that timing test, not in isolation from it.
