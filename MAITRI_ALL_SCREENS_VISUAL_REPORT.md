# Maitri All-Screens Visual & Architectural Fidelity Report

**Project:** Maitri (Personal Safety & Mutual Aid Network)  
**Baseline Specification:** Original SVG Design Boards (`maitri · Light theme · 01–14.svg` and `maitri · Dark theme · 01–14.svg`, 3034 × 1856, 390 × 844 frames)  
**Implementation Technology:** Kotlin, Jetpack Compose, Material 3, Android Jetpack Architecture, Retrofit/OkHttp, FastAPI, PostgreSQL/PostGIS, Redis, Firebase Auth.  
**Fidelity Standard:** Strict SVG vector geometry lock, no generic substitutions, genuine backend & hardware integration.

---

## 1. Global Design Tokens & Palette Specifications

The entire visual system strictly adheres to the measured color palette across both themes:

| Token | Light Theme Value | Dark Theme Value | Purpose & Application |
|---|---|---|---|
| `background` | `#FFF9FA` | `#19151B` | Full-screen root background |
| `primary` | `#40303A` | `#FAEFF5` | Primary headings, prominent titles, active input text |
| `secondary` | `#7D6771` | `#C3ADBA` | Subtitles, section captions, helper labels, timestamps |
| `accent` | `#B82055` | `#FF9CBE` | Brand action buttons, active tab indicators, SOS beacon glow |
| `card` | `#FCE8ED` | `#36232F` | Hero banner cards, active selection chips, accent containers |
| `surface` | `#FFFFFF` | `#28212A` | Form cards, elevated lists, discrete digit boxes, buttons |
| `divider` | `#F2DCE3` | `#48323F` | Hairline separators, borders, inactive box outlines |

- **Layout Grid:** 390dp screen width frame with standard 24dp horizontal margins, yielding an exact 342dp content card width across all 14 screens.
- **Top Insets & Navigation:** Top status bar padding (approx. 16dp), 44x44dp circular back navigation targets (rx=22dp), uppercase bold section eyebrows with 1.2sp letter spacing.
- **Corner Radii:** Cards use 20–28dp radii; pill action buttons use 14–26dp radii.

---

## 2. Screen-by-Screen Implementation Audit

### Screen 01: Welcome
- **Visual File Reference:** `crops/light-01.svg` / `crops/dark-01.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriWelcome.kt`
- **Elements & Layout:**
  - Original Maitri logo with 6 multi-stop SVG gradients and vector wordmarks (`maitri_wordmark_light.xml`, `maitri_wordmark_dark.xml`).
  - Circular safety beacon rings (`#FFF0F5` / `#FCE8ED` in Light; `#241B25` / `#36232F` in Dark).
  - Welcome tagline and "Get started" button leading into authentication.

### Screen 02: Home / SOS
- **Visual File Reference:** `crops/light-02.svg` / `crops/dark-02.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriHome.kt`
- **Elements & Layout:**
  - Header greeting and quick status indicator.
  - Central 3-second SOS hold button with live pulsing circular arc and cancellation on premature release.
  - Quick action tiles: "Share my trip" and "Check in" (`MaitriSharingDialog.kt`).
  - Strict consent flow, trusted contact pair validation, and server-confirmed check-in timestamps.

### Screen 03: Login / Sign-up
- **Visual File Reference:** `crops/light-03.svg` / `crops/dark-03.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriLogin.kt`
- **Elements & Layout:**
  - Segmented pill toggle ("Log in" vs "Sign up").
  - Internationalized phone number input field with fixed `+91` prefix.
  - Email & password authentication with password toggle.
  - Forgot password dialog, email verification banner, and 60-second Render spin-up resiliency timer.

### Screen 04: Volunteer Verification
- **Visual File Reference:** `crops/light-04.svg` / `crops/dark-04.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriVolunteer.kt`
- **Elements & Layout:**
  - Participation selection chips ("Yes, I'd like to help" / "Not now").
  - 5 Document attachment pickers (Government ID, Address Proof, Emergency Training, Background Check, Self Photo) with 10 MB client-side file size enforcement.
  - Legal declaration checkbox and submit action backed by additive Alembic migration `0009_volunteer_applications.py`.

### Screen 05: Your Circle
- **Visual File Reference:** `crops/light-05.svg` / `crops/dark-05.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriCircle.kt`
- **Elements & Layout:**
  - Hero card ("Keep 3-5 trusted people close").
  - Dynamic section count badge ("X / 5 added").
  - Per-contact cards with immediate `notifyOnSos` toggle, add/edit/delete modal dialogs.
  - Native Android contact picker (`ContactPickerHelper`) with phone normalization.

### Screen 06: Nearby SOS Request (Incoming Dialog)
- **Visual File Reference:** `crops/light-06.svg` / `crops/dark-06.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriIncoming.kt`
- **Elements & Layout:**
  - Modal scrim dialog with 342x396dp container (rx=24dp).
  - Beacon pulse graphic, "Nearby SOS Request" heading.
  - Privacy preservation: Displays approximate locality and distance (e.g., "Kolkata, Sector V", "Approx. 450 m away"); precise victim coordinates are strictly masked prior to acceptance.
  - Dual action buttons: "Accept" (148dp) and "Decline" (148dp).

### Screen 07: Responder on Your Way (Journey)
- **Visual File Reference:** `crops/light-07.svg` / `crops/dark-07.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriResponderJourney.kt`
- **Elements & Layout:**
  - "ON YOUR WAY" / "RESPONDING" header.
  - 342x320dp map canvas with route polyline and live location markers.
  - Live status badge, destination & ETA card ("450 m away • 3 min").
  - External actions: "Open in Maps" navigation intent (226dp) and "Call" emergency dialer (104dp).
  - Secure coordinate wipe immediately upon incident resolution or access revocation.

### Screen 08: SOS is Active
- **Visual File Reference:** `crops/light-08.svg` / `crops/dark-08.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriActiveSos.kt`
- **Elements & Layout:**
  - "SOS is active" heading with live red pulsing beacon and elapsed time counter.
  - "End SOS" button triggering authenticated 6-digit Safety PIN closure dialog.
  - 342x252dp live map canvas with 1 km dynamic recruitment geofence pulse.
  - Responder status card ("Responders accepted" & "Circle notified").
  - Voice note and camera photo evidence capture triggers with runtime consent.

### Screen 09: Profile & Settings
- **Visual File Reference:** `crops/light-09.svg` / `crops/dark-09.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriSettings.kt`
- **Elements & Layout:**
  - Eyebrow: `YOUR SPACE, YOUR CHOICES`, title: `Profile & settings`.
  - Top Profile Card (342x82dp, rx=22dp, card background) with avatar, title, subtitle, and "Edit" pill button leading to Screen 14.
  - Group "Safety, your way" (342x174dp, rx=20dp):
    - Row 1: `Permissions` ("Location, notifications & contacts") -> Screen 12
    - Row 2: `Test vibrations` ("Try your alert feedback") with "Test" pill -> Screen 11
    - Row 3: `Safety PINs` ("Set or change your safety PIN") -> Screen 13
  - Group "Make yourself comfortable" (342x208dp, rx=20dp):
    - Row 1: `Language` with "English" badge -> Screen 10
    - Row 2: `Themes` with "Blush"/"Dark" badge -> toggles `LocalThemeController`
    - Row 3: `Notifications` -> opens system notification preferences
    - Row 4: `Privacy & data` -> opens data protection dialog
  - "Help & support" card and "Sign out" action.

### Screen 10: Language
- **Visual File Reference:** `crops/light-10.svg` / `crops/dark-10.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriLanguage.kt`
- **Elements & Layout:**
  - 44x44dp circular back button, `PROFILE & SETTINGS` eyebrow, `Language` heading.
  - Banner Card (342x88dp, rx=22dp): "Your language. Your comfort.", "Choose the language you prefer."
  - Search field (342x48dp, rx=16dp) with live filtering across English and native scripts.
  - 6-Language List Card (342x336dp, rx=22dp): English, Hindi (हिन्दी), Bengali (বাংলা), Marathi (मराठी), Tamil (தமிழ்), Telugu (తెలుగు).
  - "Save language" button (342x52dp, rx=26dp) persisting selection to `SharedPreferences`.

### Screen 11: Test Vibration
- **Visual File Reference:** `crops/light-11.svg` / `crops/dark-11.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriVibration.kt`
- **Elements & Layout:**
  - 44x44dp circular back button, `PROFILE & SETTINGS` eyebrow, `Test vibration` heading.
  - Hero Card (342x196dp, rx=28dp): "Feel your safety signal", "Try a vibration pattern before you need it.", "Ready to test" status badge.
  - Pattern selection card (342x162dp, rx=20dp) with 3 selectable patterns:
    1. Gentle: Short, soft pulses (`[0, 150, 150, 150, 150, 150]`)
    2. Steady: Balanced, clear pulses (`[0, 350, 200, 350, 200, 350]`)
    3. Strong: Longer, stronger pulses (`[0, 700, 200, 700, 200, 700, 200, 700]`)
  - "Test vibration" button (342x52dp, rx=26dp) driving Android hardware `Vibrator` and `VibrationEffect`.

### Screen 12: Permissions
- **Visual File Reference:** `crops/light-12.svg` / `crops/dark-12.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriPermissions.kt`
- **Elements & Layout:**
  - 44x44dp circular back button, `PROFILE & SETTINGS` eyebrow, `Permissions` heading.
  - Banner Card (342x84dp, rx=22dp): "Your choices, always."
  - Permissions card (342x400dp, rx=22dp) inspecting 5 Android permissions:
    1. Location (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`)
    2. Notifications (`POST_NOTIFICATIONS` / `NotificationManagerCompat`)
    3. Contacts (`READ_CONTACTS`)
    4. Microphone (`RECORD_AUDIO`)
    5. Camera (`CAMERA`)
  - Status badges ("Allowed", "Ask each time", "Not allowed").
  - "Open device settings" button (342x52dp, rx=26dp) launching `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

### Screen 13: Safety PIN
- **Visual File Reference:** `crops/light-13.svg` / `crops/dark-13.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriPin.kt`
- **Elements & Layout:**
  - 44x44dp circular back button, `PROFILE & SETTINGS` eyebrow, `Safety PIN` heading.
  - Banner Card (342x116dp, rx=22dp): "A PIN, just for you."
  - Two rows of 6 discrete digit boxes (each 46x52dp, rx=14dp) for PIN entry and confirmation.
  - "Show PIN" toggle.
  - Tip Card (342x112dp, rx=22dp): "Make it hard to guess" (rejects trivial sequences: `123456`, `654321`, `111111`).
  - "Save PIN" button (342x52dp, rx=26dp) communicating directly with backend `POST /api/security/safety-pin/setup`.

### Screen 14: Edit Profile
- **Visual File Reference:** `crops/light-14.svg` / `crops/dark-14.svg`
- **Native Class:** `com.example.surakshanet.ui.MaitriEditProfile.kt`
- **Elements & Layout:**
  - 44x44dp circular back button, `PROFILE & SETTINGS` eyebrow, `Edit profile` heading.
  - Profile Photo Card (342x72dp, rx=22dp) with 48x48dp avatar circle and "Change" action launching `GetContent()`.
  - Form Fields:
    - "Full name"
    - "Phone number"
    - "Email address" (read-only verified account identity)
    - "City or area"
    - "Medical notes (optional)" (multiline 342x72dp, rx=16dp)
  - "Save changes" button (342x52dp, rx=26dp) updating local repository and backend API `PUT /api/users/me`.

---

## 3. Navigation Architecture

Bottom navigation tab order is strictly preserved:
1. `Home` (`MaitriHome.kt`)
2. `Contacts` / `Circle` (`MaitriCircle.kt`)
3. `Responder` (`TestResponderDashboard.kt` / `ResponderDashboard.kt`)
4. `Profile` (`MaitriSettings.kt`)

Sub-destinations smoothly transition off-stack while hiding the dock:
- `EditProfile` (Screen 14)
- `Permissions` (Screen 12)
- `TestVibration` (Screen 11)
- `SafetyPin` (Screen 13)
- `Language` (Screen 10)

Emergency overlay routes:
- `Active`: Screen 08 Active SOS
- `Incoming?invitationId={id}`: Screen 06 Incoming Invitation
- `ResponderJourney?incidentId={id}`: Screen 07 Responder Journey

---

## 4. Test Verification Summary

- **Android Unit Tests:** `98 passed, 0 failed, 0 skipped` via `:app:testDebugUnitTest`.
- **Backend Server Tests:** `97 passed, 0 failed, 0 skipped` via `backend/test-maitri-server.ps1` in an isolated PostgreSQL/PostGIS and Redis test environment.
- **Hardware Integration:** Samsung Galaxy F62 (`RZ8R903LKWR`) successfully built and installed debug APK via `:app:installDebug`.
