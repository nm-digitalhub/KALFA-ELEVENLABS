# KALFA Agent Console

Native Android app for kalfa.me's call-center agents: a realtime dashboard of AI-run RSVP calls, live AI-supervision controls (whisper/mute/close), manual outbound dialing per guest, campaign run/pause, and call history — with a real (if not yet fully wired) path to native Voximplant telephony so an agent stops depending on a browser tab staying open.

**Why this app exists:** the only agent client before this was a browser PWA. It only receives calls while a tab is open and its 60-second heartbeat keeps flowing. Measured 2026-08-13: the one provisioned agent sat `status='ready'` with a heartbeat 661 minutes stale, and every inbound call was answered "no agent available" because the laptop was closed.

**Read this before assuming the fix is close: a foreground service and push wake-up are two different fixes, and neither is finished here.** A foreground service (built, not yet wired — see below) keeps the app **reachable while it's running**. It does nothing for the case that actually happened on 2026-08-13 — the app not running at all. That needs push, to **wake** it. Voximplant's own platform delivers that push (verified against live docs, 2026-08-14) — this repo's unused `firebase-messaging` Gradle dependency is the real sink for it, not a false promise — but the app-side registration is not built yet, and **push alone would not have prevented the 2026-08-13 incident anyway**: the server excludes an agent with a stale heartbeat before it ever tries to call them, so no push would have been sent. See "Integration with `beta`" below and `AGENTS.md` → "Push wake-up" for both halves of that.

This repo is a **separate product** from `kalfa.me/beta` (the Next.js web app it talks to). It shares no code or deploy pipeline with it — only an HTTP contract and a Supabase project. See `AGENTS.md` for the full contract and every implementation decision; this file is the map.

---

## 30-second status check

| Capability | Status | Evidence |
|---|---|---|
| Login (Supabase email/password, RLS-gated to console agents) | **Live** | `ui/screens/LoginScreen.kt`, `AuthGate` |
| Realtime call feed, campaigns, RSVP results, live captions | **Live** | `data/SupabaseImplementations.kt`, Realtime on `console_call_feed`/`agent_status`, Broadcast for captions |
| Agent presence (ready/not-ready/dnd) | **Live** | `SupabaseCallEngineImpl.setStatus` → `POST /api/agents/status` |
| AI call supervision (whisper / barge-in / close AI leg) | **Live** | `sendAgentCommand` → `POST /api/calls/{id}/agent-command` |
| End a live call | **Live** | `endCall` → `POST /api/calls/{id}/end` |
| Manual outbound dial to an existing guest | **Live** | `enqueueOutboundCall` → `POST /api/events/{id}/outreach-call`, handles `already_reached` |
| Campaign activate/pause | **Live** | `toggleCampaign` → `POST /api/campaigns/{id}/status` |
| Voximplant v3 SDK login + call-session code | **Built, not wired** | `telephony/vox/*` — compiles, unit-tested, nothing calls it |
| Foreground service for an active call | **Built, not started** | `telephony/CallForegroundService` — `start()`/`stop()` never called |
| Monitor / listen-in on a live AI call | **Blocked server-side, gated off** | server route exists, returns `503` until a VoxEngine scenario change ships and is verified live |
| Takeover a live AI call | **Same as monitor** | same gate |
| Free-form outbound dial (arbitrary number) | **Deliberately not supported** | doesn't fit the guest-scoped enqueue contract; UI shows "בקרוב" |
| Self-managed Telecom / `ConnectionService` | **Not started** | class doesn't exist; manifest entry commented out |
| Push wake-up when the app isn't running | **Architecture resolved, app-side not built** | Voximplant delivers the push itself (no `beta` backend work needed) — client-side FCM registration + a silent-login path are the remaining work. **Even once built, does not by itself fix the 13.8 incident** — see below |

If a row says "Live", the button in the app does the real thing today. Everything else is either honestly disabled in the UI ("בקרוב") or structurally unreachable in a release build (a `throw` stands in its place). Full detail, including exact line numbers and what changed since the last audit, is in `AGENTS.md`'s "Known state" and "Push wake-up" sections.

---

## What this app already does

The data and control-plane half of this app is **real and complete**, not a demo:

- **Auth**: Supabase email/password login gated behind `console_agents` + platform-staff membership (RLS). Mock mode (no `.env`) bypasses this entirely for local demos — see "Build & run".
- **Live dashboard**: realtime call feed (`console_call_feed`), agent presence, campaign state, RSVP results, per-call ElevenLabs analysis, and live captions over Supabase Broadcast — all read through PostgREST/Realtime with the agent's own JWT, no server route needed for any of it.
- **AI supervision that doesn't need a phone leg at all**: whispering context to the AI mid-call, one-shot barge-in, and closing the AI leg are wired end-to-end against a live server route and need no Voximplant SDK, no login, no MAU cost.
- **Manual outbound dialing**: pick a dialable guest inside an event, the app enqueues a real AI call through the same worker the web app uses; the UI is honest about async state (`"הבקשה נקלטה"` → watches `call_dispatch_status`), and a guest who was already reached shows a disabled button with a fixed explanation, not a retry.
- **Campaign run control**: activate (from `paused` only) and pause, matching the server's intentionally narrow scope — first activation stays with the event owner on the web.
- **Honest failure states throughout**: a typed `AppFailure`/`AppResult`/`UiMessage` system (see `ui/message/`) replaced ad-hoc error strings across the whole data layer in the last several commits; stale-but-cached data shows a dismissible warning with retry, not a silent gap.

None of that needs the Voximplant SDK, and none of it should be rebuilt.

## What's built but not wired (the concrete next step)

A real Voximplant v3 SDK layer already exists under `telephony/`:

- `telephony/vox/VoxClientManager.kt` — connect, one-time-key login (MAU-safe: never logs in speculatively), login-state `StateFlow`, an `onIncomingCall` hook for the human-agent leg.
- `telephony/vox/VoxSdkAuthClient.kt` — talks to the live `POST /api/agents/sdk-auth`.
- `telephony/vox/VoxCallSession.kt` — a real `CallSession` backed by a Voximplant `Call`: mute, hold, DTMF, hangup, state mapping.
- `telephony/vox/VoxAudioController.kt` — audio-route selection (earpiece/speaker/bluetooth).
- `telephony/CallForegroundService.kt` + `telephony/CallAudioPermissions.kt` — a microphone-typed foreground service and the RECORD_AUDIO/POST_NOTIFICATIONS permission flow, already composed on the live-calls screen.

**None of it is connected to `CallEngine`.** `SupabaseCallEngineImpl.monitorCall`/`.takeoverCall`/`.startOutboundCall` all throw on purpose — the previous behavior (returning a fabricated `MockCallSession`) was worse, because it showed a fake in-call screen for a call that wasn't real. The UI already gates the matching buttons to a "בקרוב" (coming soon) notice, so this is a clean, honest stopping point, not a bug to work around.

Wiring it is the highest-value next PR in this repo — see `AGENTS.md` → "Telephony integration spec" for the exact sequence, and why monitor/takeover specifically can't go further than this even once wired (the server-side conference flag is off — see next section). The connection-node question that used to block trusting the login path (`Node.Node2` vs `Node.Node1`) is resolved — fixed in code to `Node.Node1`, backed by a measured successful login on that node (`AGENTS.md` → "Telephony integration spec" §1 has the evidence).

## What's still missing

1. **Monitor/takeover, server-side.** The route (`POST /api/calls/{id}/monitor`), its authorization, and the leg-tracking table are built and live — but gated behind `app_settings.monitor_enabled` (default off) until the `RSVPAgent` VoxEngine scenario implements the supervisor-conference topology and it's verified on a real call. That work is out of this repo's boundary; build and test the app side against a scenario the server team stands up for that purpose, never against the production `RSVPAgent`/`OutCall` rule.
2. **Self-managed Telecom.** No `ConnectionService` exists yet; deferred until there's a real leg to hand it. Needed for a `phoneCall`-typed foreground service (the current one is `microphone`-typed, which is enough for v1).
3. **Push wake-up — architecture resolved, app-side work remains, and it's not the whole fix.** Voximplant's own platform sends the push when the scenario calls `callUser` (verified against live docs, 2026-08-14) — **no backend work needed in `beta`**, a self-hosted FCM sender was specified and deliberately ruled out for this reason (full history in `AGENTS.md` → "Push wake-up"). What's still needed, all app-side: standard Firebase client registration (`google-services.json` + the Google Services Gradle plugin — deliberately stripped from this repo early on, needs re-adding, not just a dropped-in file), a `FirebaseMessagingService` forwarding into `Client.handlePushNotification`, and a persisted-token silent-login path for the no-human-present wake case. One line server-side (`require(Modules.PushService);` in the scenario) is the only `beta` change, and it's ops work, not app work. **Even fully built, this does not by itself fix the 2026-08-13 incident**: the server excludes a stale-heartbeat agent from the call ring before it ever tries to reach them, so no push would have been attempted. Closing that is a routability policy decision on the server side, not an app-side gap — see "Integration with `beta`" below.
4. **`CampaignsScreen.kt`** has no navigation route — unreachable dead code except for its reused `CampaignCard`.
5. **No i18n** — every string is a Hebrew literal. Fine while Hebrew-only; a recorded cost for the day English/French is needed.

## Build & run

Prerequisites: [Android Studio](https://developer.android.com/studio), JDK 17.

1. Open this directory in Android Studio and let it sync.
2. Create `.env` in the project root (see `.env.example`) with real `SUPABASE_URL` / `SUPABASE_ANON_KEY`. **Without it, the app builds and runs in full mock mode** — fabricated Hebrew names, fake phone numbers, auth bypassed entirely. That's useful for UI work; never mistake it for a real build.
3. Run on an emulator or physical device.

CI (`.github/workflows/android-build.yml`) builds debug APK, release APK, and release AAB on every push to `main` and every PR, using real Supabase secrets when the repo has them configured (otherwise mock mode, with a loud warning).

**The release AAB this CI produces cannot be uploaded to Play, in its current form, ever — not "not yet," a structural fact about how the workflow is written.** It never sets `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD`, so `app/build.gradle.kts` falls back to generating a brand-new `debug.keystore` inside every single CI run and signs the release build with it. Play requires the same signing key across all releases of an app; a keystore regenerated from scratch each run is a different key every time, so even a first upload would need to be followed by an upload with a different, unrelated key next time. This is fine for internal testing (the APK installs and runs) and not a code bug to fix — it needs a real upload keystore provisioned once and stored as `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` repo secrets before any Play distribution, internal testing track included.

## Integration with `beta` — the seam, named precisely

This app is a client of one server, `kalfa.me/beta`, over HTTP + the shared Supabase project — nothing more entangled than that (no shared code, no shared deploy). The coupling is real, though, so here is exactly where it runs today and where it doesn't yet.

**Base `https://beta.kalfa.me`, `Authorization: Bearer <supabase-jwt>`.** The authoritative, actively-maintained copy of the full contract lives in the **server repo**: `beta/docs/voice-agent/app-integration-reference.md` (and `beta/src/lib/validation/agent-console.ts` for the exact wire schemas) — read those, don't infer the contract from this app's HTTP calls alone. `AGENTS.md` in this repo keeps a synced summary with the same route list; if the two disagree, the server side is authoritative and both get fixed together. `beta/docs/agent-console-api-contract.md` is a superseded, dated snapshot (2026-07-21) kept for history only — several routes it lists as missing (`sdk-auth`, `monitor`) now exist.

**What the app consumes today** (all live server-side, all called from this app): `POST /api/agents/status` (presence), `POST /api/calls/{id}/agent-command` (AI whisper/barge-in/close), `POST /api/calls/{id}/end`, `POST /api/events/{id}/outreach-call` (enqueue outbound), `POST /api/campaigns/{id}/status` (run control) — plus direct PostgREST/Realtime reads of the `console_*` views and tables under RLS.

**What exists server-side but this app doesn't call yet:**
- `POST /api/agents/sdk-auth` — live, needed the moment the Voximplant login wiring (see `AGENTS.md` → "Telephony integration spec") starts.
- `POST /api/agents/shift` — live since 2026-08-12, the standing "on shift" signal; not called yet, and it's the natural trigger for both auto-connecting the SDK on launch and being included in the newer off-duty push-wake audience.
- `POST /api/calls/{id}/monitor` — built, authorized, and leg-tracked server-side, but answers `503` until `app_settings.monitor_enabled` is flipped (blocked on a VoxEngine scenario change + live verification, entirely server/ops work — see `docs/voximplant-sdk-phase.md`).

**Native push wake-up — resolved differently than the app/server split above.** This is not a `beta` gap: Voximplant's own platform sends the push when `callUser` is invoked in the scenario (verified against live Voximplant docs, 2026-08-14), so `beta` needs no new table, route, dependency, or secret — a self-hosted FCM sender was fully specified and then deliberately ruled out for duplicating what the platform already does (history kept in `AGENTS.md` → "Push wake-up", not repeated here). `beta`'s existing wake mechanism (`notifyRoutableAgentsOfInboundCall` / `notifyOffDutyShiftAgentsOfInboundCall`, Web Push/VAPID to `push_subscriptions`) stays exactly what it is — the browser path — and was never in scope for the native one. The remaining work is entirely in this app (FCM registration, `FirebaseMessagingService`, silent login) plus one line in the VoxEngine scenario (server/ops, not `beta` app code).

**And a caveat bigger than the architecture question:** push wake-up, however well built, does not by itself fix the 2026-08-13 incident. `route-inbound` only rings agents already in `ring_order`, computed from the same fresh-heartbeat gate as everywhere else in this contract (`findRoutableAgentVoxUsernames`) — a stale-heartbeat agent is excluded before `callUser` is ever attempted, so no push would ever have been sent.

**The server side of that was decided and shipped on 2026-08-14** (`beta` commit `8af24ab`): rather than loosening the freshness gate, the one-shot RETRY wave (`route-inbound-retry`, fired after the primary ring is exhausted) now also returns agents whose `console_agent_shift` row is active and fresh, with no heartbeat requirement — and ringing them is what triggers Voximplant's push. The primary ring is unchanged, so this is a second chance, not a redefinition of availability. **Two consequences for this app:** it must call `POST /api/agents/shift` (it currently doesn't) or it will never be in that audience at all; and the whole cold-start chain has to complete inside `RING_RETRY_WINDOW_MS` (15s), which is tighter than the primary window and has never been measured on a real device. See `AGENTS.md` → "Push wake-up" for the full detail.

## Where to look next

- `AGENTS.md` — the full contract: every hard rule, the current known state of every screen and defect, the exact telephony wiring sequence, the push-wake-up gap, and the API contract with line-level evidence.
- `docs/voximplant-sdk-phase.md` — narrower and deeper: the Voximplant SDK/monitor-takeover handoff specifically, including the connection-node evidence and the verification protocol that has to pass before `monitor_enabled` flips on.
