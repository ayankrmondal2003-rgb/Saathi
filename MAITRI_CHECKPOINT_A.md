# Maitri checkpoint A

Screens 06–08 use artwork extracted from the approved Light and Dark crops with native controls and runtime labels. The Responder tab exposes availability, the application and the account’s invitation inbox. A stale or duplicate GPS 409 skips that rejected timestamp and continues listening for a newer fix.

| Path | Guard or result |
|---|---|
| Responder → 06 | Current pending invitation. Precise location withheld. |
| Accept or resume → 07 | Confirmed acceptance and an active authorized responder membership for the same incident. Recheck on foreground and while open. |
| Journey | Authorized location updates, assistance status, maps, dialer and backend recruitment. Closure or loss of access exits. |
| Home → 08 → End SOS | Matching active incident, owner snapshot and six-digit PIN. Clear the session only on confirmed closure for that incident. |

Verified locally: 110 Android JVM tests, 107 isolated backend tests and 5 Samsung device tests passed. The device suite rendered all six assets and exercised both invitation controls. A display-dependent instrumentation failure preceded the successful rerun. No pixel-diff comparison or paired live-device emergency drill was performed.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Bring in help uses the new local backend endpoint. No deployment or push.

## Re-audit (2026-11-23, after checkpoint C)

The re-audit confirmed the following:
- The 06–08 drawables contain only fixed copy (titles, buttons, tabs, "Illustrative map"). The design's sample place, distance and ETA text is not painted.
- Screens 06–08 show only server values or explicit "unavailable" text.
- The GPS 409 recovery matches the backend's `X-Error-Code: STALE_OR_DUPLICATE_LOCATION` contract.
- Journey access is re-checked against the incident snapshot.
- Closure requires a confirmed server result.

Fixes:
- Screen 06 now uses the shared `confirmedInvitationResponse` and `invitationFailure`. Previously every 403 was reported as a stale location and every 409 as "already answered", hiding "responder unavailable" and "incident inactive".
- The Responder tab showed a blank screen in demo mode or without an API URL. It now shows the responder artwork and states that responder mode is unavailable.
- The 4 `WrongConstant` lint errors in `SafetyApp` toasts are fixed.

Verified locally: 125 JVM tests (5 new in `MaitriCheckpointATest`) and 24 device tests on SM-E625F passed with no skips. The debug APK built. Backend code was unchanged; its last isolated run passed 109/109.

Open items:
- A paired two-phone live drill has not been run.
- No pixel diff was made for 06–08.
- The responder's own location is shared only while the Responder tab is open, not during the journey.
- Duress-PIN closure wording needs a product decision (see checkpoint C).
