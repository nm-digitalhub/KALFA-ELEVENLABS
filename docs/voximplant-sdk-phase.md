# Voximplant SDK phase — pre-implementation review & handoff

Status: **design locked, implementation gated on backend.** This documents the
verified state so the human-agent SDK leg (login → monitor → takeover) can be built
against something testable, rather than blind. Every claim is tagged **[verified]**
(checked against live Maven / live official docs / a file line / the live DB) or
**[inference]** (reasoning from verified primitives).

## Scope

Wire the console (`me.kalfa.agentconsole`) to real Voximplant for the **human-agent
leg only** — outbound is already the worker's job via `/api/events/{id}/outreach-call`.
Do **not** add the ElevenLabs SDK to Android. Do **not** rebuild the ElevenLabs
bridge. The AI runs as an `ElevenLabs.AgentsClient` media node **inside** VoxEngine.

## Current state (grounded, not from docs)

| Piece | State |
|---|---|
| SDK `com.voximplant:voximplant-sdk:2.45.0` in Gradle | present, **unused** (no Vox code) — version is current, see below |
| Manifest permissions (RECORD_AUDIO, MANAGE_OWN_CALLS, FGS_PHONE_CALL, …) | present |
| `ConsoleConnectionService` in manifest | **commented out** with a TODO — correctly deferred, not a bug |
| `console_agents.vox_username` | **populated** for the single agent (staff); exposed via `console_me.vox_username` |
| `/api/sdk-auth` (backend) | **does not exist** — the one hard blocker for any Voximplant login |
| Real Vox code in app | **none** — telephony is still `MockCallSession` |
| monitor / takeover backend routes | none |
| AI-command wire contract | **already aligned** — deployed `agentCommandBodySchema` matches the app's flat `{command, text?}` |

## Verified platform facts

- **SDK version [verified — live `maven-metadata.xml` + CI build]:** latest stable v2 is
  `2.45.0` (`<latest>`/`<release>`; the list runs 2.42.0 → 2.45.0). v3 (`android-sdk-*`)
  is beta-only. Keep `com.voximplant:voximplant-sdk:2.45.0`. (A prior automated review
  claimed "2.41.2, 2.45.0 doesn't exist" — that was a stale search-API artifact;
  disproved by both maven-metadata and the passing CI build on 2.45.0.)

- **One-time-key auth [verified — live IClient reference + one-time-key guide]:**
  ```
  connect(node) → requestOneTimeKey(fullUsername)
     → onOneTimeKeyGenerated(key)        // key TTL = 5 min
     → POST {one_time_key, username} to /api/sdk-auth → {hash}
     → loginWithOneTimeKey(fullUsername, hash)
  ```
  Hash the backend must compute:
  `MD5( oneTimeKey + "|" + MD5( bareUser + ":voximplant.com:" + userPassword ) )`.
  **Critical nuance:** `requestOneTimeKey`/`loginWithOneTimeKey` take the **full**
  username `user@application.account.voximplant.com`; the **inner** hash takes the
  **bare** user (no `@app.acc`). Getting this wrong = silent auth failure. The Vox user
  password is **server-only** — never in the APK, `NEXT_PUBLIC_*`, logs, or the response
  (the route returns only `{hash}`).

- **MAU billing [verified — doc note]:** client-SDK logins count against Voximplant MAU
  and fail with `LoginMauAccessDeniedError` over quota. **Do not log in on launch, and do
  not log in until there is an actual call to handle** (i.e. wire login to Ready only
  once monitor/takeover exist — logging in earlier burns quota for zero feature).

## Monitor / takeover media topology [verified primitives / inference topology]

The governing constraint: a Call/media-unit **receives only one audio stream — a new
stream replaces the previous** (`typings/voxengine.d.ts:3651`). To let one leg hear two
sources they must be **mixed first**, and the mixer is a `Conference`.

- An `AgentsClient` **can** join a plain `createConference` as a media source via
  `agentsClient.sendMediaTo(conf)`, but **cannot** be a conference endpoint —
  `EndpointParameters.call` is typed `Call` (`typings:6473`) and `conf.add()` needs the
  video-conference rule flag. So AGENTS.md §3's `Conference.add(EndpointParameters)`
  pattern is **wrong**; use plain audio conference + `sendMediaTo`.

- **Monitor (Conference mandatory)** — keep the existing 2-party bridge line untouched,
  add directional taps only:
  ```
  guest.sendMediaTo(conf); agent.sendMediaTo(conf); conf.sendMediaTo(human);
  // human sends to nobody → receive-only
  ```
  Both existing legs only *gain a send*; neither's single *receive* slot is touched, so
  the live conversation is undisturbed.

- **Takeover (no Conference)** — `agent.close()` (or `agent.stopMediaTo(guest)`) then
  `VoxEngine.sendMediaBetween(guest, human)`.

**Honest boundary:** the ElevenLabs bridge itself is never rebuilt (additive), **but
monitor/takeover do require editing the production `RSVPAgent.voxengine.js`** — add
`require(Modules.Conference)`, a `VoxEngine.callUser(vox_username)` human leg, the media
taps, and `monitor`/`takeover` cases in the existing `AppEvents.HttpRequest` switch. With
`kalfatest` dead, that edit lands on the only scenario that dials real guests — top risk;
gate behind a flag and verify with independently captured per-leg audio.

## Backend prerequisites (handoff — not this repo's code)

Ordered; the first is the hard blocker. These are backend/ops and must not be built by
two sessions against the one live DB at once.

1. **`POST /api/sdk-auth`** — auth: agent Supabase JWT + `is_console_agent()`. Body
   `{ one_time_key: string, username: string }` (full username). Looks up the agent's
   `vox_username`, loads the per-agent Vox password (server-only), returns
   `{ hash }` = the MD5 chain above. Never returns/logs the password.
2. **Provision a Voximplant user** inside application `kalfa-rsvp` (same app the agent
   places/receives calls in — `callUser` requires same-app association). Store its
   password server-side; `console_agents.vox_username` is already populated for the one
   agent, so mainly the password store + a provisioning path for future agents.
3. **Standalone `AgentSdkTest` scenario + its own routing rule** — bridges the SDK leg to
   a controlled test destination (echo / fixed test number) for two-way audio, so the
   app can be verified **without** touching `RSVPAgent`/`OutCall`/the bridge.
4. *(monitor/takeover phase)* the additive `RSVPAgent` edits above.

## App-side design (this repo — build against #1–#3 once they exist)

- `telephony/vox/VoxClientManager` — single `IClient`, connect/reconnect + login state,
  incoming-call listener (the human leg arrives as an incoming SDK call for monitor/takeover).
- `telephony/vox/VoxAuthenticator` — the flow above; hash comes from `/api/sdk-auth`,
  never computed on device; full username from `console_me.vox_username`.
- `VoxCallSession : CallSession` — wraps `ICall` (`mute→sendAudio`, `hold`, `sendDtmf→sendTone`,
  `hangup`); replaces `MockCallSession` on the real paths.
- `VoxAudioController` — `Voximplant.getAudioDeviceManager()`.
- `CallForegroundService` + reinstated `ConsoleConnectionService` (uncomment once the
  call flow exists and Telecom is actually used).
- DI: select the Vox engine when configured; **fail-closed in release** (mock only under
  `BuildConfig.DEBUG`) — part of the Step-8 hardening pass, done consistently across all
  repos, not just the engine.
- **Login is wired to Ready only when monitor/takeover exist** (MAU).

## Sequence & ownership

1. Backend/ops (owner routes to the backend session): #1–#3 above.
2. App (this session): `VoxClientManager` + `VoxAuthenticator` verified against the
   `AgentSdkTest` scenario (login + two-way audio) — the first testable increment.
3. Backend + app together, flagged: monitor, then takeover, with per-leg audio verification.

Until #1 lands there is no sound app code to write for this phase — the login layer has
nothing to authenticate against and would only burn MAU. This doc is the gate.
