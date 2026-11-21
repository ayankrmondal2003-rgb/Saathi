# SurakshaNet — System Architecture

## 1. System Overview

SurakshaNet is a native Android personal safety application with an asynchronous, high-concurrency backend powered by FastAPI, PostgreSQL 15+, and PostGIS 3.x. The platform introduces a **continuously moving 1 km SOS response network**: as a victim in distress travels, their active emergency perimeter dynamically follows them, matching nearby verified responders while keeping already-accepted responders connected regardless of subsequent distance.

```
                                  +---------------------------------------+
                                  |         SurakshaNet Android           |
                                  |  - Kotlin & Jetpack Compose (M3)      |
                                  |  - Liquid Glass Design System         |
                                  |  - Native FusedLocationProvider FGS   |
                                  |  - FCM High-Priority Emergency Alert  |
                                  |  - OkHttp Real-Time WebSocket Client  |
                                  +-------------------+-------------------+
                                                      |
                             HTTPS REST (Auth)        |        WSS (Single-Use Ticket)
                             +------------------------+------------------------+
                             |                                                 |
                             v                                                 v
+-----------------------------------------------------+       +------------------------------------+
|               FastAPI Backend Engine                |       |      RealtimeHub (WebSockets)      |
|  - Firebase Admin ID Token Verification             |<----->|  - /ws/incidents/{incident_id}     |
|  - Strict Allowlisted Test Account Enforcement      |       |  - 60s Single-Use Ticket Auth      |
|  - Dynamic 1 km Moving Geofence (ST_DWithin 4326)   |       |  - Monotonic Sequence Streaming    |
|  - Transactional Notification Outbox & FCM          |       |  - Instant Access Revocation       |
|  - Responder Status & Coordination State Machine    |       |  - Zero Responder Coordinate Leak  |
|  - Argon2id Safety PIN & Duress Resolution Engine   |       +------------------------------------+
+--------------------------+--------------------------+
                           |
                           v
+-----------------------------------------------------+
|             PostgreSQL 15+ / PostGIS 3.x            |
|  - Spherical Geography (WGS84 EPSG:4326)            |
|  - GiST Indexes (ix_open_center, ix_responder_gist) |
|  - Transactional Isolation & Row Locking            |
|  - Comprehensive Incident & Event Audit Trails      |
+-----------------------------------------------------+
```

---

## 2. Core Architectural Components

### 2.1 Android Application Architecture
- **UI Framework**: 100% Jetpack Compose with custom Liquid Glass design tokens, dark/light theme switching, and full WindowInsets / edge-to-edge layout.
- **State Management**: Unidirectional Data Flow (UDF) via MVVM (`SafetyViewModel`, `AuthViewModel`) using Kotlin Coroutines `StateFlow` and Compose `collectAsStateWithLifecycle`.
- **Location Tracking Subsystem**:
  - `FusedLocationSource`: Google Play Services `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY`, 3s interval, 1s fastest interval, 5m displacement filter.
  - `SosLocationService`: Native Android Foreground Service running with `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION`, registered `PROVIDERS_CHANGED_ACTION` broadcast receiver, and `FirebaseAuth.AuthStateListener` for automatic shutdown upon sign-out.
  - `LocalTrackingRepository`: In-memory and SharedPreferences-backed tracking session coordinator with offline SQLite queue replay (`TestIncidentSync`).
- **Emergency Notifications & Background Wakeup**:
  - `SosFirebaseMessagingService`: Background Firebase Cloud Messaging listener. Validates data payload, enforces zero-coordinate leak policy, and delegates to WorkManager.
  - `InvitationNotificationWorker`: Expedited WorkManager task with network constraints and exponential backoff.
  - `NotificationHelper`: Manages notification channel `urgent_sos_v1` (`IMPORTANCE_HIGH`), 5.2s multi-pulse emergency vibration pattern (`[0, 700, 200, 700, 200, 700, 200, 700, 200, 700, 200, 700]`), and notification deduplication.

### 2.2 Backend Architecture
- **Framework**: FastAPI (asynchronous ASGI) with Pydantic v2 schemas and strict typing.
- **Database Access**: SQLAlchemy 2.0 async engine with `asyncpg` driver and GeoAlchemy2 geography types.
- **Migrations**: Alembic with versioned DDL migrations ensuring consistent schema evolution.
- **Authentication & Security Middleware**:
  - Firebase Admin SDK verification checking cryptographic signatures, audience, expiry, email verification, and token revocation (`check_revoked=True`).
  - Strict account allowlisting (`TEST_ACCOUNT_UIDS`) preventing unauthorized public broadcasting during testing.
- **Moving Geofence Engine (`app/services/moving_geofence.py`)**:
  - Two-way geospatial matching: triggers on victim position shift (>30m) or periodic interval (15s), and on responder location / availability changes.
  - Native PostGIS `ST_DWithin` on `geography(Point, 4326)` for true spherical geodesic distance calculation in metres.
  - Row-level locking (`FOR NO KEY UPDATE ... SKIP LOCKED`) and `ON CONFLICT DO NOTHING` ensuring idempotency.
- **Real-Time WebSocket Hub (`app/services/realtime.py`)**:
  - Dedicated in-memory connection manager tracking active incident rooms and monotonic sequence counters.
  - Single-use, 60-second expiring tickets issued via `POST /api/incidents/{id}/ws-ticket` avoiding credential exposure in WebSocket URLs.
  - Immediate revocation broadcasting and socket closure upon incident resolution or responder removal.
- **Safety PIN & Deactivation Lifecycle (`app/services/safety_pin.py`)**:
  - Memory-hard `Argon2id` password hashing (`time_cost=2`, `memory_cost=65536`, `parallelism=1`, `hash_len=32`).
  - Strict PIN policy: 6 digits, non-sequential, non-repeating, with distinct primary and duress codes.
  - Cooldown lockout: maximum 5 failed attempts triggers a 300s (5-minute) lock (`HTTP 429`).
  - Duress simulation: flags incident as `ESCALATION_REQUIRED` while returning plausible UI deactivation to protect victims under coercion.

---

## 3. Data Flow & Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    actor Victim as Phone A (Victim)
    participant FGS as SosLocationService
    participant API as FastAPI Backend
    participant DB as PostGIS 3.x
    participant FCM as Firebase Cloud Messaging
    actor Resp as Phone B (Responder)

    Victim->>API: POST /api/incidents (Test SOS Trigger)
    API->>DB: INSERT INTO incidents (status='ACTIVE', radius=1000m)
    API-->>Victim: 201 Created (Incident ID, Secret)
    Victim->>FGS: Start Foreground Service
    FGS->>API: POST /api/incidents/{id}/locations (Point A: Fix #1)
    API->>DB: UPDATE incidents SET latest_location = Fix #1
    API->>DB: ST_DWithin(responder_locations.location, Fix #1, 1000m)
    DB-->>API: Match Phone B (Eligible, 400m away)
    API->>DB: INSERT INTO incident_invitations & notification_outbox
    API->>FCM: Dispatch Alert (Data-only, zero coordinates)
    FCM->>Resp: High-Priority FCM Push Notification
    Resp->>Resp: Urgent Channel Alert (5.2s Vibration)
    Resp->>API: POST /api/invitations/{id}/accept
    API->>DB: UPDATE incident_invitations SET status='ACCEPTED'
    API->>DB: INSERT INTO incident_responders
    Resp->>API: POST /api/incidents/{id}/ws-ticket
    API-->>Resp: Single-use Ticket
    Resp->>API: WSS /ws/incidents/{id}?ticket=XYZ
    API-->>Resp: INCIDENT_SNAPSHOT (Victim Coordinates)
    
    Note over Victim,API: Victim moves to Point B (1900m away)
    FGS->>API: POST /api/incidents/{id}/locations (Point B: Fix #2)
    API->>DB: UPDATE incidents SET latest_location = Fix #2
    API->>Resp: WebSocket: LOCATION_UPDATED (Fix #2 streamed to Phone B)
    API->>DB: ST_DWithin(responder_locations.location, Fix #2, 1000m)
    Note over DB,API: Phone C discovered; Phone B remains connected!

    Note over Victim,API: Victim concludes SOS
    Victim->>API: POST /api/incidents/{id}/resolve (Argon2id PIN)
    API->>DB: Verify PIN hash, attempts < 5
    API->>DB: UPDATE incidents SET status='RESOLVED'
    API->>Resp: WebSocket: ACCESS_REVOKED & INCIDENT_CLOSED
    API-->>Resp: Close WebSocket Connection
    Victim->>FGS: Stop Foreground Service & Notification
