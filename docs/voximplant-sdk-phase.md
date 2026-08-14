# Voximplant SDK phase — implementation state & handoff

> **UPDATE 2026-08-14 — SDK login + call-session layer is now BUILT (unwired); the
> monitor/takeover backend route is now BUILT (feature-flagged off). This revises the
> "current state" table below, which previously said "none" for both.** Verified by
> reading `telephony/vox/*.kt` in this repo and `src/app/api/calls/[callAttemptId]/monitor/`
> + `src/lib/data/console-monitor.ts` + `docs/voice-agent/app-integration-reference.md`
> (2026-07-22b) in `kalfa.me/beta` (read-only — this doc does not own that repo's state,
> it only records what was verified there). The two facts the 2026-07 update below
> already flagged (v3 SDK, `/api/agents/sdk-auth` live) are unchanged and still correct.
> **Same day, resolved: the connection node was wrong (`Node.Node2`) and is now
> `Node.Node1`, fixed in `VoxTelephony.kt` — see "Verified platform facts" below for
> the measured evidence.**

Status: **app-side login/session code written; server-side monitor route built and
flagged off; the one remaining hard blocker is a VoxEngine scenario change + live
verification, which is server/ops work, not this repo's.** Every claim is tagged
**[verified]** (checked against live Maven / live official docs / a file line / the
live DB / a read of the server repo) or **[inference]** (reasoning from verified
primitives).

## Scope

Wire the console (`me.kalfa.agentconsole`) to real Voximplant for the **human-agent
leg only** — outbound is already the worker's job via `/api/events/{id}/outreach-call`.
Do **not** add the ElevenLabs SDK to Android. Do **not** rebuild the ElevenLabs
bridge. The AI runs as an `ElevenLabs.AgentsClient` media node **inside** VoxEngine
(server-side, not this repo).

## Current state (grounded, not from docs)

| Piece | State |
|---|---|
| SDK `com.voximplant:android-sdk-bom` v3.2.0 (core + calls) in Gradle | present, used |
| Manifest permissions (RECORD_AUDIO, FGS, FGS_MICROPHONE, POST_NOTIFICATIONS, BLUETOOTH_CONNECT, …) | present. `MANAGE_OWN_CALLS`/`FOREGROUND_SERVICE_PHONE_CALL` deliberately absent — see AGENTS.md §3 |
| `telephony/vox/VoxClientManager.kt`, `VoxSdkAuthClient.kt`, `VoxTelephony.kt` (login) | **written, compiles, unit-tested (`VoxConfigTest`)** — connect → one-time-key → `POST /api/agents/sdk-auth` → login. Nothing calls `ensureLoggedIn` yet. |
| `telephony/vox/VoxCallSession.kt`, `VoxAudioController.kt` (call leg) | **written** — real `CallSession` over a Voximplant `Call`: mute/hold/DTMF/hangup, state mapping, audio-device selection. Never instantiated — no call site constructs one. |
| `telephony/CallForegroundService.kt`, `telephony/CallAudioPermissions.kt` | **written**; the permission prompt already fires on the live-calls screen. `start()`/`stop()` never called — nothing starts it because no leg exists yet. |
| `ConsoleConnectionService` in manifest | **commented out** with a TODO — correctly deferred, not a bug |
| `CallEngine.monitorCall`/`.takeoverCall`/`.startOutboundCall` | **throw `UnsupportedOperationException` on purpose** (`SupabaseImplementations.kt:892-906`) — replaced a prior `MockCallSession` fabrication, which was worse. UI gates the buttons to "בקרוב" so this is unreachable in release, not a live bug. |
| `console_agents.vox_username` | **populated** for the single agent (staff); exposed via `console_me.vox_username` |
| `POST /api/agents/sdk-auth` (backend) | **live** — verified by reading `beta/src/app/api/agents/sdk-auth/route.ts` and `beta/src/lib/data/console-sdk-auth.ts` directly, 2026-08-14 |
| `POST /api/calls/{id}/monitor` (backend) | **built** — route, `manage_voice` auth, `human_agent_call_legs` row creation, and the command-envelope POST to the live VoxEngine session all exist (`beta/src/app/api/calls/[callAttemptId]/monitor/route.ts`, `beta/src/lib/data/console-monitor.ts`). Returns `503` while `app_settings.monitor_enabled` is off. |
| VoxEngine `RSVPAgent` supervisor-conference handler | **not implemented** — the actual remaining blocker. Spec is fully written (`beta/docs/voice-agent/monitor-scenario-topology.md`), not yet coded into the live scenario, and cannot be until it's verified against real call audio (§5 there). |
| AI-command wire contract (`agent-command`) | **aligned and live** — deployed `agentCommandBodySchema` matches the app's flat `{command, text?}` |

## Verified platform facts

- **SDK version [verified — Gradle + CI build]:** `com.voximplant:android-sdk-bom:3.2.0`
  (+ `-core`, `-calls`). v3 is the modular, coroutine-friendly, actively-maintained line;
  the earlier "v3 is Beta, use v2 GA" call was correct when first made and has since
  gone stale — v3 left beta in 2026-07 and this app has been building against it since.
  Do not revert to the legacy monolithic `voximplant-sdk` v2 artifact.

- **One-time-key auth [verified — live code on both sides, 2026-08-14]:**
  ```
  Client.connect(ConnectOptions(VoxConfig.node))
     → Client.requestOneTimeKey(fullUsername)     // full username here, not short
     → onSuccess(key)                              // key TTL = 5 min
     → POST {one_time_key} + Bearer JWT → /api/agents/sdk-auth → {hash}
     → Client.loginWithOneTimeKey(fullUsername, hash)
  ```
  Hash the backend computes (`beta/src/lib/data/console-sdk-auth.ts`,
  `computeOneTimeKeyHash`, matching Voximplant's documented one-time-key protocol
  verbatim):
  `MD5( oneTimeKey + "|" + MD5( shortUser + ":voximplant.com:" + userPassword ) )`.
  **Critical nuance, confirmed identical on both sides:** `requestOneTimeKey`/
  `loginWithOneTimeKey` take the **full** username
  `user@application.account.voximplant.com`; the **inner** hash takes the **short**
  user (no `@app.acc`) and the **literal** realm string `voximplant.com` (not the
  account name). Getting either wrong = silent auth failure with no signal why. The
  Vox user password is **server-only** — stored in `console_agent_secrets`, never in
  the APK, `NEXT_PUBLIC_*`, logs, or the sdk-auth response (`{hash}` only).

- **Route path, corrected:** the endpoint is `POST /api/agents/sdk-auth` (with the
  `agents/` segment) — some earlier drafts of this handoff and of `AGENTS.md` said
  `/api/sdk-auth`. The app's own `VoxSdkAuthClient.kt` already calls the correct path;
  this note exists so nobody "fixes" it to match the old, wrong path.

- **Node — resolved 2026-08-14: `Node.Node1`.** `telephony/vox/VoxTelephony.kt`'s
  `VoxConfig.node` used to say `Node.Node2`, "confirmed" from the `kalfarsvp`
  account's Management API endpoint hostname (`api-node2.voximplant.com`). That
  reasoning was wrong, not just weak: the Management API
  (`beta/src/lib/voximplant/core.ts`, `MGMT_BASE`) is a different Voximplant
  service from the SDK's realtime connection, and its base URL is generic
  (`https://api.voximplant.com/platform_api`) — not node-specific at all. A
  hostname that happens to contain "node2" is not evidence about which node the
  Client SDK should connect to.
  The real evidence is `beta/src/lib/voximplant/core.ts`'s
  `MEASURED_CONNECTION_NODE`: a real browser one-time-key login (same account,
  same login protocol this class implements) **succeeded on `NODE_1` and no other
  node tried**, measured 2026-08-12. An empirical login beats a hostname
  inference. Fixed in code to `Node.Node1`, with the corrected provenance written
  into the class as a comment. **If `Client.connect()` ever fails, re-check this
  constant first** — a wrong node fails silently at `connect()`, not `login()`, so
  it presents as a network problem, not an auth one, and can burn an afternoon
  before anyone thinks to look here.

- **MAU billing [verified — Voximplant docs]:** client-SDK logins count against
  Voximplant MAU and fail with `LoginMauAccessDeniedError` over quota. **Do not log in
  on launch, and do not log in until there is an actual call to handle** — wire login
  to the moment a leg must be attached (once `CallEngine` is wired), never to app start
  or to "Ready".

## Monitor / takeover media topology [verified — server repo's own docs, cross-checked against live Voximplant docs 2026-07-22]

The governing constraint: a Call/media-unit **receives only one audio stream — a new
stream replaces the previous** (`typings/voxengine.d.ts:3651`, and `Call.sendMediaTo`
docs). To let one leg hear two sources they must be **mixed first**, and the mixer is
`VoxEngine.createConference()`.

- An `AgentsClient` **can** join a plain conference as a media source via
  `sendMediaTo(conf)`, but **cannot** be a conference endpoint via `Conference.add()` —
  that call needs a video-conference rule flag and is typed to accept only a `Call`,
  never an `AgentsClient`. **This repo's own AGENTS.md §4 used to describe the
  `Conference.add(EndpointParameters)` pattern; it was wrong and has been corrected
  there and here.**

- **Monitor (Conference mandatory)** — keep the existing 2-party bridge line untouched,
  add directional taps only:
  ```
  guest.sendMediaTo(conf); agent.sendMediaTo(conf); conf.sendMediaTo(human);
  // human sends to nobody → receive-only
  ```
  Both existing legs only *gain a send*; neither's single *receive* slot is touched, so
  the live conversation is undisturbed.

- **Takeover (no Conference, per the current default design)** — `agent.close()` (the
  existing `close_agent` command) then `VoxEngine.sendMediaBetween(guest, human)`. The
  server's topology doc (`monitor-scenario-topology.md`) instead defaults to
  **3-way Conference** for `takeover` (AI stays connected, human joins fully) as the
  guide-faithful, safer option, and flags this as an **open owner decision** pending
  live verification — do not assume either behavior in app-side UI copy until that's
  confirmed.

**Honest boundary, unchanged:** the ElevenLabs bridge itself is never rebuilt
(additive), **but monitor/takeover do require editing the production
`RSVPAgent.voxengine.js`** — add `require(Modules.Conference)`, a
`VoxEngine.callUser(vox_username)` human leg, the media taps, and `attach`/`detach`
cases in the existing `AppEvents.HttpRequest` switch (the server side already defines
these as `SESSION_COMMANDS` in `agent-console.ts`, alongside the four AI commands and
`call_end`). With `kalfatest` dead, that edit lands on the only scenario that dials
real guests — top risk; the server's own doc gates it behind a five-step live-audio
verification protocol before `monitor_enabled` flips to `true`. See
`beta/docs/voice-agent/monitor-scenario-topology.md` §5 for that protocol in full — it
is not this repo's to execute, but it is the condition this repo is waiting on.

## What's left, and who owns it

Ordered; the first is the one this repo can act on today.

1. **This repo:** wire `VoxClientManager`/`VoxCallSession` into `SupabaseCallEngineImpl`
   for the parts that don't need the conference flag — i.e. build and test the login
   path itself (`ensureLoggedIn`, answering a real inbound SDK call) against a
   standalone test scenario the server team provisions for that purpose, **not**
   against `RSVPAgent`/`OutCall`. The node question above is resolved (`Node.Node1`,
   already fixed in code) — no longer a blocker for this step.
2. **Server/ops, not this repo:** implement the `attach`/`detach` Conference topology in
   `RSVPAgent.voxengine.js`, deploy via `voxengine-ci upload` only (never a manual PATCH
   or hand-edited `agent_configs/*.json`), and run the five-step live-audio verification
   protocol before flipping `app_settings.monitor_enabled`.
3. **This repo, once 1 and 2 both land:** finish the monitor/takeover UI path — call
   `POST /api/calls/{id}/monitor`, watch `human_agent_call_legs`, start
   `CallForegroundService` around the leg, and un-gate the "בקרוב" buttons.
4. **Both, later:** self-managed Telecom (`ConsoleConnectionService`) once there's a
   real leg to hand it; native push wake-up (see `AGENTS.md` → "Push wake-up" — this
   needs new server-side FCM sending capability that does not exist yet, in addition to
   app-side FCM registration).

Until 2 lands, monitor/takeover cannot be verified end-to-end no matter how complete
the app side is — build and test what's testable now (login, the leg-answering path
against a dedicated test scenario), and treat the rest as blocked, not as work to route
around.
