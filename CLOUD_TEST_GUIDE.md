# Cloud acceptance record

Record deployment revision, role, handset model, UTC times and observed outcomes. Keep real coordinates, credentials and tokens out of screenshots.

1. Copy the real HTTPS origin from Render. In PowerShell assign `$origin = (Read-Host 'Render HTTPS origin').TrimEnd('/')`. Run `curl.exe -fsS "$origin/health"` and `curl.exe -fsS "$origin/ready"`. Check unauthenticated `/api/users/me` returns 401 and `/docs` returns 404. Repeat the health request in the Samsung browser.
2. In the private database verify `SELECT postgis_lib_version();`, `SELECT version_num FROM alembic_version;`, `SHOW transaction_isolation;` and `SELECT indexname FROM pg_indexes WHERE indexname = 'ix_responder_location_gist';`. For Free Postgres, record the expiry and verify your own private export if retaining demo data. Record the result of a separate restore rehearsal when available.
3. Set surakshaDevApiUrl in uncommitted root local.properties to the verified HTTPS origin. Gradle project properties override the local file. Sync, run `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`, then install the debug APK via `adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk` for each intended device. Use the serial from `adb devices -l`.
4. Follow [Firebase Admin setup](FIREBASE_ADMIN_SETUP.md). Sign in independently on A, B and C, refresh verified email and observe profile sync. Grant notifications and precise location through Android. Record Firebase token available and authenticated backend registration separately.
5. Send **Send Test Alert to This Phone** on each device. Record the request result, provider acceptance, actual receipt, visible notification and open. The self-test targets one registered installation directly. Use incident invitations to test the durable outbox.
6. Execute [the three-phone route](THREE_PHONE_DEMO.md). Start with responder B inside and C outside the 1 km radius. Check C's inbox and withheld location. After moving A, have C accept. Check both phones receive a later timestamp. Reconnect one client and compare with the backend snapshot. Close with A's primary PIN. Confirm both clients lose access and cannot obtain a fresh tracking ticket.
7. Rehearse a Key Value outage only on disposable staging infrastructure. Record readiness, subscriber recovery, latest authorized snapshot and rejection of a previously revoked grant. Test Android lock-screen updates and network recovery separately. A five-minute demo passes only if the three handsets show the expected observations.

| Evidence | Status before owner deployment |
|---|---|
| Hosted URL, TLS, PostGIS migration, Redis authentication, backups | BLOCKED |
| Verified identities, backend token ownership and real FCM sends | BLOCKED |
| B and C receipt, acceptance, moving GPS and PIN closure on handsets | BLOCKED |

If any cloud stage fails, use the isolated PostGIS demonstration in DEMO_GUIDE.md and identify it as local test evidence.
