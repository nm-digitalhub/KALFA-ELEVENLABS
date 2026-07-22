# AGENTS.md — KALFA Agent Console (native Android)

Call-center agent app for kalfa.me (Hebrew RSVP SaaS): agents supervise AI voice-RSVP calls, take inbound calls, run outbound campaigns, and watch a realtime dashboard.

## Hard rules

1. **Hebrew-first RTL.** All user-facing strings in Hebrew. `LayoutDirection.Rtl` everywhere; paddings/margins use `start`/`end` only — never `left`/`right`. Phone numbers, digits, times, and dates render LTR inside RTL text.
2. **Design system: Bento Grid.** Keep the established Bento Grid layout language — rounded cards on a clean canvas, flat design, no gradients. Brand: primary `#6C4CF1`, secondary `#6366F1`.
3. **Supabase is the ONLY backend.** supabase-kt (auth-kt, postgrest-kt, realtime-kt) version 3.x with Ktor 3.x. Package is `io.github.jan.supabase.auth` (not gotrue). No Firebase Auth / Firestore / any Firebase dependency — the single exception is `firebase-messaging` (FCM push only). Secrets never in code: `SUPABASE_URL` / `SUPABASE_ANON_KEY` reach BuildConfig via the secrets-gradle-plugin from the git-ignored `.env` file (see `.env.example`).
4. **Telephony lives behind `CallEngine` / `CallSession` / `AgentPresence`.** Do NOT rename or remove these interfaces — every swap happens behind them. **The Voximplant phase is now OPEN** (see "Phase status" below): adding `com.voximplant:voximplant-sdk:2.45.0` (v2 production GA — do NOT use v3, still Beta), audio handling, Telecom/ConnectionService and the manifest permissions of §3 is **APPROVED and expected**. The mock implementations stay in the tree as the offline/unconfigured fallback; they are not deleted.
5. **Architecture:** Kotlin + Jetpack Compose (Material 3), single-activity, sealed classes for call/campaign state, one immutable `UiState` per ViewModel exposed as `StateFlow`. Every screen has a `@Preview` with fake data. DI: `di/DependencyContainer` today; **Hilt is APPROVED to introduce now** — it was deferred to the Voximplant phase precisely because of the fake→real `CallEngine` swap, and that phase has arrived. Migrate incrementally; do not rewrite working screens to chase it.
6. **Allowed libraries:** Compose BOM, Hilt, supabase-kt, Ktor client (okhttp engine), kotlinx-serialization, Coil, firebase-messaging, **`com.voximplant:voximplant-sdk:2.45.0`**. Anything else — ask first.
7. **Source of truth, and its limits.** This file is the contract, but it is NOT automatically current: the production schema and the server routes change in the `kalfa.me/beta` repo, not here. Where this file and the live database disagree, **the database wins** — and fix this file in the same change. Never invent an API path or a column name; if you cannot verify one, ask.
8. **Identity:** base package and applicationId are `me.kalfa.agentconsole` — never `com.example` or AI-Studio-generated ids (Google Play rejects them). The template tests still under `com.example` are leftovers and may be deleted or rewritten.
9. **KSP versioning:** KSP now uses standalone versions (2.3.x line, decoupled from Kotlin). Do not force the legacy `<kotlin>-<ksp>` format.
10. **Functional code first.** No marketing READMEs or comments-as-docs. **Exception, explicitly approved:** the telephony layer (auth flow, Telecom/ConnectionService lifecycle, monitor/takeover media routing) MUST carry comments explaining *why* — it is the one part of this app whose failure modes are invisible in the code and expensive in production.

## Phase status (update this section when a phase opens or closes)

| Phase | State | Notes |
|---|---|---|
| Data layer (feed / campaigns / RSVP / analysis) | **DONE** | `data/SupabaseImplementations.kt` reads the real console views; realtime on `console_call_feed` |
| Server API routes | **PARTIAL** | Manual outreach is live: `POST /api/events/{eventId}/outreach-call`, including typed `409/already_reached` and the `call_dispatch_status` truth channel. Other routes remain independently gated until verified live. |
| Voximplant SDK + real telephony | **OPEN — approved** | Blocked only on `POST /api/sdk-auth` (below) for login |
| Hilt migration | **OPEN — approved** | |
| FCM push (`google-services.json`) | Not started | |

## Known defects (audited 2026-07-21 — fix before or alongside the SDK work)

Ordered by consequence, not by effort. Items 1–3 are user-visible today in Supabase mode.

1. **RSVP form silently discards its data.** `SupabaseRsvpRepository.saveRsvpResult` has an empty body ("read-only by design"), but `InCallScreen`'s "שמור ונתק" collects an answer, a guest count and notes, calls it, and hangs up. The agent believes the RSVP was saved; nothing was written and no error is shown. **Either wire it to a server route or disable the form** — silent loss is the worst of the three options.
2. **Every call is labelled `אורח` with a blank phone.** `DbConsoleCall.toDomain()` hardcodes `customerName = "אורח"` and `customerPhone = ""` because `console_call_feed` is deliberately PII-free. Those two fields are the primary and secondary lines of every live-call card, every history row and the in-call headline, so the lists cannot identify anyone. Decide deliberately: expose a name via a gated server route, or render an honest empty state — do not leave a blank gap that reads as a layout bug.
3. **A fabricated phone number is rendered as the callee.** `monitorCall`/`takeoverCall` inject the literal `"050-000-0000"` and `"אורח משיחה $callId"` into the session, and `InCallScreen` displays them as fact. Showing a fake number is worse than showing none.
4. **`SupabaseCallEngineImpl` returns a `MockCallSession`** from all three call paths. It fires the HTTP request, ignores the response, and hands the UI a fake session with a self-incrementing timer — so a duration ticks for a call that may not exist. A failed or absent route must produce a Hebrew error state, never a running timer.
5. **Mute / Hold / DTMF / Hangup are no-ops** (`MockCallSession`) — local booleans and a cancelled timer. `sendDtmf` is literally an empty body with a comment. They read as functional call controls; until a media path exists they must be visibly disabled.
6. **Monitor and takeover are ungated.** `LiveCallsScreen` receives no permission flag at all, and `EventDetailScreen`'s no-permission branch still passes `onMonitor`/`onTakeover`. `canViewCustomerData` is declared on `ConsoleMe` and never read anywhere. Client-side gating is advisory — the real boundary is RLS and the API routes — but the UI should not offer what it cannot authorise.
7. **`toggleCampaign` is a no-op** while the button looks live (and *does* flip state in mock mode, so it demos as working). Correctly unwired — campaign state is billing-coupled to SUMIT — but it must not look actionable.
8. **`"event_id":"default-event"` is hardcoded** in `startOutboundCall`, including from `EventDetailScreen` where the real event id is in scope. The JSON body is also built by string concatenation with an interpolated phone — no escaping.
9. **"השתק AI" sends `clear_buffer`**, which flushes the agent's buffer rather than muting it. Rename the control or change the command.
10. **`ExampleRobolectricTest` fails** — it asserts `app_name == "My Application"` while the string is `KALFA Agent Console`. All four test files are unmodified template scaffolding under `com.example`; `Greeting.kt` exists only to serve them. Nothing in the app is covered — notably `normalizePhone` and the `DbConsoleCall` state machine, both pure and trivially testable.
11. **`CampaignsScreen` is unreachable** — no route, no destination, no `composable<…>` entry. Only its `CampaignCard` is reused. Either wire it or delete it.
12. **A fresh checkout builds into full mock mode**, because `.env` is absent and `.env.example` holds placeholders. That mode shows fabricated Hebrew names, real-looking phone numbers and `https://example.com/record*.mp3` URLs, with auth bypassed entirely. Never demo from a fresh checkout without saying so. (`.env.example` also still carries a leftover `GEMINI_API_KEY` from AI Studio — remove it.)
13. **No i18n.** Every string is a Hebrew literal inline in Kotlin; `strings.xml` holds only `app_name`. English/French would mean rewriting every screen. Acceptable while Hebrew-only — record it as a known cost, not a surprise.

## Telephony integration spec (for the real implementation)

### 1. Authentication
- Use the one-time key flow on the `IClient` instance: connect → **`requestOneTimeKey(String username)`** (never `requestOneTimeLoginKey`, which is Web SDK only) → send the key to the server → log in with `loginWithOneTimeKey(fullUsername, hash)`.
- **The hash is computed on the SERVER, never in the app:** `MD5(oneTimeKey + "|" + MD5(user + ":voximplant.com:" + password))`, with a bare `user` (no suffix). The Voximplant password must never reach the APK — this is the whole reason the flow exists.
- The app therefore cannot connect to Voximplant at all until `POST /api/sdk-auth` exists server-side. That route is the single hard blocker for real telephony.
- The agent's Voximplant username is already exposed to the app: **`console_me.vox_username`** (nullable — null means this agent has not been provisioned a Vox user yet, which is a legitimate state to render, not an error).
- **Billing note:** client-SDK logins count toward Voximplant's **Monthly Active Users** quota and can fail with `LoginMauAccessDeniedError`. Do not log in speculatively on app start — log in when the agent goes Ready, and surface that error distinctly from a network failure.

### 2. Audio & Hardware (v2 SDK)
- Obtain **`IAudioDeviceManager`** via **`Voximplant.getAudioDeviceManager()`**. Key methods: `selectAudioDevice(...)`, `getActiveDevice()`, `setTelecomConnection(...)`. There is no class named `AudioDeviceManager` in v2.

### 3. Android Telecom & Permissions
- Complete manifest permissions required for self-managed VoIP: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`, `MANAGE_OWN_CALLS`, `USE_FULL_SCREEN_INTENT`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, and `BLUETOOTH_CONNECT` (for Android 12+ routing).
- Declare ConnectionService with `BIND_TELECOM_CONNECTION_SERVICE` permission and `android.telecom.ConnectionService` intent-filter. Register self-managed `PhoneAccount` with `CAPABILITY_SELF_MANAGED`.

### 4. Live Monitor & Takeover (Conference routing)
- ElevenLabs **`AgentsClient`** (from `require(Modules.ElevenLabs)`) is a media stream node. It is **not** an Endpoint and cannot be added directly to `Conference.add()`.
- **Silent Monitoring Pattern:** Bridge the PSTN/SDK call and ElevenLabs `AgentsClient` via `sendMediaBetween(call, agentsClient)`. Add these as separate Call Endpoints to a Conference using `Conference.add(EndpointParameters)` with direction `SEND` / `RECEIVE` (use `RECEIVE` direction on the agent call endpoint for silent monitoring).
- **Takeover Pattern:** To take over, stop the media exchange with the ElevenLabs agent via `stopMediaTo/clearMediaBuffer` and change the agent's Conference Endpoint direction to bidirectional (both transmit and receive), bridging them fully to the PSTN caller.

### 5. SmartQueue (ACD v2)
- Live queues and agent states (Ready, DND, etc.) utilize **`require(Modules.SmartQueue)`**, which supersedes the legacy ACD v1 module.

## Data model (REAL production schema — kalfa-event-magic project; names are fixed)

Realtime tables (postgresChangeFlow; always start collecting BEFORE channel.subscribe()):
- `agent_status(agent_id, status: ready|not_ready|dnd|in_call, updated_at)` — agent updates own row only
- `console_call_feed(call_attempt_id, event_id, campaign_id, direction, kind, status, handled_by: ai|agent, agent_id, rsvp_digit, finish_reason, call_duration_sec, callback_iso, created_at, updated_at, takeover_claimed_at, takeover_request_id, participation_state)` — trigger-fed from `call_attempts`, deliberately PII-free (no phone/transcript/recording). Live `status` values: `in_progress|completed|no_answer|cancelled|no_response`.
  - **The last three columns are the takeover coordination fields and the app does not read them yet** (`DbConsoleCall` stops at `updated_at`). They exist so two agents cannot claim the same call: claim by `takeover_request_id`, observe `takeover_claimed_at`. Wire them when takeover goes real.
- `call_dispatch_status(dispatch_id, event_id, contact_id, call_attempt_id, status, reason, created_at, updated_at)` — one row per manual dispatch request. The route inserts `accepted` before returning 202; the worker settles it. Track by the 202 `dispatch_id`; on `dispatched`, `call_attempt_id` links to `console_call_feed`.

Read-only VIEWS (fetch/poll only — Supabase Realtime does not fire on views):
- `console_campaigns(id, event_id, status: approved|closed, enabled, start_at, close_at, max_contacts)` — billing columns intentionally hidden
- `console_rsvp_results(id, event_id, guest_id, guest_name, attending: boolean, adults, kids, note, created_at)` — note `attending` is a BOOLEAN, not an enum
- `console_campaign_targets(id, event_id, campaign_id, contact_id, status, current_step_index, next_run_at, reached_at, reached_channel, stop_reason, guest_name, phone)` — `phone` is empty unless the agent holds the `view_customer_data` platform permission; the view gates it in the database, so never treat a blank phone as a bug
- `console_me(user_id, display_name, vox_username, platform_role, platform_rank, permissions)` — the agent's own identity row, and the source of `vox_username` for SDK login
- `console_event_guests(guest_id, event_id, guest_name, dialable, phone, rsvp_status, has_active_campaign, reached_at, callback_scheduled_at, can_start_outreach_call, call_block_reason)` — manual-dial projection. Enable only when `dialable && has_active_campaign && can_start_outreach_call == true`; nullable booleans are fail-closed.

Write ownership (hard rule): the console READS. RSVP outcomes are written by the ElevenLabs client-tools pipeline; campaign state is billing-coupled and changed only via the beta.kalfa.me API; `outreach_state` belongs to the orchestrator. The only direct writes allowed: own `agent_status` row, and `handled_by`/`agent_id` on `console_call_feed` at takeover.

Gate: console access requires membership in `console_agents` **AND** platform staff membership — `is_console_agent()` is `is_staff() AND exists(console_agents…)` since 2026-07-20, and `console_agents.user_id` is a foreign key to `platform_staff(user_id)` with `ON DELETE CASCADE`. Consequence for the app: an agent removed from staff loses console access instantly and silently. Treat a sudden empty feed plus RLS denials as "access revoked", not as a network fault.

## API contract (the ONLY external HTTP calls; JWT = Supabase session token)

Base `https://beta.kalfa.me`, header `Authorization: Bearer <jwt>`. Route availability is tracked per entry; never infer that one deployed route makes the others live. Code against this contract exactly, surface Hebrew error states, and never invent a path. If a route you need is missing from this list, it must be added here and agreed with the server side *before* it is called from code.

- `POST /api/events/{eventId}/outreach-call` `{"guest_id":"uuid"}` → `202 {"status":"accepted","dispatch_id":"uuid","event_id":"uuid"}`. A reached contact returns `409 {"code":"already_reached"}` and creates no job. After 202, `call_dispatch_status` is the truth channel; never claim the call is queued before the 202.

- `POST /api/sdk-auth` `{"one_time_key":"...","username":"..."}` → `{"hash":"..."}` — server-side Voximplant login hash (§1). **Hard blocker for all real telephony.**
- `POST /api/agents/status` `{"status":"ready|not_ready|dnd"}`
- `POST /api/calls/outbound` `{"phone":"+9725XXXXXXXX","event_id":"uuid"}` → `{"call_id":"uuid"}`
- `POST /api/calls/{id}/monitor` `{"mode":"monitor|takeover"}`
- `POST /api/calls/{id}/agent-command` — signalling to the ElevenLabs `AgentsClient` of a live AI call. **FLAT body, not nested under `payload`** — this is the shape the app already sends and the server schema now matches it exactly:
  - `{"command":"contextual_update","text":"…"}` → `agent.contextualUpdate` — non-interrupting whisper
  - `{"command":"user_message","text":"…"}` → `agent.userMessage` — injects a user turn; **interrupts**
  - `{"command":"clear_buffer"}` → `agent.clearMediaBuffer` — one-shot barge-in
  - `{"command":"close_agent"}` → `agent.close` — closes the AI leg, the call stays up
  - `text` is trimmed, non-empty, max 1000. Every command is a strict object — an extra field is a 400, not an ignored key. Returns 409 when the call is no longer live.
- `POST /api/calls/{id}/end` `{}` — ends the **whole call**. Deliberately NOT an agent-command: the four above act on the AI leg, this one hangs up on the guest, and putting them in one enum makes a mis-tap end a live conversation. Not yet called by the app.
- `POST /api/campaigns/{id}/start` / `POST /api/campaigns/{id}/pause` `{}`

**Contract drift, and how it was caught (2026-07-21).** `agent-command` was being called from `SupabaseImplementations.kt` while absent from this list. Worse, the two sides had independently invented different shapes for it: the server accepted `agent_context_update` with a nested `payload`, the app sends `contextual_update` flat. Against `strictObject` that is a 400 on *every* command — and since that POST is one of the few whose status the app actually checks, the agent would have seen "פקודת AI נכשלה (400)" on every press. It was caught by diffing the wire formats, not by either side re-reading its own docs.

Resolved in the app's favour: the names above are the deployed app's, `user_message` was kept because `AgentsClient.userMessage()` genuinely exists, and `call_end` moved to its own `/end` route. **The server schema (`src/lib/validation/agent-console.ts` in `kalfa.me/beta`) and this section are now byte-identical in meaning — change them together or not at all.**

Still open: `campaigns/{id}/start|pause` is listed here but `SupabaseCampaignRepository.toggleCampaign` is an empty no-op. One of the two must move — wire it, or delete the route from this contract.

## Domain facts (fixed — do not redesign)

- Telephony platform: Voximplant (apps `kalfa-rsvp` prod / `kalfatest` sandbox). AI voice brain: ElevenLabs Agents (Hebrew agent) bridged inside VoxEngine.
- The AI already persists results via its own tools (`save_rsvp`, `mark_dnc`, `notify_owner`, `schedule_callback`) — the app READS results; it never writes RSVP outcomes on behalf of the AI.
- RSVP answers: `attending | declined | maybe | callback`, with adult + children counts.
- An AI call is takeover-capable: monitor (listen-only) / takeover (AI dropped, human bridged) — in this codebase these are `CallEngine` interface calls only.

## Hebrew UI conventions

- Dates `DD.MM.YYYY`; times 24h `HH:mm`; phones displayed `05X-XXX-XXXX`.
- Status labels: זמין / לא זמין / נא לא להפריע / בשיחה. Answers: מגיע / לא מגיע / אולי / חזרו אליי.
- Microcopy short and directive; natural Hebrew over anglicisms.
