# SurakshaNet — Security Audit & Threat Model

## Phase 18 cloud boundary

Every protected request checks Firebase token revocation, verified email and private test UID membership. Spatial discovery also filters eligible owner and responder UIDs. Invitations carry identifiers without precise coordinates. A single-use socket ticket is followed by a database participation check. Remote location delivery checks PostgreSQL authorization. Redis loss can interrupt updates and delay disconnect signaling.

The native app requires HTTPS; Render terminates TLS at ingress. CORS defaults to an empty origin list. CORS does not authenticate native apps. The deployment disables interactive API documentation and Uvicorn access logging. Review provider edge-log retention and redact ticket URLs before sharing diagnostics. PIN attempts have a cooldown; a general-purpose API rate limiter and application-level trusted-host allowlist have not been demonstrated. Use an isolated demo workspace, private data services and exactly three allowlisted users.

Trusted-contact provider status in this test backend is simulated. The real notification path is FCM to registered, authorized test devices. Confirm native SMS fallback only by opening and dismissing the composer. The workspace has no Git history to audit. If a secret is found in another clone, revoke or rotate it immediately, review access, then coordinate history cleanup with repository owners. Ignore patterns protect future staging only.


This document outlines the security controls, cryptographic primitives, authorization boundaries, and threat mitigation strategies implemented in SurakshaNet.

---

## 1. Authentication & Identity Controls

### 1.1 Firebase ID Token Verification
- All authenticated API requests require a valid JSON Web Token (JWT) issued by Firebase Authentication.
- The backend verifies:
  1. Cryptographic RS256 signature against Google's public JWKS.
  2. Token expiration (`exp`) and issuance time (`iat`).
  3. Intended audience matching `FIREBASE_PROJECT_ID`.
  4. Explicit revocation check (`check_revoked=True`) against Firebase servers to immediately invalidate tokens upon password change or account compromise.
  5. Email verification status (`email_verified=True`).

### 1.2 Test Account Isolation (`TEST_ACCOUNT_UIDS`)
- In development and hackathon configurations, the backend enforces an explicit UID allowlist:
  ```python
  if user.firebase_uid not in settings.test_account_uids:
      raise HTTPException(403, "Access restricted to authorized demonstration accounts.")
  ```
- Public registration cannot trigger emergency dispatches or discover test incidents.

---

## 2. Location Privacy & Authorization Boundaries

### 2.1 No Coordinates in Push Notifications (FCM)
- FCM notifications sent to responders **never** contain latitude, longitude, or victim identities.
- Payloads contain only an opaque `invitation_id`, `incident_id`, and `expires_at` timestamp.
- Android client security check (`SosFirebaseMessagingService.kt`):
  ```kotlin
  if (data.containsKey("latitude") || data.containsKey("longitude") ||
      data.containsKey("lat") || data.containsKey("lon")) {
      // Drop packet containing prohibited coordinate leaks
      return
  }
  ```

### 2.2 Strict Access Control for Live Tracking
- Victim coordinates are accessible **only** after an invited responder explicitly submits an acceptance request (`POST /api/invitations/{id}/accept`).
- Responders cannot query victim locations via arbitrary REST endpoints; access is gated through an authorized incident session.

### 2.3 Single-Use WebSocket Ticket Authentication
- Responders and victims never send raw Firebase ID tokens or API keys as query parameters when establishing WebSocket connections.
- Tickets are generated via `POST /api/incidents/{id}/ws-ticket`:
  - Generated using `secrets.token_urlsafe(32)`.
  - Stored in memory with a strict 60-second time-to-live (TTL).
  - Atomically consumed during the WebSocket handshake and deleted immediately (`tickets.pop()`).
  - Tied strictly to the requesting `user_id`, `incident_id`, and `role`.

### 2.4 Immediate Client Memory Coordinate Purge
- When an incident is resolved, cancelled, or a responder leaves:
  1. The backend broadcasts `ACCESS_REVOKED` and `INCIDENT_CLOSED` events over the WebSocket.
  2. The backend forcefully closes the WebSocket connection.
  3. The Android client (`IncidentTrackingRepository.kt`) immediately purges all victim coordinates, telemetry history, and active snapshots from device memory.

---

## 3. Cryptographic Controls & Safety PIN Lifecycle

### 3.1 Argon2id Password Hashing
- Safety PINs and Duress PINs are hashed using the memory-hard **Argon2id** algorithm:
  - `time_cost = 2` iterations.
  - `memory_cost = 65536` KB (64 MB).
  - `parallelism = 1` thread.
  - `hash_len = 32` bytes.
- Plaintext PINs are never stored in the database or logged in application traces.

### 3.2 PIN Strength & Collision Policies
- Exactly 6 numeric digits.
- Trivial repetitive patterns (`111111`, `222222`) are strictly rejected.
- Ascending and descending runs (`123456`, `654321`) are rejected.
- Primary PIN and Duress PIN cannot be identical.

### 3.3 Rate Limiting & Cooldown Lockout
- Failed PIN attempts are tracked in `safety_credentials.failed_attempt_count`.
- Upon 5 consecutive failed attempts, the backend enforces a mandatory 300-second (5-minute) cooldown (`HTTP 429 Too Many Requests`).
- Every attempt is logged in `incident_resolution_attempts` with timestamp, attempt index, and outcome.

### 3.4 Duress Safeguards
- If a victim is coerced into stopping the SOS, entering the Duress PIN triggers a silent safeguard:
  - The UI displays an acknowledged confirmation state.
  - The backend marks the incident as `ESCALATION_REQUIRED`.
  - Live tracking, foreground services, and responder connections continue uninterrupted.

---

## 4. Mobile Client Hardening

### 4.1 Zero Backend Secrets in APK
- The Android client holds no database credentials, Firebase service account keys, or master API tokens.
- All backend communication is routed through authenticated endpoints using temporary user session tokens.

### 4.2 Foreground Service Lifecycle & Auto-Shutdown
- `SosLocationService` monitors `FirebaseAuth.AuthStateListener`. If the active user signs out or their session is revoked, the service stops immediately and clears temporary coordinate caches.
- Broadcast receiver monitors `PROVIDERS_CHANGED_ACTION` to flag location tampering or disabled GPS sensors.

### 4.3 Network Security Config
- Cleartext HTTP traffic is blocked in release configurations.
- Only encrypted HTTPS and WSS protocols are permitted.
