# Maitri beta test report

Build: `0.9.0-beta.1` (versionCode 900), signed release APK, R8 on.
Server: Render Free, branch `beta-complete`, `ENVIRONMENT=beta`, Neon + R2.

## Devices

| Phone | Model | Android | Notes |
|---|---|---|---|
| A | Samsung Galaxy F62 (SM-E625F) | 14 | One UI |
| B | Xiaomi/Redmi 22041216I | 14 | HyperOS 2.0, text size 150%, enlarged display size |
| Emulator | Pixel (API 37 image) | 17 | release launch check |

## Two-phone run, 2026-11-24

| # | Test | Result | Notes |
|---|---|---|---|
| 0 | Install signed release, Google sign-in (both phones) | ✅ | Release key SHA-1 registered in Firebase |
| 0 | Runtime permissions via Profile → Permissions | ✅ | Android dialogs shown; status lines update |
| 1 | Medical note saved and read back | ✅ | `MEDICAL_NOTES_KEY` set |
| 2 | Profile photo upload | ✅ | R2 storage |
| 3 | Circle invite code A → B | ✅ | |
| 4 | Real SOS from A (hold 3 s) | ✅ | Beta mode accepts real SOS |
| 5 | Alert on B with the screen locked; map shows A | ✅ | Xiaomi: Autostart on, battery No restrictions |
| 6 | Live link opens | ✅ | `LIVE_LINK_SECRET`, `PUBLIC_URL` |
| 7 | Voice note + photo during SOS; B plays and views them; SOS ended with PIN | ✅ after fixes | See bugs 1–3 |
| 8 | B's inbox shows "SOS ended"; media still viewable | ✅ | |
| — | Hourly SOS limit message | ✅ | Hit at 3/hour; raised to 20 for testing |

## Bugs found and fixed in this run

1. **Startup crash in the first release build.** R8 removed WorkManager's Room database constructor.
   Fixed with `app/proguard-rules.pro` (`173a1f0`).
2. **Take photo closed the app.** FileProvider had no root for `files/sos-media/` (`b760b6e`).
   Now covered by `FileProviderPathsTest`.
3. **Circle couldn't see SOS media.** It was owner-only by design. Changed so that the alerted circle members can
   list, play and view it, and volunteer responders can't (`3977fca`, `f5a9758`).
4. **Play/Stop on phone B crashed.** A local `stop()` shadowed `MediaPlayer.stop()` and recursed (`6c8811e`).
5. **Photo upload status shown under Voice.** The SOS screen now shows each tile's uploads separately (`6c8811e`).

## Open

- **Large text (150%) breaks Home on phone B:** the Beta label is squeezed into a vertical column, and the
  cards clip their text. This is in P10.
- **Before inviting testers, delete `SOS_PER_HOUR` in Render** (or set it to 3).
- **Not run yet:** the API 26 emulator, check-in reminders, and responder (volunteer) flows end to end.
