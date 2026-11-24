# Claude Code prompt — Maitri: finish all 14 screens, make every control work, rename, and make it Play-Store-ready

> Fill in the 6 values in **Section 0** before pasting. Everything else can be pasted as-is into Claude Code, run from the project root (`C:\Users\ayank\AndroidStudioProjects\SurakshaNet`).

---

## 0. Decisions I have made (fill these in)

- **Package / applicationId:** `<e.g. in.maitri.app>`  (must NOT start with `com.example` — Play Console rejects it)
- **Backend hosting:** `<Render Starter (paid, always-on) | Railway | Fly.io | GCP Cloud Run>`  — must be always-on; free tiers that sleep are not acceptable for SOS
- **Database:** `<Render Postgres paid | Neon | Supabase>` with PostGIS enabled
- **File storage for volunteer documents and SOS media:** `<Firebase Storage | Cloudflare R2 | AWS S3>` (private bucket)
- **Maps provider:** `<Google Maps SDK + Directions API | MapLibre + OpenStreetMap>`
- **Support email + privacy-policy URL host:** `<email>` / `<where the privacy policy page will live>`

---

## 1. Context — read before writing any code

You are working on an Android app (Kotlin, Jetpack Compose) with a FastAPI + PostgreSQL/PostGIS + Redis backend in `backend/`. The app is currently branded **SurakshaNet** (package `com.example.surakshanet`) and must become **Maitri**.

The source of truth for the UI is:
- `design-reference/maitri · Light theme · 01–14.svg`
- `design-reference/maitri · Dark theme · 01–14.svg`
- Per-screen crops: `design-reference/crops/light-01..14.svg/png` and `dark-01..14.svg/png`

The 14 screens (board order) are:
01 Welcome · 02 Home/SOS · 03 Log in / Sign up · 04 Volunteer verification · 05 Your circle · 06 Nearby SOS request (Responder modal) · 07 Responder "On your way" · 08 SOS is active · 09 Profile & settings · 10 Language · 11 Test vibration · 12 Permissions · 13 Safety PIN · 14 Edit profile

Existing work: there are many `MAITRI_*.md` reports plus `Maitri*.kt` screens in `app/src/main/java/com/example/surakshanet/ui/`. **Do not trust the status claims in those reports.** `MAITRI_FINAL_READINESS.md` says everything is "VERIFIED & LOCKED", while `MAITRI_REPO_FEATURE_AUDIT.md`, `MAITRI_PENDING_FEATURE_IMPLEMENTATION.md` and `MAITRI_DEPLOYMENT_REQUIREMENTS.md` list many items as PARTIAL, MISSING or BLOCKED. Re-audit every screen against the SVGs and the actual code before you change anything.

Known blockers for a public release, already in the repo:
- `render.yaml` runs `ENVIRONMENT=development`, on the **free** plan, with a `TEST_ACCOUNT_UIDS` allowlist. Real users would be rejected, and the server sleeps (SOS cold-start delay).
- `KNOWN_LIMITATIONS.md` says public SOS broadcasting is intentionally disabled.
- Phone-number login (Screen 03 "Continue with phone") is not implemented.
- Volunteer document upload (Screen 04) and SOS voice/photo evidence (Screen 08) have no private storage backend.
- The maps on Screens 06, 07 and 08 are labelled "Illustrative map" in the design and must become a real map.
- `applicationId` is `com.example.surakshanet`.

## 2. Working rules

1. Start in **plan mode**. Produce a written audit (Section 3) and an implementation plan, then wait for my approval before editing.
2. Work in small, reviewable git commits on a new branch `maitri-release`. Never force-push. Never commit secrets (`google-services.json` service-account keys, `.env`, keystores, `local.properties`).
3. Keep existing working behaviour (SOS lifecycle, moving 1 km geofence, Safety-PIN closure, FCM, WebSocket, outbox worker, trusted contacts, circle sharing) unless the plan explicitly replaces it. Run the existing test suites after every phase: `./gradlew app:testDebugUnitTest` and the backend pytest suite.
4. **Never show fake data as real.** Names like Maya, Arjun, Asha, Ravi, "Rosewood Park", "8 min", "650 m" in the SVG are placeholders. Bind every value to real data, with proper empty, loading and error states.
5. Stop and ask me whenever you need an account, an API key, a payment, a DNS change or a console action. Tell me exactly what to click and which value to paste back. Do not invent keys.

## 3. Phase A — audit (no code changes)

For each of the 14 screens, in both themes, create `MAITRI_AUDIT_V2.md` with one row per visible control. Columns: screen, control, expected behaviour, current file/function, backend endpoint, status (works / partial / missing / fake), and fix needed. Compare rendered Compose output against the crop PNGs: colours, spacing, corner radii, icon geometry, typography and copy, all in light and dark. Note every pixel or copy mismatch.

## 4. Phase B — rename to Maitri

- `app_name` → `Maitri` in all `values*/strings.xml`. Rename theme `Theme.SurakshaNet` → `Theme.Maitri`.
- Change `namespace` and `applicationId` to the package in Section 0. Move the Kotlin sources to the new package directory and fix all imports, the manifest, ProGuard/keep rules, tests and `androidTest`.
- Replace the launcher icon (`mipmap/suraksha_launcher`) with an adaptive icon made from the Maitri logo in the SVG (Screen 01 mark), including a monochrome layer for Android 13+ themed icons.
- Rename user-visible "SurakshaNet" / "Suraksha" strings, notification channel names, the foreground-service notification text, and the backend service name and API title. Keep DB table names unless renaming them is trivial and done through an Alembic migration.
- Firebase: the new applicationId needs a new Android app registered in the Firebase project and a new `google-services.json`. Tell me the exact steps, including the debug/release/Play App Signing SHA-1 and SHA-256 fingerprints to add, then wait for me to drop the file in.

## 5. Phase C — UI fidelity (strictly follow the SVGs)

Match every screen exactly in light and dark, reusing the existing `Maitri*.kt` files where they're correct:
- Extract exact colours from the SVG and centralise them in `ui/theme/Color.kt` as light/dark token pairs. Get radii and sizes from the SVG geometry (frames are 390×844, so 1 SVG unit = 1 dp).
- Use exact extracted vector paths for the logo, wordmark and icons (ImageVector / XML vector drawables). Never use approximated Material icons where the SVG has its own.
- Font: identify it from the outlined glyphs. If you can't confirm it, propose the 2–3 closest Google Fonts with side-by-side screenshots and ask me to pick. Do not guess silently.
- The bottom nav is Home · Circle · Responder · Profile, with the active-tab styling exactly as drawn.
- Add Compose screenshot tests (Paparazzi or Roborazzi) for all 28 screen/theme combinations and diff them against the crop PNGs. Report the mismatch percentage per screen and fix until the visual diff is small.

## 6. Phase D — make every control functional

| Screen | Must work end-to-end |
|---|---|
| 01 Welcome | Get started → Login. Skip for signed-in users. |
| 02 Home | Notification bell opens a real inbox. SOS hold for 3 s starts an incident (with cancel). Share my trip (consented live sharing with circle, auto-expiry). Check in (pick a time; alert circle if missed — backend scheduled job). Manage → Circle. Add contact. |
| 03 Login | Log in / Sign up toggle. Country code picker. **Phone OTP via Firebase Phone Auth** with Play Integrity/reCAPTCHA. Email + password. Show/hide password. Forgot password. Terms/Privacy links open real pages. |
| 04 Volunteer | Yes / Not now. Upload 5 documents (camera or file, max 10 MB, PDF/JPG/PNG) to **private** storage with signed URLs. Download declaration template. Consent checkbox. Live "x of 5 uploaded" count. Submit for review. Application status shown to the user. **Admin review panel** (simple web page, admin-only auth) to approve or reject. Only approved volunteers receive nearby SOS requests. |
| 05 Circle | List, add, edit and remove contacts. Per-contact "SOS alerts on" toggle persisted to the server. Import from phone contacts (system picker). "Choose who gets your SOS" section. |
| 06 Responder modal | Approved volunteers within radius get a high-priority FCM full-screen notification. Approximate locality and distance only before acceptance. Accept / Decline. "Available for nearby requests" toggle. |
| 07 On your way | Real map with live route, ETA and distance (provider from Section 0). Bring in help (recruit more responders). Call (dial the victim's number via the dialer). Contact emergency services (dial 112). Coordinates wiped when the incident ends or access is revoked. |
| 08 SOS active | Live map with the victim and accepted responders. Live elapsed timer. Responders-accepted list with ETAs. Circle notified with real per-contact delivery status. Voice note and photo evidence uploaded to private storage with consent. End SOS requires the Safety PIN. |
| 09 Profile | Edit profile, Permissions, Test vibrations, Safety PINs, Language (shows current), Themes (Blush / light / dark / system, persisted), Notifications settings, Privacy & data (export my data, **delete my account** — Play requires both in-app and via a web link), Help & support (FAQ + contact email), Sign out. Build the screens not drawn in the SVG in the same visual language. |
| 10 Language | Search. English, Hindi, Bengali, Marathi, Tamil, Telugu with full `strings.xml` translations (mark them for human review). Save applies immediately via `AppCompatDelegate.setApplicationLocales` without losing an active SOS. |
| 11 Test vibration | Gentle / Steady / Strong patterns and Test vibration. Persist the chosen pattern and use it for real SOS alerts. Never sends an SOS. |
| 12 Permissions | Real state for Location (incl. background), Notifications, Contacts, Microphone and Camera. Tap a row to request it or deep-link to settings. Open device settings. Battery-optimisation exemption guidance. |
| 13 Safety PIN | 6-digit PIN + confirm, Show PIN, weak-PIN rejection, Save PIN to the existing secure endpoint, change/reset flow. |
| 14 Edit profile | Profile photo change (camera/gallery, cropped, private storage). Name, phone, email, city, medical notes. Save to `PUT /api/users/me` with an Alembic migration for any new fields. |

## 7. Phase E — production backend

- Remove the test-UID allowlist gating for normal users. Set `ENVIRONMENT=production` with strict config validation, keeping a separate staging environment for testing.
- Enable real SOS broadcasting **only to verified, available volunteers** and the user's circle, with rate limiting and abuse controls: per-user SOS throttling, false-alarm reporting, the ability to suspend a volunteer, and audit logs.
- Add the missing pieces: storage service (signed upload/download URLs), volunteer review workflow, check-in scheduler, trip-sharing expiry jobs, account deletion and data export endpoints, and retention jobs that purge SOS media and locations after a defined period.
- Security: Firebase ID-token verification on every route, row-level ownership checks, CORS locked down, secrets only in the host's secret store, HTTPS only, and request-size limits on uploads.
- Deployment: update `render.yaml` (or the chosen host's config) for an always-on paid instance plus managed Postgres with PostGIS and managed Redis. Add health checks, run migrations on deploy, add Sentry (or similar) error tracking and uptime monitoring with alerts to my email. Add a GitHub Actions CI pipeline that runs tests on every PR. Point the release `API_BASE_URL` to the production URL.
- Document everything in `DEPLOYMENT.md`, including step-by-step commands I can run, every environment variable, and how to roll back.

## 8. Phase F — Play Store readiness

- Release build: R8/minify on and tested, versionCode/versionName scheme, an upload keystore that I generate (give me the command; never commit it), and an AAB via `./gradlew bundleRelease`.
- targetSdk = the current Play requirement. Use foreground-service types correctly (`location`, plus `microphone`/`camera` if recording during SOS).
- If you use `ACCESS_BACKGROUND_LOCATION` or full-screen intents, prepare the in-app prominent-disclosure screens and the text and video script Play needs for the permission declarations.
- Write `PRIVACY_POLICY.md` and a hosted HTML version, an account-deletion web page, and Data Safety form answers (what's collected, why, shared with whom, encrypted in transit, deletable). Prepare store-listing copy in English and Hindi. Make a list of the screenshots I need (phone, 1080×1920+) and capture them from the emulator.
- Show a clear in-app disclaimer that Maitri does not replace 112, with one-tap 112 dialling.
- Finish with `RELEASE_CHECKLIST.md`: every console action I must do myself (Play developer account, app signing, internal testing track with 12+ testers for 14 days if my account is a new personal account, content rating, target audience, Data Safety, permission declarations).

## 9. Definition of done

- All 28 screenshot tests match the SVG crops, and every row in `MAITRI_AUDIT_V2.md` is "works".
- Android unit tests, instrumented tests and backend tests pass. A three-device end-to-end test (victim, circle member, verified volunteer) against **production** passes, and you report the results.
- The production backend is deployed, always-on, monitored, and its URL is baked into the release AAB.
- A signed release AAB builds, and `RELEASE_CHECKLIST.md` lists only the steps that need my own accounts.

Begin with Phase A and the plan. Do not edit code until I approve.
