# Maitri audit v2 — Phase A (no code changed)

Date: 2026-11-23. Method: code read and grep of the current working tree; device captures of all 28 screen/theme combinations on a Samsung SM-E625F (Android 13), edge-to-edge, compared side by side with `design-reference/crops/{light,dark}-NN.png`; earlier report claims were re-verified rather than trusted. No automated pixel diff exists yet (Phase C adds it).

**Status key:**
- **works**: real, end to end, locally verified.
- **partial**: real but incomplete.
- **missing**: not implemented.
- **fake**: shows or claims something untrue.
- **blocked**: needs an external service or account.

## Release-wide findings

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

## Per-control audit

### 01 Welcome — `ui/MaitriWelcome.kt`, route `Intro`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Get started | → 03; skipped for signed-in users | → Login; shown only on first launch; signed-in users go straight to Home | — | works | — |
| Visual | Crop 01 | Logo tile and wordmark ~10 units higher; button 52 vs ~55 tall; footer text smaller | — | partial | spacing pass |

### 02 Home / SOS — `ui/MaitriHome.kt`, route `Home`
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

### 03 Log in / Sign up — `ui/MaitriLogin.kt`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Log in / Sign up toggle | Switch modes | Works | Firebase | works | — |
| Country code | Picker | Fixed "+91" label; `+91` prefixed unless the number starts with `+` | — | partial | picker |
| Continue with phone (OTP) | Firebase Phone Auth | `verifyPhoneNumber` + code dialog implemented. **Backend requires a verified email**, so phone-only accounts are rejected by every API call | Firebase; `core.identity` | partial | allow phone identities server-side (Phase E) |
| Email + password, show/hide | Sign in / sign up | Works | Firebase | works | — |
| Forgot password | Reset email | Dialog → Firebase reset | Firebase | works | — |
| Terms / Privacy | Open real pages | Plain text, not links | — | missing | pages + links (Phase F) |
| Visual | Crop 03 | Copy restored in D; vertical rhythm ~30 units tighter; "+ Code" shown as "+91"; Material email/lock/eye icons instead of SVG icons | — | partial | spacing and icon extraction |

### 04 Volunteer verification — `ui/MaitriVolunteer.kt`, route `VolunteerVerification`
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

### 05 Your circle — `ui/MaitriCircle.kt`, route `Contacts`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| List / Add / Edit / Remove | Persisted to server | **Device-only** (SharedPreferences) | (server API exists but unused) | fake | sync to `/api/trusted-contacts` (R3) |
| SOS alerts toggle | Persisted to server | Device-only | — | fake | same |
| Import from phone contacts | System picker | Works | — | works | — |
| "Choose who gets your SOS" | Informational | Present | — | works | — |
| Visual | Crop 05 | Copy restored (banner body is an intentional, truthful deviation); title 25 vs ~28; info card layout (icon column vs inline title) differs | — | partial | layout pass |

### 06 Nearby SOS request — `ui/MaitriIncoming.kt`, route `Incoming?invitationId=`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Delivery to volunteers | Approved, in radius, high-priority full-screen FCM | FCM to opted-in available users in 1 km; no approval check; no full-screen intent | outbox worker | partial | R5; full-screen intent + disclosure |
| Accept / Decline | Server-confirmed | Works | `POST /api/responders/invitations/{id}/response` | works | — |
| Locality / distance | Approximate only before acceptance | "Nearby emergency" / "Distance unavailable" (server sends none) | — | partial | server approximate locality + distance band |
| "Available for nearby requests" | Toggle | Switch on Responder tab (artwork shows label) | `PUT /api/users/me` | works | — |
| Visual | Crop 06 | Matches both themes | — | works | — |

### 07 On your way — `ui/MaitriResponderJourney.kt`, route `ResponderJourney?incidentId=`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Map / route / ETA / distance | Real map, live route | Illustrative artwork; coordinates + "Open in Maps"; "Distance and ETA unavailable" | snapshot + WebSocket | partial | map SDK (Section 0) |
| Bring in help | Recruit more | Works | `POST /api/incidents/{id}/request-help` | works | — |
| Call (victim) | Dial victim | Dials **112** | — | partial | victim phone, consent-gated |
| Contact emergency services | Dial 112 | Works | — | works | — |
| Status update | ON_THE_WAY / HELPING / … | Works | `PUT /api/incidents/{id}/responders/status` | works | — |
| Wipe on end/revoke | Exit and clear | Exits on CLOSED/REVOKED/403/404/410; tracking stopped | — | works | — |
| Visual | Crop 07 | Matches; header reads "RESPONDER · AUTHORIZED/status" vs "LIVE" | — | works | — |

### 08 SOS is active — `ui/MaitriActiveSos.kt`, route `Active`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Live map | Victim + responders | Illustrative artwork; last location as text | snapshot | partial | map SDK |
| Elapsed timer | Live | Works | — | works | — |
| Responders accepted + ETA | Live | Count and IDs/status; no names or ETA | snapshot | partial | ETA needs routing; public responder display name |
| Circle notified | Real per-contact status | Truthful status labels; currently "Not sent · SMS not connected" | `GET /api/incidents/{id}/trusted-contacts` | blocked | R3 + R4 |
| Voice / Camera | Evidence to private storage with consent | Opens device recorder/camera apps; nothing uploaded | — | blocked | R6 |
| End SOS (PIN) | Safety PIN | Works; missing-PIN path → 13 | `POST /api/incidents/{id}/resolve` | works | — |
| Visual | Crop 08 | Matches both themes | — | works | — |

### 09 Profile & settings — `ui/MaitriSettings.kt`, route `Profile`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Edit / Permissions / Test vibrations / Safety PINs / Language | → 14/12/11/13/10 | Works (device-tested) | — | works | — |
| Themes | Blush/light/dark/system, persisted | System/Light/Dark persisted; SVG "Blush" = light | — | partial | label decision |
| Notifications | Settings | Opens Android notification settings | — | works | — |
| Privacy & data | Export + delete account | Info dialog + app settings only | — | missing | R8 |
| Help & support | FAQ + email | Bundled help text, no email | — | partial | needs support email (Section 0) |
| Sign out | Sign out | Works | — | works | — |
| Visual | Crop 09 | Matches; eyebrow letter-spacing tighter | — | partial | — |

### 10 Language — `ui/MaitriLanguage.kt`
| Control | Expected | Current | Status | Fix |
|---|---|---|---|---|
| Search, six languages, Save | Applies immediately | Works via `LocaleManager` (API 33+) / prefs; translations cover screens 09–14 only, not the whole app | partial | full-app translations; human review |
| Visual | Crop 10 | Matches | works | — |

### 11 Test vibration — `ui/MaitriVibration.kt`
| Control | Expected | Current | Status | Fix |
|---|---|---|---|---|
| Patterns + Test | Plays waveform | Works (device-tested) | works | — |
| Persist + use for real SOS alerts | Persisted | **Not persisted** (`rememberSaveable` only); not used by alerts | missing | persist + apply in `NotificationHelper` |
| Visual | Crop 11 | Matches | works | — |

### 12 Permissions — `ui/MaitriPermissions.kt`
| Control | Expected | Current | Status | Fix |
|---|---|---|---|---|
| Location / Notifications rows | Real state, request or settings | Works | works | — |
| Background location | Shown | Not shown (permission not declared) | missing | R14 |
| Contacts / Mic / Camera | Real state | "Not needed" with explanation (picker and external apps) | partial | becomes real once in-app recording exists |
| Battery optimisation guidance | Present | Missing | missing | add |
| Visual | Crop 12 | Matches | works | — |

### 13 Safety PIN — `ui/MaitriPin.kt`
| Control | Expected | Current | Status |
|---|---|---|---|
| Enter/confirm, Show PIN, weak-PIN rejection, save, change, reset | Server-backed | Works (server- and device-tested) | works |
| Visual | Crop 13 | Matches | works |

### 14 Edit profile — `ui/MaitriEditProfile.kt`
| Control | Expected | Current | Endpoint | Status | Fix |
|---|---|---|---|---|---|
| Photo change | Camera/gallery, cropped, private storage | Gallery, downscaled, **device-only**; no camera or crop | — | partial | storage (R6) + camera/crop |
| Name / phone / city | Saved to server | Works locally (server tests); **not deployed** | `PATCH /api/users/me` (migration 0010) | partial | deploy |
| Email | Editable | Read-only (sign-in identity) | — | partial | Firebase verify-before-update |
| Medical notes | Saved to server | Encrypted **on device only** (deliberate privacy choice) | — | partial | decision needed |
| Visual | Crop 14 | Matches | — | works | — |

## Summary

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

## Release phase 1 progress (branch `maitri-release`)

| Item | Commit | Now |
|---|---|---|
| R5 Only approved volunteers invited; acceptance re-checks approval | `56f4ce0` | works (approval itself still needs the admin panel, R7) |
| R3 Circle add/edit/remove/toggle/primary saved server-first; phone-only contacts uploaded once; failures revert and say "won't be used for SOS alerts"; screen 08 records circle alerts on a confirmed incident | `1819e62` | works for persistence and dispatch; delivery still blocked by R4 (no SMS provider) |
| 03 Phone OTP accounts accepted by the backend (migration 0011 `phone_verified`); Android gate uses the same rule; email pairing can no longer match phone-only accounts | `efdc70d` | works (country picker still missing) |

Tests at `efdc70d`:
- Android JVM 136/136.
- Device 26/26 on SM-E625F.
- Isolated backend 116/116 (Alembic head 0011).

Found during phase 1, not yet fixed: `SafetyViewModel` still builds local `contactAlertStatuses` marked `PROVIDER_ACCEPTED` (test alert and closure). No screen displays them, but they should be removed in phase D.
