# SurakshaNet verification record

**Decision — withhold the three-phone demonstration.** The isolated backend and debug client have executable regression coverage. Live Firebase credentials, three allowlisted handsets and a completed [rehearsal record](THREE_PHONE_TEST.md) are outstanding. No public alerts were sent.

## Feature matrix

Each label applies only to the scope in that row. Backend fixtures substitute identity or Firebase transport and use actual PostgreSQL transactions and PostGIS geography.

| Phase | Scope | Finding and evidence |
|---|---|---|
| 1 | Kotlin, Compose, Material 3, Navigation, ViewModels, StateFlow, repositories | VERIFIED WORKING for compilation, packaging and covered state transitions. See app/build.gradle.kts, navigation/SafetyApp.kt and AuthViewModelTest. |
| 1 | Splash, onboarding, account forms, home, active SOS, responder, contacts, incoming, profile/settings, navigation, back stack, recreation and validation | IMPLEMENTED BUT UNTESTED as a complete UI journey. Instrumentation covers installation context and the service manifest. |
| 1 | Dark/light/system, persisted preference, typography, touch targets, responsive layout, glass, PIN, readiness and loading/error views | IMPLEMENTED BUT UNTESTED visually. Theme.kt and ui/Components.kt provide styling. Contrast, large text, reduced motion, blur cost, older-device rendering and rapid navigation need device QA. |
| 2 | Firebase initialization, registration, sign-in/out, session restoration, verification email, reset and auth state observation | BLOCKED BY ENVIRONMENT for live identity. FirebaseAuthRepository implements the operations. No app/google-services.json was located. Local demo registration and simulated verification are separate. |
| 2 | Email verification enforcement, verified identity and restricted backend access | VERIFIED WORKING with stubbed verifier for missing/invalid/unverified/unlisted identities. core.py requests Firebase revocation checking. Real signing keys and live project configuration remain untested. |
| 2 | Local contacts, isolation, local credentials and logout | PARTIALLY IMPLEMENTED. Contacts and profiles use UID-specific preference keys. Demo credentials now have salted PBKDF2 verifiers and existing local plaintext entries migrate. Backup and transfer include only the theme preference. Cross-account handset and offline logout tests remain. |
| 3 | Fused provider, foreground service type/permissions, notification, callback cleanup, accuracy, timestamps, duplicate/stale fixes and state | VERIFIED WORKING for the installed manifest and JVM tracking rules. LocationPanel, SosLocationService and FusedLocationSource implement permission/provider handling. Shared tracking startup checks the confirmed server incident. |
| 3 | GPS callbacks while backgrounded, screen-locked, restricted, approximate or offline | BLOCKED BY ENVIRONMENT for an authorized live incident. The instrumentation run did not start GPS. A server outage can leave only the latest local fix available for retry. |
| 4 | FastAPI startup, migrated schema, constraints, GiST indexes, ownership, protected profile/device/location/incident routes | VERIFIED WORKING in the isolated suite. Uvicorn loopback health and HTTP calls exercised. Revisions 0001–0005 applied to an empty PostGIS 3.7.0 database. |
| 5 | Geography in metres, longitude/latitude ordering, 999.9/1000.1 m boundaries, moving victim and responder entry, periodic evaluation, stale/inaccurate fixes, opt-in and availability, atomic deduplication and grants outside radius | VERIFIED WORKING for the scenarios in test_postgis.py, test_moving_geofence.py and test_realtime_and_coordination.py. Independent connections exercise racing requests. |
| 6 | Device registration/reassociation, several tokens, outbox eligibility, transport acceptance, retry, invalid-token deactivation, pending invitation expiry, closure and payload privacy | VERIFIED WORKING for tested mocked sends backed by PostGIS. Closure during a blocked simulated send preserves cancellation. A previously transmitted message can still reach a handset after closure. |
| 6 | Actual FCM registration, refresh, delivery, foreground, background, locked screen, sound, permission and tap navigation | BLOCKED BY ENVIRONMENT. High-priority data messages use the app notification handler. Six 700 ms pulses plus five 200 ms pauses span 5200 ms. OS and channel settings govern playback. Direct notification-shade accept/decline actions are MISSING. Responses are available after opening the invitation. |
| 7 | Single-use socket tickets, protected snapshots, authorized location events, join/status/leave, expiry, suspension, revocation, closure | VERIFIED WORKING for backend test scenarios. See test_realtime_and_coordination.py. JVM tests exercise parsing, including a FastAPI location envelope. |
| 7 | Responder profile and fix uploads, authorized tracking, map, reconnect, owner status and assistance UI | PARTIALLY IMPLEMENTED. TestResponderDashboard uploads while visible and available. The owner polls a snapshot. UI provides coordinates and an external map shortcut. The in-app radar is illustrative. Device reconnect, process death and multi-account navigation need observation. Durable misuse reporting is MISSING. |
| 8 | Enrollment, distinct duress enrollment, Argon2id, old-PIN change, lockout, owner checks, closure, invitation invalidation, grants, audit, denied post-closure uploads | VERIFIED WORKING in isolated API scenarios. PIN enrollment now rejects replacement. Recovery checks recent Firebase auth_time. Tests substitute a recently authenticated identity. |
| 8 | Recovery reauthentication UX, simultaneous closure requests, handset GPS shutdown, false-alarm and experimental duress experience | PARTIALLY IMPLEMENTED. A successful primary PIN confirms closure. Human safety cannot be inferred from it. Duress is a test-state transition and needs separate client/server state-machine validation. |
| 8 | Development retention defaults | VERIFIED WORKING for the configured values and tested cleanup cases. maintenance.py clears closed incident coordinates after 24 h, eligible expired invitations/grants after 7 days and incident events after 30 days. Cleanup is a bounded maintenance command. Scheduling and coverage of separate PIN-attempt/request tables remain open. |

## Test execution and builds

- Baseline backend — 38 passed. That suite accepted legacy PIN-free resolution, so the count alone overstated protection.
- Intermediate backend — 37 passed, 1 failed. Protected closure also expires fresh pending invitations. The retention test now checks deletion of the specific eight-day-old invitation and checks for remaining pending invitations.
- Final backend — from backend, run `./.venv/Scripts/python.exe tests/run_isolated.py`. The loopback test cluster migrated to 0005. **39 passed, 12 warnings in 20.76 seconds**. Warnings concerned Starlette test-client and Alembic configuration deprecations. The cluster stopped. No deployment database was targeted.
- Android — the shell wrapper initially exited before Gradle because JAVA_HOME and java on PATH were absent. Android Studio's Gradle runner executed `:app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest`. JVM coverage includes auth, tracking, notifications, realtime parsing and PIN-related rules. Final result — debug build succeeded, 31 JVM tests passed with zero skips or failures, and both instrumentation checks passed on a physical handset and on emulator-5554. The installed checks cover package and foreground-service manifest configuration.
- No real Firebase notification, emergency call or three-phone workflow was exercised. Test fixture tokens and a simulated transport cannot establish delivery latency, vibration or human acknowledgement.

## Security and API compatibility repairs

| Finding | Change |
|---|---|
| Legacy request-resolution and close-test routes bypassed PIN protection and sync used them automatically | Removed both routes and client calls. Shared closure goes through POST /api/incidents/{id}/resolve. |
| Enrollment could overwrite a PIN and ordinary long-lived sessions could invoke recovery | Insert-only enrollment, recent sign-in requirement for recovery, conflict and recovery coverage. |
| Verified Firebase users could participate without a deployment-controlled test allowlist | TEST_ACCOUNT_UIDS defaults empty. Firebase identity, geofence recipient/owner selection and outbox claims enforce the scope. Use a dedicated development Firebase project. |
| Returned ticket had no ws_url, client expected a different event envelope and snapshot field names | Client builds socket URL from HTTPS API origin, uses expires_in, handles event_type and payload, maps latest_location, responder_summary, started_at and grant fields. |
| Owner dashboard displayed synthetic counts and ETA | Shared incident counts and assistance statuses now come from server snapshots. |
| Local session mismatch could trigger backend closure, coordinates could survive terminal events and a real map action could use a simulated fallback point | Scoped active incident IDs, explicit closure, clearing terminal coordinates and restricted map fallback. |
| Combined notification/data messages could bypass the app handler when backgrounded, test-alert reported success without checking send results | Data-only urgent messages, expiry check, test alert counts accepted sends, external send runs off the event loop. |
| Send completion could overwrite cancellation during closure | Protected final status update and integration regression with a deliberately suspended mock send. |
| Plaintext demo account passwords and backup eligibility for credential preferences | Salted password verifiers with lazy repository migration and theme-only backup/transfer. |

## Remaining work before promotion

- [ ] Execute the entire three-phone guide on a private deployment. Verify fresh email/password authentication, token refresh, device reassociation, stale token rejection, owner status and service shutdown.
- [ ] Verify force-stop/relaunch and server restart recovery. The realtime hub and ticket store are process-local. Sustained multi-worker operation requires shared delivery or single-worker deployment. A crashed outbox worker can leave SENDING rows stranded. Mixed token successes and transient failures need per-device retry coverage.
- [ ] Verify concurrent closure/acceptance, repeated closure and expiry during a silent socket. Test failed closure and client recovery after a successful commit with a lost HTTP reply.
- [ ] Review audit and resolution-attempt cleanup, production proxy trust, access-log redaction of WebSocket query tickets and device-token paths, rate limits and crash-report privacy.
- [ ] Exercise active-incident logout and account switching. The client can lose its local incident screen before server closure. Verify foreground-stop/re-entry and sign-in recovery without disclosing another account's state.
- [ ] Complete geographic map rendering, misuse reporting, notification actions and automated screen-flow coverage. Measure accessibility, layout, screen rotation and battery/lock-screen behavior on representative devices.
- [ ] Exercise duplicate notification delivery after process death. The current local deduplication window is in memory. Firebase transport acceptance and server-recorded responder acceptance must be shown separately.
- [ ] Review the duress simulation end to end. Some active-only operations exclude ESCALATION_REQUIRED. No covert public alert pathway is authorized.
