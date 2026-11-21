# SurakshaNet — Native Android Women’s Safety Application

[![Android Test Suite](https://img.shields.io/badge/Android%20Tests-47%20Passed-brightgreen)](TEST_RESULTS.md)
[![Backend Test Suite](https://img.shields.io/badge/Backend%20Tests-44%20Passed-brightgreen)](TEST_RESULTS.md)
[![Total Tests](https://img.shields.io/badge/Total%20Automated%20Tests-91%20Passed-brightgreen)](TEST_RESULTS.md)
[![PostGIS](https://img.shields.io/badge/PostGIS-3.7.0%20Spatial-blue)](DATABASE_SCHEMA.md)
[![Design System](https://img.shields.io/badge/UI-Liquid%20Glass%20Compose-purple)](ARCHITECTURE.md)

SurakshaNet is a native Android women's safety platform with a **continuously moving 1 km SOS response network**. As a victim in distress travels, their emergency perimeter dynamically follows them, matching nearby verified responders via PostGIS `ST_DWithin` while keeping already-accepted responders connected regardless of distance.

---

## 📚 Complete Technical Documentation

| Document | Description |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System components, sequence diagrams, and real-time state machine |
| [SETUP.md](SETUP.md) | Local development, PostgreSQL/PostGIS, Firebase, and Android setup |
| [API_DOCUMENTATION.md](API_DOCUMENTATION.md) | REST APIs, request/response models, and WebSocket event specs |
| [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) | PostgreSQL / PostGIS schemas, spatial indexes (GiST), and ER diagram |
| [SECURITY.md](SECURITY.md) | Cryptographic controls, Argon2id, token verification, and privacy barriers |
| [PRIVACY.md](PRIVACY.md) | Ephemeral telemetry handling, retention policies, and zero-coordinate leaks |
| [TEST_RESULTS.md](TEST_RESULTS.md) | Detailed automated test evidence (91 / 91 tests passing) |
| [DEMO_GUIDE.md](DEMO_GUIDE.md) | Three-phone live demonstration script and coordinate run sheet |
| [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) | Operational realities, OEM battery management, and GPS limitations |

---

## 🌟 Key Features & Innovations

1. **Continuously Moving 1 km Geofence (PostGIS 3.x)**:
   - Dynamic geodesic perimeter evaluation (`ST_DWithin` on `geography(POINT, 4326)` in metres).
   - Reevaluates automatically on victim movement (> 30m) or periodic interval (15s).
   - Discover newly eligible community members as the victim moves into new neighborhoods.
2. **Persistent Responder Authorization**:
   - Once a responder accepts an emergency invitation, they **remain connected** even if the victim moves beyond the initial 1 km radius.
3. **Trusted Emergency Contacts & Zero-Permission SMS/Dialer Fallback**:
   - Granular notification preferences per contact: SOS alerts, coordinates link, live tracking, and resolution notification.
   - Zero broad permissions: no `SEND_SMS`, `READ_CONTACTS`, or `CALL_PHONE` runtime permissions.
   - Native SMS fallback uses Android's `ACTION_SENDTO` with `smsto:` URI (explicit user review).
   - Emergency dialing uses Android's `ACTION_DIAL` with `tel:112` (explicit user confirmation).
   - Realistic delivery status tracking: `NOT_REQUESTED`, `QUEUED`, `SENDING`, `PROVIDER_ACCEPTED`, `DELIVERED_WHEN_CONFIRMED`, `FAILED`, `UNKNOWN`.
4. **High-Priority Push Delivery & Multi-Pulse Vibration**:
   - High-importance notification channel (`urgent_sos_v1`).
   - Custom **5.2-second multi-pulse emergency vibration pattern** designed to penetrate bags and pockets.
   - Zero victim coordinates in push notifications to protect privacy.
5. **Secure Real-Time Tracking & WebSocket Hub**:
   - Single-use, 60-second expiring tickets (`POST /api/incidents/{id}/ws-ticket`) preventing credential exposure.
   - Monotonic GPS fix sequencing and immediate coordinate memory purge upon incident resolution.
6. **Argon2id Safety PIN & Coercion Safeguards**:
   - 6-digit memory-hard PIN protection with 5-minute lockout after 5 failed attempts.
   - Silent Duress PIN simulation: triggers `ESCALATION_REQUIRED` while returning an acknowledged confirmation to protect victims under coercion.
7. **Premium iOS-Inspired Liquid Glass Design System**:
   - Translucent frosted glass surfaces, 45-degree specular gradient borders, and diffuse ambient shadows.
   - Seamless Dark and Light modes with persistent preference storage.
   - Official pale-pink and magenta SurakshaNet smiling-face shield brand mark integrated across launcher, splash, login, onboarding, header, and profile.

---

## 🚀 Quick Start

### Android Application
- **Build Requirements**: Android Studio (Koala/Ladybug+), JDK 17+, Android SDK 37 (minSdk 26).
- **Run Unit Tests**:
  ```bash
  ./gradlew :app:testDebugUnitTest
  # 47 passed, 0 failed
  ```
- **Build Demonstration APK**:
  ```bash
  ./gradlew :app:assembleDebug
  # Artifact: app/build/outputs/apk/debug/app-debug.apk (~24.7 MB)
  ```

### Backend & Database
- **Requirements**: Python 3.11+, PostgreSQL 15+ with PostGIS 3.x.
- **Run Standalone Isolated Test Suite**:
  ```bash
  cd backend
  python tests/run_isolated.py
  # 44 passed, 0 failed (PostGIS 3.7.0 & Alembic 0001-0007 verified)
  ```
- **Start FastAPI Development Server**:
  ```bash
  uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
  ```

---

## 🔒 Demonstration & Safety Notice

SurakshaNet is configured strictly for **controlled demonstration and research testing**:
- All emergency dispatches are restricted to authorized accounts in `TEST_ACCOUNT_UIDS`.
- Public emergency broadcasting is intentionally disabled.
- In actual emergency situations, always dial official authorities directly (e.g., **112** in India, **911** in the US).
