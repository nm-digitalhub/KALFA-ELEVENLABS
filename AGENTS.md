# AGENTS.md — KALFA Agent Console (native Android)

Call-center agent app for kalfa.me (Hebrew RSVP SaaS): agents supervise AI voice-RSVP calls, take inbound calls, run outbound campaigns, and watch a realtime dashboard.

## Hard rules

1. **Hebrew-first RTL.** All user-facing strings in Hebrew. `LayoutDirection.Rtl` everywhere; paddings/margins use `start`/`end` only — never `left`/`right`. Phone numbers, digits, times, and dates render LTR inside RTL text.
2. **Design system: Bento Grid.** Keep the established Bento Grid layout language — rounded cards on a clean canvas, flat design, no gradients. Brand: primary `#6C4CF1`, secondary `#6366F1`.
3. **Supabase is the ONLY backend.** supabase-kt (auth-kt, postgrest-kt, realtime-kt) version 3.x with Ktor 3.x. Package is `io.github.jan.supabase.auth` (not gotrue). No Firebase Auth / Firestore / any Firebase dependency — the single exception is `firebase-messaging` (FCM push only). Secrets never in code: `SUPABASE_URL` / `SUPABASE_ANON_KEY` reach BuildConfig via the secrets-gradle-plugin from the git-ignored `.env` file (see `.env.example`).
4. **Telephony stays behind clean interfaces:** `CallEngine`, `CallSession`, `AgentPresence` — fake/mock implementations only. Do NOT implement WebRTC or audio logic, do NOT add the Voximplant dependency, do NOT rename or remove these interfaces. The real implementation uses **`com.voximplant:voximplant-sdk:2.45.0`** (v2 production GA, do not use v3 which remains Beta) and is added later outside this tool behind the same interfaces via a single Hilt binding swap.
5. **Architecture:** Kotlin + Jetpack Compose (Material 3), single-activity, manual DI via `di/DependencyContainer` (Hilt deliberately deferred to the Voximplant phase, where the fake→real CallEngine swap justifies it), sealed classes for call/campaign state, one immutable `UiState` per ViewModel exposed as `StateFlow`. Every screen has a `@Preview` with fake data.
6. **Allowed libraries only:** Compose BOM, Hilt, supabase-kt, Ktor client (okhttp engine), kotlinx-serialization, Coil, firebase-messaging. Anything else — ask first.
7. **Source of truth.** Everything needed is in this file; if your environment cannot reach docs/repos, ask — never invent. Verify version claims against Maven Central when you can.
8. **Identity:** base package and applicationId are `me.kalfa.agentconsole` — never `com.example` or AI-Studio-generated ids (Google Play rejects them).
9. **KSP versioning:** KSP now uses standalone versions (2.3.x line, decoupled from Kotlin). Do not force the legacy `<kotlin>-<ksp>` format.
10. **Functional code only.** No documentation files, READMEs, or comments-as-docs unless explicitly requested.

## Telephony & Integration Integration Specs (For Real Implementation Swapping)

### 1. Authentication
- Use one-time key flow on `IClient` instance: client connects → calls **`requestOneTimeKey(String username)`** (never `requestOneTimeLoginKey`, which is Web SDK only) → gets one-time key → hashes server-side: `MD5(oneTimeKey + "|" + MD5(user + ":voximplant.com:" + password))` with bare `user` (no suffix) → log in with `loginWithOneTimeKey(fullUsername, hash)`.

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
- `console_call_feed(call_attempt_id, event_id, campaign_id, direction, kind, status, handled_by: ai|agent, agent_id, rsvp_digit, finish_reason, call_duration_sec, callback_iso, created_at, updated_at)` — trigger-fed from `call_attempts`, deliberately PII-free (no phone/transcript/recording). Live `status` values: `in_progress|completed|no_answer|cancelled|no_response`.

Read-only VIEWS (fetch/poll only — Supabase Realtime does not fire on views):
- `console_campaigns(id, event_id, status: approved|closed, enabled, start_at, close_at, max_contacts)` — billing columns intentionally hidden
- `console_rsvp_results(id, event_id, guest_id, guest_name, attending: boolean, adults, kids, note, created_at)` — note `attending` is a BOOLEAN, not an enum
- `console_campaign_targets(id, event_id, campaign_id, contact_id, status, current_step_index, next_run_at, reached_at, stop_reason)`

Write ownership (hard rule): the console READS. RSVP outcomes are written by the ElevenLabs client-tools pipeline; campaign state is billing-coupled and changed only via the beta.kalfa.me API; `outreach_state` belongs to the orchestrator. The only direct writes allowed: own `agent_status` row, and `handled_by`/`agent_id` on `console_call_feed` at takeover.

Gate: all console access requires membership in `console_agents` (enforced by RLS/`is_console_agent()`).

## API contract (the ONLY external HTTP calls; JWT = Supabase session token)

Base `https://beta.kalfa.me`, header `Authorization: Bearer <jwt>`. Routes may not exist yet server-side — code against this contract exactly, surface failures as Hebrew error states, never change paths or invent routes.

- `POST /api/agents/status` `{"status":"ready|not_ready|dnd"}`
- `POST /api/calls/outbound` `{"phone":"+9725XXXXXXXX","event_id":"uuid"}` → `{"call_id":"uuid"}`
- `POST /api/calls/{id}/monitor` `{"mode":"monitor|takeover"}`
- `POST /api/campaigns/{id}/start` / `POST /api/campaigns/{id}/pause` `{}`

## Domain facts (fixed — do not redesign)

- Telephony platform: Voximplant (apps `kalfa-rsvp` prod / `kalfatest` sandbox). AI voice brain: ElevenLabs Agents (Hebrew agent) bridged inside VoxEngine.
- The AI already persists results via its own tools (`save_rsvp`, `mark_dnc`, `notify_owner`, `schedule_callback`) — the app READS results; it never writes RSVP outcomes on behalf of the AI.
- RSVP answers: `attending | declined | maybe | callback`, with adult + children counts.
- An AI call is takeover-capable: monitor (listen-only) / takeover (AI dropped, human bridged) — in this codebase these are `CallEngine` interface calls only.

## Hebrew UI conventions

- Dates `DD.MM.YYYY`; times 24h `HH:mm`; phones displayed `05X-XXX-XXXX`.
- Status labels: זמין / לא זמין / נא לא להפריע / בשיחה. Answers: מגיע / לא מגיע / אולי / חזרו אליי.
- Microcopy short and directive; natural Hebrew over anglicisms.

