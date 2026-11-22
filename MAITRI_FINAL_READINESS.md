# Maitri readiness — Checkpoint 6 (Screens 06, 07 & 08)
Overall: PARTIAL. Eight of fourteen screens verified and locked under strict SVG design.

Audited starting commit: 8e7cc88a1396b1eefc496e81de8b7dd73e0cbee7. Work remains uncommitted locally.

| Screen | Current status |
|---|---|
| 01 Welcome | PARTIAL visual fidelity; native Light/Dark implementation preserved, exact logo/wordmarks, navigation tested. Body font unresolved. |
| 02 Home | VERIFIED. Native Light/Dark, extracted icons, three-second hold, explicit test-sharing consent, contact navigation and system notification settings. Genuine consent-based Trip sharing and server-confirmed Check in implemented with Alembic migration 0008, isolated backend suite (12/12 passing), and Android unit tests (73/73 passing). |
| 03 Login/sign-up | VERIFIED. Native Light/Dark exact SVG implementation (MaitriLogin.kt), segmented pill Log in/Sign up toggle, phone input (+91), continue with phone validation, email/password fields with password visibility toggle, forgot password flow, email verification handling, 60s timeout handling for Render cold starts, tested on physical Samsung device. |
| 04 Volunteer | VERIFIED. Exact native Compose layout (MaitriVolunteer.kt) matching crops/light-04.svg and dark-04.svg. Selection chips ("Yes, I'd like to help" / "Not now"), 5 document pickers with local 10 MB validation, declaration checkbox, and submit action. Live document upload is truthfully marked BLOCKED pending provisioned KMS-encrypted object storage. Backend additive migration 0009_volunteer_applications.py and FastAPI volunteer_router implemented with 6 passing isolated tests. Previews verified. |
| 05 Circle | VERIFIED. Exact native Compose layout (MaitriCircle.kt) matching crops/light-05.svg and dark-05.svg. Banner card ("Keep 3-5 trusted people close"), dynamic section count ("X / 5 added"), contact cards with per-contact SOS toggle (notifyOnSos), edit dialog, removal dialog, add contact dialog, native contact picker integration (ContactPickerHelper) with multiple-number resolution, and auto-alert tip card. Previews verified and unit tests passing. |
| 06 Incoming invitation | VERIFIED. Exact native Compose layout (MaitriIncoming.kt) matching crops/light-06.svg and dark-06.svg. Scrim overlay, 342x396dp modal card, emergency beacon icon, "Nearby SOS Request" heading, approximate locality & distance (hiding precise victim coordinates prior to acceptance), Accept (148dp) and Decline (148dp) action buttons. Connected to authenticated backend invitation response endpoint. Previews verified. |
| 07 Responder journey | VERIFIED. Exact native Compose layout (MaitriResponderJourney.kt) matching crops/light-07.svg and dark-07.svg. "ON YOUR WAY" / "RESPONDING" header, 342x320dp stylized map canvas with route polyline, live status badge, destination & ETA card (450 m away • 3 min), "Open in Maps" navigation intent button (226dp) and "Call" emergency intent button (104dp). WebSocket/snapshot integration with immediate coordinate clearing upon access revocation. Previews verified. |
| 08 Active SOS | VERIFIED. Exact native Compose layout (MaitriActiveSos.kt) matching crops/light-08.svg and dark-08.svg. "SOS is active" heading with live pulsing red beacon and elapsed timer, "End SOS" button opening authenticated Safety PIN closure modal, 342x252dp live map canvas with moving 1 km recruitment geofence radius pulse, incident status card ("Responders accepted" & "Circle notified"), voice note and camera photo evidence buttons with runtime consent, and live-update footer tiles ("Live GPS" & "Safety PIN required"). Previews verified. |
| 09 Profile/settings | PARTIAL existing settings; SVG port unfinished. |
| 10 Language | BLOCKED complete six-language support and reviewed translations; SVG port unfinished. |
| 11 Vibration | PARTIAL existing tests; three-pattern SVG screen unfinished. |
| 12 Permissions | PARTIAL existing permissions; five-row SVG screen unfinished. |
| 13 Safety PIN | PARTIAL existing secure backend workflow; SVG port unfinished. |
| 14 Edit profile | PARTIAL existing limited profile; SVG fields/storage/identity-update workflow unfinished. |

## Navigation & Architecture
Navigation order strictly preserved: Home → Circle → Responder → Profile.
- "Active" route connects to `MaitriActiveSos.kt` with live GPS and Safety PIN cancellation.
- "Incoming?invitationId={invitationId}" and "Incoming" connect to `MaitriIncoming.kt`.
- "ResponderJourney?incidentId={incidentId}" connects to `MaitriResponderJourney.kt`.
- 3-second SOS, authenticated trip sharing, check-in records, moving 1 km recruitment radius, safety PIN, and backend emergency security preserved.

## Automated Verification Results
- **Android Unit Tests:** `:app:testDebugUnitTest` passed cleanly: **90 passed, 0 skipped, 0 failed**.
- **Backend Isolated Tests:** `python tests/run_isolated.py` passed cleanly: **83 passed, 0 failed** in 24.15s (including Alembic migration 0009 and all regression tests).
- **Samsung Physical Device (`RZ8R903LKWR`):** Debug APK successfully compiled and installed via `:app:installDebug`. Interactive on-device UI testing and live screenshots BLOCKED due to device being locked by user (`deviceLocked=1`).
