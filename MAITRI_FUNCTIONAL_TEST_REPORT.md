# Functional test report — checkpoint 3

Current increment (2026-11-23 — Circle Sharing & Check-In):
- VERIFIED: :app:assembleDebug and :app:testDebugUnitTest; 73 JVM unit tests passed, zero skipped/failed.
- VERIFIED: CircleSharingTest (8 tests passed) covering Check-in location omission, trip freshness and mock flags, error sanitization, receipt availability labeling, pairing/link serialization, active session deserialization.
- VERIFIED: Isolated backend test execution against local PostGIS 3.7.0 and Alembic head 0008 via run_isolated.py.
  - test_circle_sharing.py: 12/12 tests passed (100%).
  - Full isolated backend suite: 77/77 tests passed.
  - Retention purge, expiration, consent boundaries, rate limiting, and stop revocation verified without touching the live Render DB.
- VERIFIED: Light and Dark Compose Previews (MaitriHomeLightPreview, MaitriHomeDarkPreview) rendered and visual hierarchy verified.
- VERIFIED: git diff --check passed with zero whitespace or conflict warnings.
- Preserved exact SVG UI design, navigation order (Home -> Circle -> Responder -> Profile), and hold recognizer without substitution.

- VERIFIED: :app:assembleDebugAndroidTest and installation of both APKs on connected Samsung.
- VERIFIED: focused MaitriHomeTest, 3 tests passed in 11.08s. Cancellation after 2.2 seconds prevents later completion; full hold completes once; release does not duplicate; accessible action opens confirmation; cancel does not start; default confirmation starts local-only; tab order and Contacts route preserved.
- VERIFIED: full Samsung instrumentation suite, OK (13 tests), 20.709s. Includes prior Welcome/keypad/recovery regressions and new Home tests.
- PARTIAL: Light/Dark Home Compose previews inspected; no matched full-screen handset pixel comparison.
- Backend unchanged and suite not rerun in checkpoint 2. The 65 backend tests below are checkpoint-1 evidence, not a new run.
- No live SOS, FCM delivery, SMS, cloud rollout or database mutation tested in this increment.
- After relaunching MainActivity, the physical phone showed the existing authenticated account-sync screen (Syncing your verified account). The production Home screen was not reached in that observation. Isolated Home instrumentation passed, but authenticated full-screen Home/device-cloud verification remains BLOCKED pending successful profile synchronization. No authentication bypass or account clearing was used.
- git diff --check passed.

Current APK: app/build/outputs/apk/debug/app-debug.apk.
SHA-256: 0E38E23955086FDA493F1EB3C568D645C5BC8AFA52ABAF9ED43488F8C9D62A0D.

## Historical checkpoint 1 evidence

Overall: PARTIAL. Tests below validate the welcome increment and existing regressions, not completion of all Maitri controls.

| Check | Result |
|---|---|
| Debug build | VERIFIED. Android Studio Gradle :app:assembleDebug succeeded. |
| JVM tests | VERIFIED. 65 passed, zero skipped/failed. |
| Instrumentation APK | VERIFIED. :app:assembleDebugAndroidTest succeeded. |
| Samsung install | VERIFIED. Main and test APKs installed successfully through ADB on connected Samsung. |
| Samsung tests | VERIFIED. Final run OK (10 tests), 11.23 seconds. Includes new welcome button/scroll test in both themes at 500dp height and existing keypad, hold, dock, queue, deduplication, receipt and service tests. |
| Earlier device run | FAILED due to locked device. Keypad and welcome could not see app controls. User unlocked; full rerun passed. This was not hidden by changing expectations. |
| Backend suite | VERIFIED. Fresh isolated PostGIS 3.7.0, Alembic head 0007 and temporary Redis 7. 65 passed, 26 warnings, 18.37 seconds. Database shut down by runner and temporary Redis stopped. |
| Compose visuals | PARTIAL. Light and Dark welcome previews render. Remaining font/spacing mismatch documented; no pixel-perfect claim. |
| Real FCM / cloud incident | BLOCKED for this checkpoint: not exercised. Previous phase evidence is historical. No live SOS or real SMS was sent. |
| New feature tests | BLOCKED pending implementation: phone OTP, volunteer review, trips/check-ins, routing, media, six-language coverage and extended profile. |

Build artifact: app/build/outputs/apk/debug/app-debug.apk.
SHA-256: E2A1234DCD788924EA5B495A99C11376E6F1E4DF67CF171F7F9AFF84E3E230E1.

Initial shell Gradle call failed before compilation because JAVA_HOME was unset. IDE Gradle used its configured JVM. First resource compilation caught duplicate fillColor on converted gradient paths; fixed, then build/tests succeeded. Vector wordmarks retain long paths intentionally to preserve original branding.

The app was relaunched after instrumentation. Existing authenticated users bypass first-run welcome through the unchanged auth gate; app data was not cleared and user was not signed out to force onboarding.

No new release signing, database migration, Git commit/push or Render deployment performed. git diff --check passed.
