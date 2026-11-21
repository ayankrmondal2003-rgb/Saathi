# Phase 9 reliability and private rehearsal

The debug build and isolated regression tests passed. Live Firebase delivery, background GPS continuity and the complete three-handset workflow still need observation. All incident tests in this phase used simulations. No public delivery was enabled.

## Evidence

| Check | Result |
|---|---|
| Android Studio Gradle sync, assembleDebug, testDebugUnitTest | Succeeded. 34 JVM tests passed across seven suites. |
| connectedDebugAndroidTest | Emulator 7 passed. Final physical handset run 5 passed and 2 UI checks skipped while locked. An earlier unlocked run passed all 7. These exercises cover local app behavior. |
| Isolated backend suite, from backend using .venv on Windows | `tests/run_isolated.py` reported 41 passed and 14 warnings in 16.94 s. The temporary PostgreSQL cluster used PostGIS 3.7.0 and migrated through revision 0006. |
| Official logo | The approved 1536 px PNG was imported from the supplied ZIP. The installed app's launcher icon was observed in the emulator drawer. [Launcher capture](app/build/reports/phase9-launcher.html) |
| Private Firebase and three phones | Pending. One physical handset and one emulator were available. The development Firebase Android configuration and a verified reachable private API were unavailable. |
| Battery, OEM restrictions, cold splash appearance and theme/device matrix | No measured performance or full physical visual run in this phase. |

## Implementation and limits

- **GPS and offline recovery.** Explicitly shared active sessions use an account- and incident-bound private SQLite queue. Android Keystore protects GPS payloads with AES-GCM. Routing identifiers and timestamps remain local database metadata. A unique timestamp per account and incident prevents duplicate records. The queue holds at most 48 fixes with a 120-second freshness window. Enqueue, replay and scheduled cleanup prune old records. Android may delay physical deletion while work is suspended. Old coordinates are removed from the tracking preferences on restore.
- **Upload recovery.** Network-constrained WorkManager work refreshes authorization, reconciles incident state, and replays eligible fixes oldest first. The worker uses exponential backoff with a six-run limit. Old fixes can expire during an outage. A confirmed closure clears local tracking. The foreground service owns live location acquisition. Returning to the app can be necessary to resume tracking under Android's start restrictions.
- **Backend and tracking.** Exact location replays suppress duplicate live events. Stalled outbox sends can be reclaimed after 90 seconds. Attempt fencing protects the new claim from late completion. Migration 0006 retains hashes of device tokens for successful sends to support partial-device retries. Registration now uses an installation identifier so two phones of the same model can register independently. WebSocket authorization is rechecked on idle connections, reconnects refresh the authorized snapshot, and old event sequences are discarded.
- **Notifications.** Incoming invitation work checks the signed-in recipient, backend invitation status and expiry before posting. Persistent local deduplication suppresses repeat alerts. The configured pattern spans 5.2 seconds. The local notification test exercises the Android channel. Firebase acceptance, handset display, perceived vibration and the responder's acceptance are separate observations. A crash between external send and database commit can still cause redelivery.
- **Privacy and retention.** Development cleanup targets closed precise history after 24 hours, expired invitations and grants after 7 days, and minimal audit records after 30 days. Cleanup of completed resolution bookkeeping is bounded. Private deployment still needs restricted proxy trust, redacted logs and deployment-level rate limiting. Use a single API worker with the current in-process live event hub.
- **Brand.** The Gradle build derives density resources from the original PNG. The adaptive launcher resource uses an inset foreground and pale plate. A shared Compose brand image and native Android 12+ launch theme cover startup and app branding. Functional status icons retain their own meanings. The flattened multicolour artwork has no faithful monochrome layer. Physical launcher mask and OEM splash checks are pending.

## Set up the three test accounts

1. Open the [Firebase Console](https://console.firebase.google.com/) on your computer. Choose **Create a project** and give it a development-only name. In Project overview, choose **Add app → Android** and enter `com.example.surakshanet` as the package name. [Firebase Android setup](https://firebase.google.com/docs/android/setup)
2. Download **google-services.json**. In Android Studio's **Project** view, place it inside the **app** folder beside **build.gradle.kts**. It should appear as `app/google-services.json`. Sync Gradle and rebuild.
3. In Firebase Console choose **Authentication → Sign-in method → Email/Password**, enable the email/password provider and save. Register three consenting test accounts in the app, open each verification email, and sign in again. Under **Authentication → Users**, copy the three **User UID** values for the private backend operator. Keep passwords and PINs private. [Firebase password authentication](https://firebase.google.com/docs/auth/android/password-auth)
4. Give the operator the Firebase project ID and those three UIDs. Ask for a private HTTPS API URL reachable from all three handsets. The operator steps below cover the database, credentials and server. You can use the local notification test while waiting.
5. When the operator gives you the URL, put `surakshaDevApiUrl=https://YOUR-PRIVATE-HOST/` in `%USERPROFILE%/.gradle/gradle.properties`, replacing the example with that URL. Sync, rebuild and install the same debug APK on all three phones.

### Private backend operator setup

Follow the dependency and database instructions in the [backend setup guide](backend/README.md). Use an isolated development PostGIS database. Set `DATABASE_URL`, `ENVIRONMENT=development`, the matching `FIREBASE_PROJECT_ID`, and `TEST_ACCOUNT_UIDS=["UID_A","UID_B","UID_C"]` with the three actual UIDs. Configure server-side Application Default Credentials with Firebase Auth verification and FCM send access. Store any credential file outside the repository and Android build. [Firebase server credentials](https://firebase.google.com/docs/cloud-messaging/auth-server)

From the backend virtual environment run `python -m alembic upgrade head`. Run the API behind a private TLS endpoint with one worker. Limit forwarded-header trust to your reverse proxy. Disable or redact access logs containing WebSocket query strings and authorization material. Confirm the private endpoint's `/ready` succeeds and all three phones can reach it. Check that invitation delivery is restricted to the test allowlist before beginning.

## Physical test record

Use the [three-phone rehearsal guide](THREE_PHONE_TEST.md) with the checks below. All rows are **pending physical end-to-end observation**. Record phone model, Android version, build, permission and channel settings, UTC time, observed result and pass/fail for each. Use aliases A/B/C and synthetic route points in shared notes.

| Step | Action and expected observation |
|---|---|
| 1 | Launch each installed app from its launcher. Inspect the mask, splash and header in dark, light and system modes. Recreate the activity and scroll the last Home card above the dock on small and large screens. |
| 2 | On B use the local notification test. Observe the channel, vibration and tap route. Repeat with notification permission disabled and with DND enabled. Record device behavior separately. |
| 3 | A explicitly starts a shared simulated SOS. B opts in inside the initial radius. C opts in outside. Check B's invitation receipt, visible alert and acceptance individually. Pending C has no precise tracking access. |
| 4 | Press Home on A, switch apps, then lock the screen. Record successive device fix timestamps observed by authorized B. Repeat with battery saver. Disable and restore location, and check that gaps and stale data remain visible. |
| 5 | Temporarily disconnect A. Move along the synthetic route. Restore connectivity within the freshness window, then repeat with a longer outage. Only fresh authorized fixes may replay. No older fix may replace a newer server fix. |
| 6 | Move A to the second point so C enters range and B leaves it. Confirm a single new invitation to C and continued access for accepted B. C accepts. Move to the third point and compare both maps. |
| 7 | Restart the private backend. Verify reconnect, authorized snapshot and subsequent ordered updates. Delay delivery of a test invitation beyond expiry or closure. Check that the device suppresses an obsolete alert. |
| 8 | Recreate A's activity and separately terminate the app process during a simulation. Reopen, sign in if needed, and verify the authoritative incident, one tracking notification and fresh timestamps. Repeat after credential expiry and account switch. No other account may inherit queued points. |
| 9 | Try an incorrect PIN, then resolve with the correct PIN. Verify closure on the backend, loss of access for both responders, stopped tracking on A and rejection of stale uploads. In a separate run, disconnect A and resolve using an authorized second session of the owner. Reconnect A and verify closure reconciliation before replay. |
| 10 | Repeat with the screen locked and notification permissions changed between runs. Record any OEM-specific behavior. Force-stop and shutdown can interrupt both collection and upload. Record the gap and the recovery observed after reopening. |

## Phase 10 priorities

Run the private three-phone rehearsal and retain redacted evidence first. Profile battery use during a sustained active simulation on two handset families. Review TLS proxy configuration, API throttling, retention scheduling and operational monitoring before widening testing. Multi-worker deployment requires a shared event transport and cross-worker revocation tests.
