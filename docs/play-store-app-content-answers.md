# Google Play — "App content" answer sheet

Every answer below is grounded in what the code actually does, with the file that
proves it. Written 2026-08-18 against `85457bb` / `versionCode 137`.

**None of these sections has a Developer API** except Data Safety. Verified against
the live Android Publisher API reference and against the `gplay-compliance` skill:
"Data Safety is the **only** Play compliance surface with a Developer API endpoint."
Content rating, target audience, ads, government, financial, health, app access,
app category and the privacy-policy URL are Console-only.

---

## 1. Privacy policy

`https://beta.kalfa.me/privacy` — live, returns 200, and names
`netanel.kalfa@kalfa.me` as the contact.

## 2. App access — ✅ DONE (2026-08-18)

The app is unusable without an approved KALFA team account
(`ui/screens/LoginScreen.kt`, Supabase email+password). Google **will** reject a
review it cannot get past, so this section is not optional.

A dedicated review account was created and verified end-to-end: Supabase auth
user (confirmed) → `platform_staff` → `console_agents` (`play-review`) →
`console_agent_secrets` → a real Voximplant SDK user. The reviewer can log in,
use every screen, and go READY.

**Remove it once review passes.** It sees the whole business call log — the
history API is staff-gated by `requireConsoleAgent`, not scoped per agent — and
a READY reviewer could take a real customer call. Removal is the same admin
screen (remove agent, revoke staff role; the FK cascades), then delete the user
in the Supabase dashboard.

## 3. Ads

**No ads.** The app shows none, and no ad SDK is present. Firebase Analytics
arrives transitively via `firebase-messaging` (needed for incoming-call push),
but `com.google.android.gms.permission.AD_ID` is **not declared** — confirmed by
`gplay preflight`, whose own note is that without it the SDK "reads zeros".

## 4. Content rating (IARC questionnaire)

A business/communication tool. No violence, no sexuality, no gambling, no user-
generated content shared between users, no unmoderated social features. Expected
outcome: the lowest rating tier in every region.

Answer honestly on one point: the app **does** enable calling real people.

## 5. Target audience and content

**18+ only.** This is an internal staff tool. It is not designed for or appealing
to children, so the Families policy does not apply.

## 6. Data safety — ✅ DONE (pushed 2026-08-18, accepted by Google)

### Collected and transmitted off the device

| Category → type | Collected | Required | Purpose | Shared | Evidence |
|---|---|---|---|---|---|
| Personal info → Email address | Yes | Required | Account management, App functionality | No | `LoginScreen.kt`, `ConsoleViewModel.kt:492` |
| Personal info → Phone number | Yes | Required | App functionality | No | dial-intent + call-history call the KALFA API with the target number |
| Audio → Voice or sound recordings | Yes | Required | App functionality | No | the app captures the mic and streams call audio; recording itself happens server-side |
| App activity → Other actions | Yes | Optional | Analytics, App functionality | No | `telemetry/` — user-toggleable |
| App info and performance → Crash logs, Diagnostics | Yes | Optional | Analytics, App functionality | No | `telemetry/`, Firebase |
| Device or other IDs | Yes | Required | App functionality | No | FCM registration token |

### NOT collected

Location (no permission), photos/videos (no camera — removed via
`tools:node="remove"`), contacts, calendar, files, financial info, health,
advertising ID.

### Security practices

- **Encrypted in transit: yes.** Every call goes to `https://beta.kalfa.me/api/…`;
  telephony media runs over the Voximplant SDK's encrypted transport.
- **Data deletion: yes, on request** — via the employer, `netanel.kalfa@kalfa.me`.
  Agents do not self-serve deletion; this is a staff account.

### The one thing that needs care

Telemetry values pass `scrubTelemetryValue` (`telemetry/TelemetryEvent.kt`) before
leaving the device: emails → `<redacted:handle>`, JWTs and opaque tokens →
`<redacted:token>`, phone-shaped values → `<redacted:digits>`. The scrub runs on
the **raw** value before whitespace flattening — deliberately, because flattening
first would turn `(050) 123 4567` into something the phone rule no longer matches.
So diagnostics are declarable as non-identifying. **That claim depends on the
scrub staying correct** — it is covered by `TelemetryFormatTest`, and that test is
the thing protecting this declaration.

### How to push it

The Data Safety CSV is the Play Console's own import/export format. Bootstrap is
one manual export:

    Play Console → App content → Data safety → Export to CSV

Commit it to `compliance/data-safety.csv`, then:

    gplay compliance datasafety validate            # offline, structural
    gplay compliance datasafety set --dry-run       # rehearse
    gplay compliance datasafety set --confirm       # the real write

Requires gplay ≥ 0.18.0. The write **replaces the whole declaration** and there is
no read endpoint — the CSV in git is the only record of what was sent.

## 7. Government apps

**No.** KALFA is a private B2C company.

## 8. Financial features

**No.** The app handles no payments. Billing lives in the web platform, not here.

## 9. Health

**No.**

## 10. App category and contact details

Category: **Business**. Contact details are already set via the API —
`netanel.kalfa@kalfa.me`, `+97233301505`, `https://kalfa.me`.

---

## Also outstanding

- **Screenshots** — minimum 2, device-only.
- **`USE_FULL_SCREEN_INTENT` declaration** — a calling app qualifies for the
  exemption, but the form still has to be filled.
- **Testers** — `gplay tracks testers set` once you have the email list.
