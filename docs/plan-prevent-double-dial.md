# Preventing a double outbound dial — app-side plan

Status: **implemented 2026-08-18**, compiled and unit-tested. Not yet on a device.

Order changed after measuring the latency (see "Why it takes 6-10 seconds"): the
UI feedback is the real fix and the gate is the belt behind it. The two latency
optimisations stand on their own and are NOT in this change.

## What happened, with evidence

Measured on the owner's device, 2026-08-18 01:38 UTC (04:38 IDT), build
`v5.5 (85457bb)` / versionCode 137:

```
04:38:51  dial.step step=intent_http status=200 ms=3228
04:38:51  vox.connect_start
04:38:57  vox.connect_ok                          ← 6.5 s
04:38:58  dial.step step=place_call ok=true ms=6892   ← call 1
04:39:00  dial.step step=intent_http status=200 ms=3002
04:39:00  vox.login_start plan=already
04:39:00  dial.step step=place_call ok=true ms=1      ← call 2
```

Voximplant confirms two independent sessions on rule `ConsoleOut`, two distinct
dial tokens:

| session | start | duration | agent leg | PSTN leg | finish |
|---|---|---|---|---|---|
| 7769476232 | 01:38:58 | 122 s | 200 normal clearing | 603 decline | Timeout |
| 7769493570 | 01:39:00 | 71 s | 200 normal clearing | 603 decline | Timeout |

The agent hung up; that ended the session the UI was tracking (71 s). The other
session was orphaned and played hold music until `HOLD_REPEAT_MAX_MS` (120 000 ms)
— which is why it ran 122 s. The owner experienced this as "I pressed hang up and
the call did not disconnect". A second dial minutes later, with only one call in
flight, hung up correctly — confirming the diagnosis.

## Root cause

`ConsoleViewModel.dialManual` (and `dialContact`, `returnCallback`) launch a
coroutine with no in-flight guard. Every tap mints a new dial token and places a
new call. A 6.9 s dial with no on-screen feedback makes a second tap the natural
thing for a person to do.

The server did not catch it because `countLiveConsoleCalls()` is **account-wide**,
not per agent, with `MANUAL_DIAL_MAX_LIVE_CALLS = 2` — two calls is exactly what
it permits.

## The fix, entirely on the device

### 1. One gate for every dial path

Not a flag per function: the keypad, a history row and a callback return all
contend for the same single outbound call, and per-function flags would let two
of them race. An `AtomicBoolean` is the authority (correct on any thread);
`ConsoleUiState.dialing` exists only so the UI can render it.

```kotlin
private val dialInFlight = AtomicBoolean(false)

/** Single-flight gate for EVERY outbound dial. See docs/plan-prevent-double-dial.md. */
private fun dialOnce(block: suspend () -> Unit) {
    if (!dialInFlight.compareAndSet(false, true)) {
        DeviceTelemetry.record(TelemetryEvents.DIAL_SUPPRESSED)
        return
    }
    _uiState.update { it.copy(dialing = true) }
    viewModelScope.launch {
        try {
            block()
        } finally {
            dialInFlight.set(false)
            _uiState.update { it.copy(dialing = false) }
        }
    }
}
```

The three entry points become `fun dialManual(...) = dialOnce { ...existing body... }`
with the `viewModelScope.launch` removed from each.

**The gate releases when the dial call returns**, not when the call ends. That is
the right boundary: it spans the whole 6.9 s window, and hanging up then dialling
again is legitimate.

### 2. The button has to say so

The gate alone only catches the second tap. What stops it is feedback:

- `ManualDialSheet` takes `dialing: Boolean`; `enabled = canDial && !dialing`,
  with a progress indicator in the button while dialling.
- The same for the dial affordances in `HistoryScreen` and `CallDetailSheet`.

### 3. Close the observability gap

The outbound path emits nothing after `place_call`, while the inbound path emits
`call.state` and `call.cleanup`. That is why the hangup had to be inferred from
Voximplant rather than read. Add the same two events on the outbound leg, plus
`dial.suppressed` so we can measure how often this was happening.

## Risks

- **A hung dial holds the gate.** Bounded: `KalfaHttpClient` uses a 30 s request
  timeout, so the suspend function cannot hang indefinitely.
- **Process-scoped.** An app kill mid-dial clears the flag; two devices signed in
  as one agent are not covered. The account-wide cap of 2 remains the backstop.
  A per-agent server gate in `dial-intent` would close this, and is deliberately
  NOT in this plan — see below.
- **Deliberately out of scope:** a per-agent server gate. It would refuse rather
  than prevent, and it has a bad failure mode — an intent that succeeds server-side
  while the client times out would leave the agent refused with an orphan call
  they cannot see. Worth revisiting only if a duplicate ever appears that the
  device-side gate could not have stopped.

## Verification

1. ViewModel unit test: two rapid `dialManual` calls → `callEngine.dialManual`
   invoked exactly once.
2. ViewModel unit test: after the first completes, a further dial is allowed.
3. ViewModel unit test: the failure path still releases the gate (`finally`).
4. On device: open the keypad, tap dial twice quickly — the button disables on
   the first tap.
5. Decisive: `npm run voximplant -- history --app 11107202 --from <t0> --to <t1>`
   over the test window shows exactly ONE session, not two.

---

## Why it takes 6-10 seconds — measured 2026-08-18

| stage | where | measured |
|---|---|---|
| `POST /api/console-calls/dial-intent` | our server: ~20 **sequential** Supabase round trips | 3228-5159 ms |
| `ensureLoggedIn` → SDK connect + login | done **lazily inside `placeCall`** | 2137-6451 ms |
| `VICalls.createCall` → `attachIncomingSession` → call screen | device | ~0 ms |

A PostgREST round trip from this server measures **~190 ms** (6 samples: 189-272 ms,
of which ~30 ms is TLS). Twenty of them in series is ~3.8 s — which is the
`intent_http` figure, so the endpoint's latency is fully explained by its shape.

The proof that the SDK half is avoidable is in the incident itself: the second dial's
`place_call` took **1 ms**, because the session was already up (`plan=already`).

### Two follow-ups, each worth doing on its own

1. **Keep the Voximplant session warm.** `placeCall` calls `ensureLoggedIn` at dial
   time. `PresenceActions.loginAndRegisterForPush` already logs in when the agent
   goes READY — the work is to guarantee the session is live before a dial, not to
   build anything new. Removes 2.1-6.5 s.
2. **Stop serialising `dial-intent`.** `getVoximplantConfig`, `consoleManualDialEnabled`,
   `callerHasPlatformPermission` and `countLiveConsoleCalls` depend on neither each
   other nor `resolveDialTarget`, yet run in series. Running the independent ones
   concurrently, and caching the two rarely-changing flags, should take ~3.8 s to
   well under 1 s.

## What shipped

- `SingleFlightGate` — pure Kotlin, no Android imports, 5 unit tests including a
  32-thread race that a check-then-act would fail.
- `ConsoleViewModel.dialOnce` wrapping `dialManual`, `dialContact`, `returnCallback`;
  releases in a `finally` so a refusal cannot lock the agent out.
- `ConsoleUiState.dialing`, rendered by `ManualDialSheet` and `CallDetailSheet` as a
  disabled button with a spinner and "מחייג…".
- **The sheets no longer close on the tap.** They stay open and busy and close when
  the dial resolves — this is the part that removes the silent window.
- `TelemetryEvents.DIAL_SUPPRESSED`, so a quiet log can be read as "the gate worked"
  rather than "nobody tapped".

Not done, deliberately: a call screen that opens immediately in a "dialling" state.
It would have to claim a session that does not exist yet, and this codebase is
careful never to show what it cannot vouch for. Worth revisiting once the two
latency items land, at which point it matters much less.
