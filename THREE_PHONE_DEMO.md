# Controlled three-phone lifecycle rehearsal

Use three consenting, email-verified Firebase accounts from the configured project. Privately allowlist their real UIDs in Render. Assign A as victim, B as initial responder, and C as newly eligible responder. Each phone needs Google Play Services, network access and notification permission. A SIM is unnecessary for this app-only rehearsal.

## Before starting

1. Record the successful Render deployment SHA and compare it with the reviewed backend commit. Confirm /health and /ready return 200. A health response alone does not identify the deployed revision.
2. Install the tested debug APK on each phone. Confirm the HTTPS API origin, authenticated profile sync and backend-confirmed FCM registration.
3. Use Send Test Alert to This Phone. Record provider acceptance, handset receipt, display and tap separately. A self-test tap opens the app without an invitation acceptance screen.
4. A checks server-side Safety PIN status and enrolls or recovers a six-digit primary PIN privately. Recovery requires recent Firebase authentication. Keep the PIN out of notes and recordings.
5. B and C opt in and become available. Keep all three accounts' location samples fresh with acceptable accuracy.
6. Use Android mock-location facilities only on designated test devices, or the isolated deterministic PostGIS suite. All participating samples must use the same mock/real mode. Synthetic test coordinates must be labeled as such.

## Moving-radius sequence

Distances below are relative positions for a controlled test along one line, measured in metres. Use the project's deterministic A–B–C–D integration test when physical devices or mock-location setup are unavailable.

| Stage | Victim A | Responder B | Responder C | Expected evidence |
|---|---|---|---|---|
| Initial | 0 | 400 | 2200 | B invited, C outside radius |
| Move | 1800 | 400 | 2200 | B keeps its accepted grant, C invited |
| Further movement | Continue with valid fresh fixes | Keep refreshing location | Keep refreshing location | New invitations deduplicated, accepted grants persist |

1. A explicitly selects server sharing and holds SOS for two seconds. Wait for a server-confirmed incident ID. Distinguish local collection and pending creation from remote activation.
2. B receives a persisted invitation. Before acceptance, backend location access must be denied.
3. B taps Accept and Help. Require backend ACCEPTED, then an authorized snapshot and WebSocket connection. Initial acceptance checks current radius eligibility.
4. Move A to 1800 m. B remains authorized despite now being 1400 m away.
5. C receives a new invitation and accepts while inside the current radius. Verify both phones show the latest valid timestamped location.
6. Change B's assistance status to ON_THE_WAY or HELPING and confirm A sees the server-confirmed state. Acceptance itself means ACCEPTED.
7. Briefly interrupt a responder's connection. Show delayed/offline status. After reconnecting, require an authoritative snapshot. Exhausted automatic retries expose a manual retry action.
8. A selects End SOS, enters the private primary PIN, and waits for confirmed backend closure. The backend uses Argon2id PIN hashing.
9. Verify pending invitations are invalidated, grants revoked, precise coordinates cleared, and new tracking requests rejected. Confirm A's foreground GPS service stops and its screen returns to idle.
10. Record each observed result. Do not infer notification display from FCM acceptance or cloud deployment from local tests.

## Failure handling

Missing PIN opens authenticated enrollment. Recovery changes the PIN while leaving the incident active. An incorrect PIN consumes a server-controlled attempt. Network failure leaves closure unconfirmed and permits a deliberate retry. Avoid starting a live cloud rehearsal until the owner can complete PIN closure.

The Android WebSocket uses a short-lived single-use ticket in the Authorization header and TLS. Keep tickets out of URLs, logs and recordings. PostgreSQL decides access even when Redis events are delayed or interrupted.

Render Free can sleep and introduce cold-start delays. This controlled development demonstration does not establish emergency-service reliability or an official 112 dispatch connection. Phone dialer fallback requires the user's separate action.

## Evidence to retain

Record commit SHA, APK SHA-256, device models, API status, coarse relative test positions, invitation/acceptance outcomes, GPS timestamps, closure confirmation and access-denial results. Keep passwords, PINs, Firebase UIDs, device tokens and private coordinates out of the report. Current execution results are in TEST_RESULTS.md.
