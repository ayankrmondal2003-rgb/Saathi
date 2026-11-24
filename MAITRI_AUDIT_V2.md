# Maitri audit v3 — Step 0 re-audit (no code changed)

Date: 2026-11-24, at `master` `0d06187` (PRs #1–#3 merged).

Method:
- Re-read the code. Every claim from v2 below was re-checked, not copied.
- Captured all 28 screen/theme pairs with `MaitriAllScreensCaptureTest` on a Pixel 10 Pro emulator (API 37, 1280×2856, 426×952 dp viewport) and placed each next to `design-reference/crops/{light,dark}-NN.png`.
  - The crops are drawn at 390×844, so absolute positions differ by the viewport. Findings below are relative proportions, sizes and content.
  - 07 and 08 are captured without the dock, because the capture test does not dock them.

Baselines:
- Android JVM unit tests: 164/164.
- Isolated backend tests: 143/143 (Alembic head 0012).
- `lintDebug`: **7 errors** (one fewer than in v2), 203 warnings:
  - `MissingPermission` in `NotificationHelper`
  - 2 × `StateFlowValueCalledInComposition` in legacy `IncidentScreens`
  - 4 × `MissingTranslation` (`maitri_tagline`, `maitri_supporting`, `maitri_get_started`, `maitri_footer`)

**Status key:**
- **works**: real, end to end, locally verified.
- **partial**: real but incomplete.
- **missing**: not implemented.
- **fake**: shows or claims something untrue.
- **blocked**: needs an external service or account.

## Release-wide findings

| # | Finding | Evidence | Status | Planned fix (phase) |
|---|---|---|---|---|
| R1 | Server rejects every account not in the test allowlist | `core.py` `test_account_uids`; `ENVIRONMENT=development` | blocked for beta | `ENVIRONMENT=beta` (P1) |
| R2 | Incidents can only be test incidents | `CreateIncident.is_test: Literal[True]`; DB `CHECK (is_test)` | partial | real incidents in beta, rate limits (P1) |
| R3 | Circle contacts saved server-first; dispatch reads the server table | `TrustedContactSync.kt`, `trusted_contacts.py` | works | — |
| R4 | No SMS provider. Contact alerts are recorded as `NO_SMS_PROVIDER` | `trusted_contacts.py` | blocked | FCM to app users, plus the phone's own SMS composer (P3) |
| R5 | Only approved volunteers get invitations, but nobody can approve one | `moving_geofence.py`, `volunteer.py` | partial | admin panel (P4) |
| R6 | R2 presigned upload/download API exists (`/api/files`, migration 0012). Bucket not configured; the app never uses it | `files.py`, `storage.py` | partial | app uploads + R2 setup (P4) |
| R7 | No volunteer admin review panel | — | missing | `/admin` (P4) |
| R8 | No account deletion or data export | — | missing | P6 |
| R9 | No missed-check-in job | `circle_sharing.py` | missing | P3 |
| R10 | No crash reporting (app or server), no CI | — | missing | Crashlytics; Render logs or Sentry (P8) |
| R11 | Package `in.maitri.app`, Maitri branding | — | works | — |
| R12 | Release signing wired (keystore at `~/keys/maitri-upload.jks`). versionCode 1 / "1.0"; R8 release never installed and tested | `app/build.gradle.kts` | partial | P9 |
| R13 | Font identity unknown (SVG text is outlined) | — | partial | stays documented; no exact-match claim |
| R14 | Manifest lacks `ACCESS_BACKGROUND_LOCATION`, `USE_FULL_SCREEN_INTENT`, `RECORD_AUDIO`/`CAMERA` and microphone/camera FGS types | `AndroidManifest.xml` | missing | P3/P5 |
| R15 | 7 lint errors | `lintDebug` | partial | P0 |
| R16 | Maps use Esri World Street Map, keyless public endpoint. Dark theme only dims tiles; light capture showed half-loaded grey tiles | `ui/EsriMap.kt` | partial | MapLibre + OpenFreeMap (P2) |
| R17 | No beta label, no SOS rate limit, no false-alarm report | — | missing | P1 |
| R18 | No privacy policy, terms or account-deletion web page | — | missing | `docs/site/` (P6) |
| R19 | Legacy screens still compiled: `IncidentScreens`, `CommunityScreens`, `ContactsScreen`, `TestResponderDashboard` (the Responder tab uses the last). Source of 2 lint errors and "practice/test alert" wording | `navigation/SafetyApp.kt:412` | partial | P0 clean-up |

## Per-control audit (14 screens × light/dark)

Both themes behave identically in every row unless "dark" is noted. Visual rows cover both themes.

### 01 Welcome — `ui/MaitriWelcome.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Get started → 03; skipped when signed in | Works | works | — |
| Visual | Logo tile, wordmark and button sit higher than drawn (button at ~76% vs ~84% of height); footer text smaller | partial | spacing pass (P10) |

### 02 Home — `ui/MaitriHome.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Bell | Opens Android notification **settings**, not an inbox | missing | in-app inbox + unread badge (P3) |
| SOS hold 3 s + cancel | Works. Starts a local session; shared with the server only as a **test** incident, otherwise "Local practice session" | partial | real SOS in beta (P1). No-PIN path: start, then prompt for a PIN (P1) |
| Share my trip | Consented TRIP session, server expiry; recipients must be paired app users (by **email** pairing code) | partial | phone/pairing-code matching + push (P3) |
| Check in | Creates a CHECK_IN session; nothing happens when it's missed | partial | missed-check-in job (P3) |
| Manage / + | → Circle | works | — |
| Contact count | From the server-backed circle | works | — |
| Beta label | Absent | missing | P1 |
| Visual | SOS card and quick-action cards shorter; title/subtitle sizes smaller than drawn | partial | P10 |

### 03 Log in / Sign up — `ui/MaitriLogin.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Log in / Sign up tabs | Works | works | — |
| Continue with phone | Firebase Phone Auth; SMS needs Blaze, so real users fail on Spark | blocked | replaced by Google in the same slot (P1) |
| Continue with Google | Implemented as an **extra** outlined button under "Log in with email"; not yet verified on a device | partial | move to the phone button's slot and style; device test (P1) |
| Country code "+91" | Fixed label; design shows "+ Code" | partial | goes away with the phone row (P1) |
| Email + password, show/hide, forgot password | Works | works | — |
| Terms / Privacy | Plain text | missing | real links (P6) |
| Cold-start "Connecting to Maitri…" | Interceptor exists; the login screen shows no message | partial | P1 |
| Visual | Spacing tighter; Material email/lock/eye icons instead of SVG icons | partial | P10 |

### 04 Volunteer verification — `ui/MaitriVolunteer.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Yes / Not now | Works | works | — |
| 5 document rows | Picks a file and validates type/size; sends **metadata only** | partial | R2 presigned upload, camera or file, retry and remove (P4) |
| Download template | Absent | missing | bundled declaration PDF (P4) |
| Consent | Works | works | — |
| "x of 5" counter | Says "documents selected" (local) | partial | count real uploads (P4) |
| Submit for review | Works server-side; shows the storage notice | partial | P4 |
| Application status | Read on the Responder tab; nobody can approve | partial | status card + admin panel (P4) |
| Visual | Material filled icons vs outline SVG; rows shorter; title smaller; no template link | partial | P10 |

### 05 Your circle — `ui/MaitriCircle.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Add / edit / remove / primary / SOS toggle | Server-first; failures revert with a message | works | — |
| Import from phone contacts | System picker | works | — |
| "Choose who gets your SOS" | Present | works | — |
| Pairing with friends who have the app | Email-based code in the sharing dialog only | partial | phone-number match + pairing code on this screen (P3) |
| Banner copy | "SMS alerts are not connected yet." (truthful deviation) | partial | replace once the phone-SMS composer flow exists (P3) |
| Visual | Title and row heights smaller; info card uses a filled shield | partial | P10 |

### 06 Nearby SOS request — `ui/MaitriIncoming.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Delivery | FCM to approved, opted-in, available users within 1 km; no full-screen intent | partial | full-screen where allowed, else heads-up + chosen vibration (P5) |
| Locality / distance before accepting | "Nearby emergency" / "Distance unavailable" (the server sends none) | partial | approximate locality + distance band from the server (P5) |
| Accept / Decline | Works | works | — |
| Expiry | Server expires the invitation; the screen doesn't count it down | partial | P5 |
| "Available for nearby requests" | Toggle lives on the Responder tab; the label under the card is missing | partial | P5/P10 |
| Visual | Otherwise matches both themes | works | — |

### 07 On your way — `ui/MaitriResponderJourney.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Map | Esri tiles with the victim pin only; dark = dimmed tiles | partial | MapLibre, both positions, dark style (P2) |
| Distance / ETA | "≈ N m straight-line"; no ETA | partial | "approx." walking estimate (P2) |
| Bring in help | Works | works | — |
| Call | Labelled as a call to the person but **dials 112** | fake | dial the victim when they've consented, otherwise hide (P5) |
| Contact emergency services | Dials 112 | works | — |
| Open in Maps | Works | works | — |
| Wipe on end/revoke | Works | works | — |
| Visual | Matches; "RESPONDER · ON THE WAY" vs "LIVE" | works | — |

### 08 SOS is active — `ui/MaitriActiveSos.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Live map | Esri, own fix + accuracy circle; no responder pins | partial | MapLibre + responder positions (P2) |
| Timer | Works | works | — |
| Responders + ETA | Count/status only; empty card "Checking responders…" | partial | names + approx. ETA (P5) |
| Circle notified | "Delivery unconfirmed" | blocked | push status + "SMS prepared" (P3) |
| Voice / Camera | Opens the phone's recorder/camera apps; nothing uploaded | partial | in-app capture to R2 (P5) |
| End SOS with PIN | Works | works | — |
| Call 112 always visible | Works (added in PR #3) | works | — |
| Offline queue / cold-start retry | Works | works | — |
| Visual | Layout matches; subtitle and footer are truthful states | works | — |

### 09 Profile & settings — `ui/MaitriSettings.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Edit, Permissions, Test vibrations, Safety PINs, Language | Navigate correctly | works | — |
| Themes | System / Light / Dark persisted; the drawn label is "Blush" | partial | "Blush" naming (P10) |
| Notifications | Android notification settings | works | — |
| Privacy & data | Info dialog only | missing | export + delete (P6) |
| Help & support | Bundled text, no email, no beta info | partial | FAQ + support email + Beta info (P6/P11) |
| Sign out | Works | works | — |
| Visual | Matches; eyebrow letter-spacing tighter | partial | P10 |

### 10 Language — `ui/MaitriLanguage.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Search, six languages, Save | Works; translations cover screens 09–14 only | partial | full-app strings, marked for review (P7) |
| Visual | Matches | works | — |

### 11 Test vibration — `ui/MaitriVibration.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Patterns + Test | Works; never sends an SOS | works | — |
| Persist + use for real alerts | `rememberSaveable` only | missing | persist + apply in `NotificationHelper` (P5) |
| Visual | Matches | works | — |

### 12 Permissions — `ui/MaitriPermissions.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Location / Notifications | Real state; request or settings | works | — |
| Background location | Not declared or shown | missing | prominent disclosure (P5) |
| Contacts / Microphone / Camera | "Not needed" | partial | real states once in-app capture exists (P5) |
| Battery optimisation + OEM tips | Absent | missing | P5 |
| Visual | Matches | works | — |

### 13 Safety PIN — `ui/MaitriPin.kt`
| Control | Current | Status |
|---|---|---|
| Set, confirm, show, weak-PIN rejection, change, reset | Server-backed | works |
| Visual | Matches | works |

### 14 Edit profile — `ui/MaitriEditProfile.kt`
| Control | Current | Status | Fix |
|---|---|---|---|
| Photo | Gallery only, downscaled, device-only | partial | camera/gallery, crop, R2 (P4) |
| Name / phone / city | Saved via `PATCH /api/users/me` (deployed) | works | — |
| Email | Read-only | partial | verify-before-update (P6) |
| Medical notes | Encrypted on the device only | partial | server-side encryption, responder-visible during an active SOS, consent note (P6) |
| Visual | Matches | works | — |

## Summary (v3)

| Status | Rows |
|---|---|
| works | 34 |
| partial | 34 |
| missing | 8 |
| fake | 1 (07 "Call" dials 112) |
| blocked | 2 (phone OTP on Spark; circle delivery without SMS) |

Visual: 06–14 match in both themes. 01–05 need the spacing/size pass and SVG icons instead of Material icons.

## Beta progress (branch `beta-complete`)

Status "server-tested" means covered by the isolated backend suite and Android unit tests, and rendered on the emulator, but not yet tried end to end on phones against a deployed server (that is Step 4).

| Phase | Rows changed | Now |
|---|---|---|
| P0 | R15 lint, R19 legacy screens | works (0 lint errors; dead screens removed) |
| P1 | R1 beta access, R2 real incidents, R17 beta label and 3 SOS/hour; 02 SOS hold, no-PIN path; 03 Google in the phone slot, phone OTP removed; cold start no longer blocks Home | server-tested |
| P2 | R16 maps; 07 map, distance and approx. walk; 08 map | works on the emulator (MapLibre + OpenFreeMap, both themes) |
| P4 | R5/R6/R7 volunteer uploads to R2, declaration template, application status, /admin review with audit log; 14 profile photo | server-tested |
| P5 | 08 in-app voice (60 s, consent, indicator) and photo uploaded via WorkManager; responders' names, approx. distance/ETA and map pins; 07 Call only with consent, false-alarm report; 06 distance band + expiry countdown; 11 pattern saved and used for alerts; full-screen request where Android allows; 12 battery tips; 14 call consent | server-tested |
| P6 | R8 export + account deletion (in app and web page); R18 privacy/terms/deletion pages (docs/site, GitHub Pages); 14 medical notes encrypted on the server, shown to an active responder (07), email change via verification; 09 Privacy & data screen, Help with support email and Beta info; 03 Terms/Privacy links; retention jobs now run (SOS location 7 days, SOS media 30 days) | server-tested |
| P7 | 10 full-app translations: 630 app texts in Hindi, Bengali, Marathi, Tamil, Telugu via tr() + assets/i18n (screens 09–14 keep their string resources); machine-drafted, marked for native review; I18nTest enforces coverage and placeholders | works on the emulator in Hindi, except English words baked into the 06/07/08 artwork (fixed when P10 draws those screens natively) |
| P8 | R10 error tracking: Firebase Crashlytics in release builds with an opt-out in Privacy & data; Sentry for the server (errors only, scrubbed, on when SENTRY_DSN is set). CI workflow still missing | server-tested |
| P9 | R12 signed release APK (upload key, SHA-1 registered in Firebase), versionCode 900 / 0.9.0-beta.1, R8 scoped to androidx/kotlin; keep rules for Room/WorkManager/startup/Gson models after a startup crash in the first build; installs and starts on the emulator and the SM-E625F | partial: full feature check waits for the deployed server (Step 4) |
| P10 | Visual pass: 150% text on every screen (FitText, taller tab bar, design line breaks), 06-08 text native (no text in artwork), design outline icons on 03-05, 03/04/05 sizes from the SVGs; tools/design_diff.py scores all 28 pairs against design-reference/scores.json (wrong screen ~25, boards 90-99) | done; remaining gaps are font (Inter vs system), sample data and the intentional Google login on 03 |
| P3 | R4 circle alerts without SMS; R9 missed check-in; 02 bell inbox, Share my trip, Check in; 05 pairing; 08 Circle notified | server-tested |

P3 detail:
- **05:** Invite creates a single-use code (24 h, 10 tries/hour); "Enter a code from a friend"; phone-verified numbers get an inbox request; "You get SOS alerts from …".
- **08 Circle notified:** per contact Sending / Push sent / Push delivered (phone confirmed) / Opened / SMS prepared / Not reached. Contacts without the app: a one-time offer opens the phone's SMS app (or WhatsApp share) with the `/l/<token>` live link; recorded as "SMS prepared" only after the composer returns.
- **/l/<token>:** read-only page, no personal data, stops when the SOS ends.
- **02 bell:** real inbox with unread badge; circle pushes confirm delivery.
- **Check-ins:** due time, reminder 5 minutes before, circle told once when missed, "I'm safe, check in now". Trips share location while Maitri is open.

Open limits:
- No background location: SOS tracking is a foreground service started from the open app, and volunteers share
  their position only while the app is open, so `ACCESS_BACKGROUND_LOCATION` is deliberately not requested.
- Photos use the phone's camera app (no CAMERA permission); the crop is an automatic centre square.
- Full-screen requests need the user's "full-screen notifications" permission on Android 14+; otherwise heads-up.
- Missed check-ins reach only circle members with the app.
- Trip updates stop when Maitri is closed.
- WhatsApp needs one share per chat.

---

# History

## Audit v2 (2026-11-23, superseded by v3 above)

Date: 2026-11-23. Method: code read and grep of the current working tree; device captures of all 28 screen/theme combinations on a Samsung SM-E625F (Android 13), edge-to-edge, compared side by side with `design-reference/crops/{light,dark}-NN.png`; earlier report claims were re-verified rather than trusted. No automated pixel diff exists yet (Phase C adds it).

**Status key:**
- **works**: real, end to end, locally verified.
- **partial**: real but incomplete.
- **missing**: not implemented.
- **fake**: shows or claims something untrue.
- **blocked**: needs an external service or account.

### Release-wide findings

| # | Finding | Evidence | Status |
|---|---|---|---|
| R1 | Server rejects every account not in the test allowlist | `backend/app/core.py:35` checks `test_account_uids`; `render.yaml` sets `ENVIRONMENT=development`, plan `free` (sleeps) | blocked for release |
| R2 | Incidents can only be test incidents | `CreateIncident.is_test: Literal[True]`; DB `CHECK (is_test)` on `incidents` | intentional test-only; must change in Phase E |
| R3 | **Circle contacts never reach the server.** The app stores them in SharedPreferences; SOS alert dispatch reads the server `trusted_contacts` table, which the app never writes | `SafetyViewModel.kt:17` → `LocalContactRepository`; `trusted_contacts.dispatch_incident_alerts` | **fake** (UI implies contacts are alerted) |
| R4 | No SMS/WhatsApp provider; contact alerts are recorded as `NO_SMS_PROVIDER` (fixed in checkpoint D; previously reported a false `PROVIDER_ACCEPTED`) | `trusted_contacts.py` | blocked |
| R5 | Invitations go to any opted-in, available user, with **no check that the volunteer is approved** | no volunteer status check in `services/geofence.py` / `moving_geofence.py` | missing (safety-critical) |
| R6 | No private object storage (volunteer docs, SOS media, profile photo) | no storage SDK in `requirements.txt`; `volunteer.py` stores metadata only | blocked |
| R7 | No volunteer admin review panel | `reviewer_notes` column only | missing |
| R8 | No account deletion or data export (Play requirement) | no endpoints or UI | missing |
| R9 | No missed-check-in alert job or scheduler; trips rely on `expires_at` filtering only | `circle_sharing.py`; no scheduler in `app/` | partial |
| R10 | No error tracking (Sentry), no CI workflow | no `sentry` in requirements; no `.github/workflows` | missing |
| R11 | Branding: `applicationId`/`namespace` `com.example.surakshanet`; `app_name` "SurakshaNet"; `Theme.SurakshaNet`; launcher `mipmap/suraksha_launcher` | `app/build.gradle.kts:32,38`; `values/strings.xml:2`; manifest | missing |
| R12 | Release build: R8 optimisation enabled; no signing config; versionCode 1; targetSdk 37 | `app/build.gradle.kts` | partial |
| R13 | Font identity unconfirmed (all SVG text is outlined); app uses the system font | inventory notes | partial |
| R14 | Manifest lacks `ACCESS_BACKGROUND_LOCATION`, `USE_FULL_SCREEN_INTENT`, microphone/camera FGS types | `AndroidManifest.xml` | missing (needed for Phase D/F scope) |
| R15 | Lint: 8 pre-existing errors (`NotificationHelper` permission check, 2 `StateFlow.value` in legacy `IncidentScreens`, 5 untranslated strings) | `lintDebug` | partial |

### Per-control audit

#### 01 Welcome — `ui/MaitriWelcome.kt`, route `Intro`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Get started | → 03; skipped for signed-in users | → Login; shown only on first launch; signed-in users go straight to Home | — | works | — |
| Visual | Crop 01 | Logo tile and wordmark ~10 units higher; button 52 vs ~55 tall; footer text smaller | — | partial | spacing pass |

#### 02 Home / SOS — `ui/MaitriHome.kt`, route `Home`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Notification bell | Real inbox | Opens Android notification **settings** | — | missing | build inbox (invitations, circle requests, alert status) |
| SOS hold 3 s + cancel | Start incident | Hold with cancel → "Activate test SOS?" dialog → local practice or shared **test** incident | `POST /api/incidents` (is_test only) | partial | real incidents (R2) with consent and abuse controls |
| Share my trip | Consented live sharing to circle, auto-expiry | Dialog creates TRIP session, posts fixes, server-side expiry | `/api/circle-sharing/sessions*` | partial | recipients are paired app users only; no SMS link |
| Check in | Pick time; alert circle if missed | Creates CHECK_IN session | same | partial | missed check-in job (R9) |
| Manage / + Add | → 05 | → Circle tab | — | works | — |
| "Keep your people close · N saved contacts" | Real count | Local contact count | — | partial | count from server circle (R3) |
| Dock Home·Circle·Responder·Profile | As drawn | Order and active pill correct | — | works | — |
| Visual | Crop 02 | Header ~10 units higher; card heights −8; otherwise matches both themes | — | partial | spacing pass |

#### 03 Log in / Sign up — `ui/MaitriLogin.kt`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Log in / Sign up toggle | Switch modes | Works | Firebase | works | — |
| Country code | Picker | Fixed "+91" label; `+91` prefixed unless the number starts with `+` | — | partial | picker |
| Continue with phone (OTP) | Firebase Phone Auth | `verifyPhoneNumber` + code dialog implemented. **Backend requires a verified email**, so phone-only accounts are rejected by every API call | Firebase; `core.identity` | partial | allow phone identities server-side (Phase E) |
| Email + password, show/hide | Sign in / sign up | Works | Firebase | works | — |
| Forgot password | Reset email | Dialog → Firebase reset | Firebase | works | — |
| Terms / Privacy | Open real pages | Plain text, not links | — | missing | pages + links (Phase F) |
| Visual | Crop 03 | Copy restored in D; vertical rhythm ~30 units tighter; "+ Code" shown as "+91"; Material email/lock/eye icons instead of SVG icons | — | partial | spacing and icon extraction |

#### 04 Volunteer verification — `ui/MaitriVolunteer.kt`, route `VolunteerVerification`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Yes / Not now | Record intent | Works | `POST /api/volunteer/intent` | works | — |
| 5 document rows | Upload to private storage, 10 MB, PDF/JPG/PNG | Picks file, validates size and type, sends **metadata only** | `/api/volunteer/upload-document` | blocked | storage (R6) |
| Download template | Real template | Omitted (no template exists) | — | missing | author template |
| Consent checkbox | Required | Works | `/api/volunteer/consent` | works | — |
| "x of 5" counter | Uploaded count | "x of 5 documents selected" (local) | — | partial | switch to uploaded after storage |
| Submit for review | Submit | Works server-side; shows storage-blocked notice | `/api/volunteer/submit` | partial | — |
| Application status / admin review | Visible status; admin approve/reject | Status read on Responder tab; no admin panel | `/api/volunteer/status` | missing | R7 |
| Visual | Crop 04 | Copy restored in D; rows 58 vs ~63; title smaller; Material icons; no template link | — | partial | spacing and icons |

#### 05 Your circle — `ui/MaitriCircle.kt`, route `Contacts`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| List / Add / Edit / Remove | Persisted to server | **Device-only** (SharedPreferences) | (server API exists but unused) | fake | sync to `/api/trusted-contacts` (R3) |
| SOS alerts toggle | Persisted to server | Device-only | — | fake | same |
| Import from phone contacts | System picker | Works | — | works | — |
| "Choose who gets your SOS" | Informational | Present | — | works | — |
| Visual | Crop 05 | Copy restored (banner body is an intentional, truthful deviation); title 25 vs ~28; info card layout (icon column vs inline title) differs | — | partial | layout pass |

#### 06 Nearby SOS request — `ui/MaitriIncoming.kt`, route `Incoming?invitationId=`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Delivery to volunteers | Approved, in radius, high-priority full-screen FCM | FCM to opted-in available users in 1 km; no approval check; no full-screen intent | outbox worker | partial | R5; full-screen intent + disclosure |
| Accept / Decline | Server-confirmed | Works | `POST /api/responders/invitations/{id}/response` | works | — |
| Locality / distance | Approximate only before acceptance | "Nearby emergency" / "Distance unavailable" (server sends none) | — | partial | server approximate locality + distance band |
| "Available for nearby requests" | Toggle | Switch on Responder tab (artwork shows label) | `PUT /api/users/me` | works | — |
| Visual | Crop 06 | Matches both themes | — | works | — |

#### 07 On your way — `ui/MaitriResponderJourney.kt`, route `ResponderJourney?incidentId=`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Map / route / ETA / distance | Real map, live route | Illustrative artwork; coordinates + "Open in Maps"; "Distance and ETA unavailable" | snapshot + WebSocket | partial | map SDK (Section 0) |
| Bring in help | Recruit more | Works | `POST /api/incidents/{id}/request-help` | works | — |
| Call (victim) | Dial victim | Dials **112** | — | partial | victim phone, consent-gated |
| Contact emergency services | Dial 112 | Works | — | works | — |
| Status update | ON_THE_WAY / HELPING / … | Works | `PUT /api/incidents/{id}/responders/status` | works | — |
| Wipe on end/revoke | Exit and clear | Exits on CLOSED/REVOKED/403/404/410; tracking stopped | — | works | — |
| Visual | Crop 07 | Matches; header reads "RESPONDER · AUTHORIZED/status" vs "LIVE" | — | works | — |

#### 08 SOS is active — `ui/MaitriActiveSos.kt`, route `Active`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Live map | Victim + responders | Illustrative artwork; last location as text | snapshot | partial | map SDK |
| Elapsed timer | Live | Works | — | works | — |
| Responders accepted + ETA | Live | Count and IDs/status; no names or ETA | snapshot | partial | ETA needs routing; public responder display name |
| Circle notified | Real per-contact status | Truthful status labels; currently "Not sent · SMS not connected" | `GET /api/incidents/{id}/trusted-contacts` | blocked | R3 + R4 |
| Voice / Camera | Evidence to private storage with consent | Opens device recorder/camera apps; nothing uploaded | — | blocked | R6 |
| End SOS (PIN) | Safety PIN | Works; missing-PIN path → 13 | `POST /api/incidents/{id}/resolve` | works | — |
| Visual | Crop 08 | Matches both themes | — | works | — |

#### 09 Profile & settings — `ui/MaitriSettings.kt`, route `Profile`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Edit / Permissions / Test vibrations / Safety PINs / Language | → 14/12/11/13/10 | Works (device-tested) | — | works | — |
| Themes | Blush/light/dark/system, persisted | System/Light/Dark persisted; SVG "Blush" = light | — | partial | label decision |
| Notifications | Settings | Opens Android notification settings | — | works | — |
| Privacy & data | Export + delete account | Info dialog + app settings only | — | missing | R8 |
| Help & support | FAQ + email | Bundled help text, no email | — | partial | needs support email (Section 0) |
| Sign out | Sign out | Works | — | works | — |
| Visual | Crop 09 | Matches; eyebrow letter-spacing tighter | — | partial | — |

#### 10 Language — `ui/MaitriLanguage.kt`
| Control | Expected | Current | Status | Fix |
|---|---|---|---|---|
| Search, six languages, Save | Applies immediately | Works via `LocaleManager` (API 33+) / prefs; translations cover screens 09–14 only, not the whole app | partial | full-app translations; human review |
| Visual | Crop 10 | Matches | works | — |

#### 11 Test vibration — `ui/MaitriVibration.kt`
| Control | Expected | Current | Status | Fix |
|---|---|---|---|---|
| Patterns + Test | Plays waveform | Works (device-tested) | works | — |
| Persist + use for real SOS alerts | Persisted | **Not persisted** (`rememberSaveable` only); not used by alerts | missing | persist + apply in `NotificationHelper` |
| Visual | Crop 11 | Matches | works | — |

#### 12 Permissions — `ui/MaitriPermissions.kt`
| Control | Expected | Current | Status | Fix |
|---|---|---|---|---|
| Location / Notifications rows | Real state, request or settings | Works | works | — |
| Background location | Shown | Not shown (permission not declared) | missing | R14 |
| Contacts / Mic / Camera | Real state | "Not needed" with explanation (picker and external apps) | partial | becomes real once in-app recording exists |
| Battery optimisation guidance | Present | Missing | missing | add |
| Visual | Crop 12 | Matches | works | — |

#### 13 Safety PIN — `ui/MaitriPin.kt`
| Control | Expected | Current | Status |
|---|---|---|---|
| Enter/confirm, Show PIN, weak-PIN rejection, save, change, reset | Server-backed | Works (server- and device-tested) | works |
| Visual | Crop 13 | Matches | works |

#### 14 Edit profile — `ui/MaitriEditProfile.kt`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Photo change | Camera/gallery, cropped, private storage | Gallery, downscaled, **device-only**; no camera or crop | — | partial | storage (R6) + camera/crop |
| Name / phone / city | Saved to server | Works locally (server tests); **not deployed** | `PATCH /api/users/me` (migration 0010) | partial | deploy |
| Email | Editable | Read-only (sign-in identity) | — | partial | Firebase verify-before-update |
| Medical notes | Saved to server | Encrypted **on device only** (deliberate privacy choice) | — | partial | decision needed |
| Visual | Crop 14 | Matches | — | works | — |

### Summary

| Status | Controls |
|---|---|
| works | 37 |
| partial | 29 |
| missing | 13 |
| fake | 2 (Circle persistence and alert toggle, R3) |
| blocked | 5 |

Visual fidelity:
- 06–14 match the crops in both themes.
- 01–05 need a spacing/typography pass and extracted SVG icons instead of Material icons.
- Font identity is unresolved.

### Release phase 1 progress (branch `maitri-release`)

| Item | Commit | Now |
|---|---|---|
| R5 Only approved volunteers invited; acceptance re-checks approval | `56f4ce0` | works (approval itself still needs the admin panel, R7) |
| R3 Circle add/edit/remove/toggle/primary saved server-first; phone-only contacts uploaded once; failures revert and say "won't be used for SOS alerts"; screen 08 records circle alerts on a confirmed incident | `1819e62` | works for persistence and dispatch; delivery still blocked by R4 (no SMS provider) |
| 03 Phone OTP accounts accepted by the backend (migration 0011 `phone_verified`); Android gate uses the same rule; email pairing can no longer match phone-only accounts | `efdc70d` | works (country picker still missing) |

Tests at `efdc70d`:
- Android JVM 136/136.
- Device 26/26 on SM-E625F.
- Isolated backend 116/116 (Alembic head 0011).

Found during phase 1: `SafetyViewModel` built local `contactAlertStatuses` marked `PROVIDER_ACCEPTED`. Removed (see "SOS path fixes" below).

#### Maps (Esri World Street Map), commit after `e4cc31c`

Status:
- **07:** live map pinned at the server-reported location, with the exact design marker. Shows "≈ N m/km straight-line" only when the responder's own fix is under 5 minutes old. No ETA, because no routing service is configured; "Open in Maps" hands routing to the device's maps app.
- **08:** live map pinned at the owner's server-confirmed location, otherwise "Awaiting your location". No responder pins, because the server does not share responder positions.
- **06:** keeps the illustrative artwork, because the server withholds location before acceptance.

Attribution: "Powered by Esri" plus the service's copyright text is always visible; the full text opens on tap.

Open items:
- **API key:** tiles currently load from Esri's public endpoint. Commercial/production use needs an ArcGIS Location Platform key, set as `maitriEsriApiKey` in `local.properties` (never committed). With a key, the app switches to `ibasemaps-api.arcgis.com` with `?token=`; verify that endpoint once a key exists.
- **Dark theme:** World Street Map has no dark variant, so tiles are dimmed instead.
- **Privacy:** tile requests send the viewed area (tile coordinates) and the device IP to Esri. Disclose this in the privacy policy and the Data Safety form (Phase F).

#### Package move and Google Sign-In (`0a29e33`, `cdbd1af`)

| Item | Now |
|---|---|
| R11 applicationId/namespace `in.maitri.app`; sources under `in/maitri/app`; `Theme.Maitri`, `MaitriTheme`, adaptive launcher with monochrome layer (`e4cc31c`) | works. Internal logger and DB names (`surakshanet`) are kept on purpose |
| `google-services.json` for `in.maitri.app` (debug and upload-key SHA-1, web client) | works |
| R12 release signing with the upload key; passwords only from `local.properties` (`maitriKeystorePassword` / `maitriKeyPassword`) | partial (versioning scheme and a tested R8 release build still to do) |
| 03 "Continue with Google": Credential Manager → Firebase `GoogleAuthProvider`; cancelling is silent; a missing account or disabled provider shows a clear message | works on the build; not yet verified on a device |

Tests at `cdbd1af` plus the contract-test path fix:
- Android JVM 140/140.
- Isolated backend 116/116 (Alembic head 0011). `test_android_api_contract_consistency` still pointed at the old `com/example/surakshanet` path; it now reads `in/maitri/app`.
- Device suite not re-run: it uninstalls the app.

#### SOS path fixes

| Item | Now |
|---|---|
| Phone-OTP users could not raise an SOS. `TestIncidentSync`, `SosLocationService`, `DeviceRepository` (FCM token) and `InvitationNotificationWorker` still required `isEmailVerified`, so the incident was never created, location tracking stopped, and invitations never arrived. All now use `FirebaseUser.isMaitriVerified()` (same rule as `core.identity`) | fixed; `PhoneIdentityTest.sosPathsDoNotRequireAnEmail` guards it |
| Local fake SOS data: `contactAlertStatuses` / `PROVIDER_ACCEPTED` on start, closure and "test alert"; `SosIncident.notified = 3, accepted = 1` defaults | removed. Screen 08 shows only server delivery status (`contactDelivery`) |
| SOS still defaults to a local-only practice session unless "share" is ticked, and the server accepts only test incidents (R2) | open, Phase E |

Tests at `7f98d68`: Android JVM 141/141; device 26/26 on SM-E625F (Android 13).

#### SOS "Server refused synchronization (409)" fix

Cause: the server already had an open incident for the user (unique index `uq_open_incident_owner`), while the phone had lost its local link to it (reinstall, cleared data, sign-out/sign-in, or an SOS never ended with the PIN). `create()` answered 409, and the app treated every 4xx as fatal: it stopped the location service but left the local session running, so Home said "Your SOS session is open." The app always sends `{"is_test": true}` with no location, so `IDEMPOTENCY_MISMATCH` and `LOCATION_CONFLICT` could not occur from it.

| Change | Where |
|---|---|
| `GET /api/incidents/active` (owner only; 200 or 204) | `routes.py`, `services/incidents.py` |
| Structured 409s: `INCIDENT_ALREADY_OPEN` (with `incident_id`), `IDEMPOTENCY_MISMATCH`, `LOCATION_CONFLICT`; `X-Error-Code` header | `services/incidents.py` |
| Idempotency hash leaves out the fix; legacy full-body hashes still accepted | `request_digests` |
| A fix sent with create never fails creation; a replay applies a newer fix | `apply_creation_fix` |
| Orphaned ACTIVE incidents (no fix and no responder activity for `ORPHANED_INCIDENT_HOURS`, default 12) are closed with `INCIDENT_AUTO_EXPIRED`; duress and resolution requests are untouched; no migration (uses `CLOSED`) | `expire_orphaned`, background loop in `main.py` |
| Close side effects shared by PIN resolution and expiry | `revoke_incident_access` |
| App adopts the open incident on 409; 409 is never fatal; fatal codes end the local session too | `TestIncidentSync.kt`, `IncidentRecovery.kt` |
| Reconcile at sign-in/app start: adopt the server's open incident, or clear a local session the server ended | `SafetyViewModel.reconcileIncident` |
| Home: Resume SOS / End SOS (Safety PIN) and an inline status with Try again; no raw HTTP codes; technical detail goes to logcat tag `MaitriSosSync` | `MaitriHome.kt`, `SafetyApp.kt` |

Tests: Android JVM 149/149 (8 new in `IncidentRecoveryTest`); isolated backend 124/124 (8 new in `test_incident_recovery.py`). Needs the backend deployed before the on-phone checks.
