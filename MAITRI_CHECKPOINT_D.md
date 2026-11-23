# Maitri checkpoint D — final audit report

Date: 2026-11-23. Scope: screens 01–14 against the 28 approved crops (`design-reference/crops/{light,dark}-01…14`). Local only; nothing was pushed or deployed.

## Overall result

| Gate | Result |
|---|---|
| Android JVM tests | **PASS**: 129/129, 0 skipped |
| Android device tests (Samsung SM-E625F, Android 13, unlocked) | **PASS**: 26/26, 0 skipped |
| Isolated backend tests (PostgreSQL + PostGIS, Alembic head 0010) | **PASS**: 110/110 |
| Debug APK | **PASS**: `app/build/outputs/apk/debug/app-debug.apk` |
| Paired Light/Dark screenshots, all 14 screens | **PASS**: 28 device captures compared side by side with the 28 crops; no automated pixel diff |
| `lintDebug` | **FAIL**: 8 errors, all in files this checkpoint did not touch (see blockers) |
| Live two-phone emergency drill | **NOT RUN** |
| Deployment | **NOT DONE** (as instructed) |

Test-only SOS restrictions are unchanged:
- The "Activate test SOS?" confirmation is still required.
- An SOS stays local practice unless GPS sharing is ticked.
- The server's `is_test` CHECK constraint still applies.

## Per-screen audit

Visual: PASS means matches the crop in both themes, with any listed deviation being intentional.

| # | Screen | Buttons → destination | Function | Visual |
|---|---|---|---|---|
| 01 | Welcome | Get started → 03 (first launch only) | Real | PASS |
| 02 | Home / SOS | Hold SOS → test-SOS confirmation → local practice, or 08 after server confirms. Share my trip / Check in → sharing dialogs. Bell → Android notification settings. Manage, + → 05. Dock → 02/05/Responder/09 | Real (test incidents only) | PASS |
| 03 | Login | Continue with phone → SMS code. Log in/Sign up with email. Forgot password → reset email | Real (Firebase) | PASS after fix. Deviation: the country code defaults to +91 where the SVG shows a "+ Code" placeholder |
| 04 | Volunteer verification | Intent, document pickers, consent, Submit → volunteer API | Partial: documents are validated on the device only (blocker 3) | PASS after fix. Deviations: "documents selected", not "uploaded"; the "Download template" link is omitted because no template exists |
| 05 | Your circle | Add contact, edit, remove, SOS toggle, Import → system contact picker | Contacts real; SMS delivery not connected (blocker 2) | PASS after fixes. Deviation: the banner body states that SMS alerts are not connected |
| 06 | Incoming SOS | Accept → 07 after server confirmation. Decline → Responder. Unknown or expired invitation → back to inbox | Real | PASS. Place, distance and ETA show "unavailable" because the server withholds location before acceptance |
| 07 | Responder journey | Status, Open in Maps, Bring in help → recruitment API, Call / Contact → dialer (112). Exits when the incident is closed or access is revoked | Real | PASS. Distance and ETA are unavailable (no routing service) |
| 08 | SOS active | End SOS → PIN → server-confirmed closure (or "Set Safety PIN" → 13). Voice / Camera → the device's own apps | Real | PASS. Contact delivery is shown truthfully |
| 09 | Profile & settings | Edit → 14, Permissions → 12, Test → 11, Safety PINs → 13, Language → 10, Themes dialog, Notifications → Android settings, Privacy, Help, Log out | Real | PASS |
| 10 | Language | Six languages, Save | Real | PASS |
| 11 | Test vibration | Gentle / Steady / Strong, Test | Real | PASS |
| 12 | Permissions | Rows → runtime prompt or the matching Android settings page; Open device settings | Real | PASS |
| 13 | Safety PIN | Enrol / change / recover / unlock | Real (server) | PASS |
| 14 | Edit profile | Change photo, Save → `PATCH /api/users/me` | Real; needs deployment (blocker 1) | PASS |
| — | Responder tab | Availability switches, location sharing, inbox → 06, Resume → 07, Volunteer application → 04. Without a server-backed session it shows an "unavailable" card | Real. A device test confirms it fails closed without a session | Artwork PASS |

## Fixes in this checkpoint

1. **False delivery success (backend).** Trusted-contact alerts were stored and reported as `PROVIDER_ACCEPTED`, but no SMS provider exists. The status endpoint returned that value for any incident ID without checking ownership.
   - Alerts are now recorded as `FAILED` with `failure_reason = NO_SMS_PROVIDER`.
   - Status is derived from the real outbox rows (`NOT_SENT`, `NO_SMS_PROVIDER`, `NOT_REQUESTED`, …).
   - The endpoint returns 404 to anyone who doesn't own the incident.
   - A dead query that picked an arbitrary active user was removed.
   - Screen 08 shows plain wording such as "Not sent · SMS not connected", never "Sent".
2. **Fake home indicator.** Screens 03 and 04 drew the reference iPhone frame's home-indicator bar inside the app. It has been removed.
3. **SVG copy restored** on screens 03, 04 and 05, which had drifted from the approved design. Wording was changed only where the SVG sentence would claim something untrue (documents "uploaded", contacts "receive your SOS").
4. **Circle status-bar inset.** Screen 05 ignored the status-bar inset, so on the production edge-to-edge window its header sat under the status bar. It now uses the same insets as Home.
5. **Dock on screen 06.** The dock now stays visible on the Incoming route with Responder selected, matching crop 06.
6. **Screen 07 board.** The board was extracted as stateless `MaitriResponderJourneyBoard` so it can be rendered and captured; behaviour is unchanged.

## Remaining blockers (external)

1. **Deployment:** migration `0010`, the profile `PATCH` endpoint and the truthful contact-delivery change all need a backend deploy.
2. **No SMS provider** is integrated, so trusted contacts are not actually notified.
3. **No private object storage**, so volunteer documents and profile photos are not uploaded. There is also no declaration template.
4. **No routing service** for distance and ETA.
5. **Medical notes** are stored on the device only; sharing them with responders needs a consent design and server-side key management.
6. **Pre-existing lint errors (8):** `NotificationHelper` missing-permission check, two `StateFlow.value` reads during composition in legacy `IncidentScreens`, and five untranslated `maitri_strings`/`app_name` entries.
7. **Not yet verified:**
   - the live two-phone responder drill;
   - an automated pixel diff;
   - native-speaker review of the translations;
   - the duress-PIN closure wording, which needs a product decision.
