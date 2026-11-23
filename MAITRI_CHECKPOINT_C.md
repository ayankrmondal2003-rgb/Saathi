# Maitri checkpoint C

Screens 12 Permissions, 13 Safety PIN and 14 Edit profile now use the approved Light and Dark crop geometry. Each screen draws its static shapes and icons from a vector drawable converted from `design-reference/crops/{light,dark}-{12,13,14}.svg`, with outlined text, the status bar and the home indicator removed. Native text and controls are placed at the crop coordinates through checkpoint B's `SettingsBoard`. On screen 13, the PIN boxes, the Show PIN checkbox and the Save button are native stateful controls, not artwork. Screen 09's Edit, Permissions and Safety PINs rows open these screens.

## Screen 12 — Permissions

Each row reads live Android state and refreshes on resume:
- Location: fine, approximate-only or none.
- Notifications: `POST_NOTIFICATIONS` plus whether notifications are enabled at system level.
- Contacts, Microphone, Camera.

Tapping a row shows Android's runtime prompt when Android will still show one. Otherwise it opens the app or notification settings page.

Maitri does not declare `READ_CONTACTS`, `RECORD_AUDIO` or `CAMERA`. Contacts come from the system contact picker, and SOS voice and photo capture use the device's recorder and camera apps. Those three rows therefore show "Not needed" and explain why when tapped, instead of reporting a false state. No permissions were added to the manifest.

## Screen 13 — Safety PIN

The screen loads `/api/security/safety-pin/status` and offers one of four modes:
- **Enrollment** (`setup`) when no PIN exists.
- **Change**: the current PIN first, then the new PIN and confirmation (`change`).
- **Recovery**: from "Forgot your PIN?". Within four minutes of sign-in it calls `recover` directly. Otherwise it re-authenticates with the account password, refreshes the token and then calls `recover`, which satisfies the server's 5-minute `auth_time` rule.
- **Locked** state, with recovery offered.

The client rejects repeated and sequential PINs before sending, using the server's rules. PIN digits are held only in memory, never in saved instance state.

Incident closure on the Active SOS screen now uses the shared `pinFailure` mapping, so lockout, connection and attempts-remaining messages are accurate. If no PIN is enrolled, the dialog offers "Set Safety PIN", which opens screen 13.

## Screen 14 — Edit profile

- Name, phone and city save through a new authenticated `PATCH /api/users/me` (migration `0010`). It never touches the responder opt-in or availability flags. The existing `PUT` endpoint can revoke responder authorizations and is no longer used by this screen.
- Email is the sign-in identity and is read-only.
- Medical notes are optional and stored only on the phone. They are AES-256-GCM encrypted with a non-exportable Android Keystore key, bound to the account uid, and kept in no-backup storage. They are never sent to the server.
- The profile photo is decoded, downscaled to 512 px and re-encoded, which removes EXIF location. It is stored privately on the device.

All new copy is translated into all six languages. That is 91 strings per locale, written by the model without native-speaker review.

## Verification (local only)

- Debug APK built.
- 120 JVM tests passed with no skips: 13 new, and 3 stale screen 12–14 tests that only checked local helper functions were removed.
- 24 device tests passed with no skips on SM-E625F, Android 13. The 6 new tests cover:
  - live permission states and the explanation dialog;
  - PIN strength, mismatch and masking in both themes;
  - profile validation, with saving refused without the matching account session;
  - Keystore round trip with no plaintext on disk;
  - production-graph navigation from Profile to screens 12–14;
  - screenshot capture.
- Isolated server suite: 109/109 passed, including new tests for the `PATCH` endpoint and the PIN change and recovery contract. The PowerShell wrapper hung in this agent's shell when its output was piped, so the same runner was invoked directly with Python.
- Device screenshots were compared side by side with the reference crops in both themes. No automated pixel diff was run.
- Screens 01–11 retained their implementations.
- No push or deployment.

## Remaining blockers

1. **Deployment:** migration `0010` and the `PATCH /api/users/me` endpoint must be deployed before screen 14 can save on Render. Until then the screen shows its save-failed message.
2. **Medical notes do not reach responders.** Sharing them needs consent design and server-side encryption key management, which do not exist yet.
3. **Profile photos are device-only.** No private object storage is configured. This is the same blocker as the volunteer documents.
4. **Email cannot be changed** from screen 14. That would need a Firebase verify-before-update flow.
5. **PIN recovery for accounts without a password** (none can reach the backend today, because it requires a verified email) must sign out and back in first.
6. **Server error text is English.** Messages returned by `pinFailure` are not localized.
7. **Font identity is still unconfirmed** (inherited). The header eyebrow letter-spacing from checkpoint B is tighter than the crop.
8. **Pre-existing lint errors:** `lintDebug` reports 12 errors, all outside checkpoint C files (`NotificationHelper`, `SafetyApp` toast constants, `IncidentScreens`, untranslated `maitri_strings`/`app_name`).
9. **Duress PIN closure** on the Active SOS screen still shows "Closure has not been confirmed". That behaviour is unchanged and needs a product decision.
