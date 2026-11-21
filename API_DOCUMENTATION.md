# SurakshaNet — Backend API Documentation

All API routes require HTTPS and are prefixed with `/api`. Authenticated endpoints require an `Authorization: Bearer <firebase_id_token>` header unless explicitly specified.

---

## 1. Authentication & System Health

### `GET /health`
- **Auth**: None
- **Description**: Lightweight health probe verifying service responsiveness.
- **Response**: `200 OK`
  ```json
  {
    "status": "ok",
    "service": "SurakshaNet API"
  }
  ```

### `GET /api/users/me`
- **Auth**: Bearer token
- **Description**: Returns the caller's profile and responder availability.
- **Response**: `200 OK`
  ```json
  {
    "id": "7b08ecaa-9b7e-40ec-8ee8-bca77259f972",
    "firebase_uid": "user_phone_a_uid",
    "display_name": "Priya Sharma",
    "email": "priya@example.com",
    "email_verified": true,
    "responder_opt_in": true,
    "responder_available": true
  }
  ```

### `PATCH /api/users/me`
- **Auth**: Bearer token
- **Description**: Updates user profile display name or toggles responder settings.
- **Request Body**:
  ```json
  {
    "display_name": "Priya S.",
    "responder_opt_in": true,
    "responder_available": false
  }
  ```
- **Response**: `200 OK`

---

## 2. Emergency Incidents & Location Telemetry

### `POST /api/incidents`
- **Auth**: Bearer token
- **Description**: Initiates a new emergency SOS incident.
- **Request Body**:
  ```json
  {
    "is_test": true,
    "initial_fix": {
      "latitude": 28.6139,
      "longitude": 77.2090,
      "accuracy_meters": 12.5,
      "recorded_at": "2026-11-21T02:00:00Z",
      "is_mock": false
    }
  }
  ```
- **Response**: `201 Created`
  ```json
  {
    "id": "c138f18d-6c17-4889-a5c2-f19bca0214ee",
    "status": "ACTIVE",
    "radius_meters": 1000,
    "created_at": "2026-11-21T02:00:00.123Z"
  }
  ```

### `POST /api/incidents/{incident_id}/locations`
- **Auth**: Bearer token (Victim only)
- **Description**: Posts continuous GPS fixes from the victim's foreground service. Automatically reevaluates the dynamic geofence if displacement > 30m or interval > 15s.
- **Request Body**:
  ```json
  {
    "latitude": 28.6145,
    "longitude": 77.2095,
    "accuracy_meters": 8.0,
    "recorded_at": "2026-11-21T02:00:05Z",
    "is_mock": false
  }
  ```
- **Response**: `200 OK`

### `POST /api/responders/location`
- **Auth**: Bearer token (Opted-in responder)
- **Description**: Updates the responder's current location to assess eligibility for nearby active incidents.
- **Request Body**:
  ```json
  {
    "latitude": 28.6150,
    "longitude": 77.2100,
    "accuracy_meters": 15.0,
    "recorded_at": "2026-11-21T02:00:02Z",
    "is_mock": false
  }
  ```
- **Response**: `200 OK`

---

## 3. Responder Invitations & Coordination

### `GET /api/invitations`
- **Auth**: Bearer token
- **Description**: Returns all invitations addressed to the calling responder.
- **Response**: `200 OK`
  ```json
  [
    {
      "id": "d9817f7b-9993-41bb-98f9-aa11b817e900",
      "incident_id": "c138f18d-6c17-4889-a5c2-f19bca0214ee",
      "status": "PENDING",
      "expires_at": "2026-11-21T02:05:00Z"
    }
  ]
  ```

### `POST /api/invitations/{invitation_id}/accept`
- **Auth**: Bearer token (Invited responder)
- **Description**: Accepts an emergency invitation, granting authorized access to live incident updates.
- **Response**: `200 OK`
  ```json
  {
    "status": "ACCEPTED",
    "incident_id": "c138f18d-6c17-4889-a5c2-f19bca0214ee"
  }
  ```

### `POST /api/invitations/{invitation_id}/decline`
- **Auth**: Bearer token
- **Description**: Declines an invitation, marking it `DECLINED` and preventing further alerts.
- **Response**: `200 OK`

### `PATCH /api/incidents/{incident_id}/responders/me/status`
- **Auth**: Bearer token (Accepted responder)
- **Description**: Updates responder operational status during the incident.
- **Request Body**:
  ```json
  {
    "status": "ON_THE_WAY" // Allowed: ON_THE_WAY, HELPING, UNABLE_TO_HELP, LEFT_INCIDENT
  }
  ```
- **Response**: `200 OK`

---

## 4. Real-Time Tracking & WebSocket API

### `POST /api/incidents/{incident_id}/ws-ticket`
- **Auth**: Bearer token (Victim or Accepted Responder)
- **Description**: Issues a short-lived (60s), single-use ticket to establish a WebSocket connection without exposing auth tokens in the query string.
- **Response**: `200 OK`
  ```json
  {
    "ticket": "sec_wst_abc12345xyz67890",
    "expires_at": "2026-11-21T02:01:00Z"
  }
  ```

### `WSS /ws/incidents/{incident_id}?ticket={ticket}`
- **Auth**: Single-use ticket via query parameter
- **Description**: Secure bi-directional WebSocket connection streaming live incident updates.
- **Events Broadcasted**:
  1. `INCIDENT_SNAPSHOT`: Initial full state upon connection.
  2. `LOCATION_UPDATED`: Real-time GPS fix of the victim.
  3. `RESPONDER_JOINED`: Notification that a new responder accepted and entered.
  4. `RESPONDER_STATUS_CHANGED`: Status changes (`ON_THE_WAY`, `HELPING`, etc.).
  5. `INCIDENT_STATUS_CHANGED`: State transitions (e.g. `ESCALATION_REQUIRED`).
  6. `ACCESS_REVOKED`: Dispatched immediately to responders upon incident closure or eviction.
  7. `INCIDENT_CLOSED`: Broadcast when incident resolves.

---

## 5. Safety PIN & Incident Resolution

### `POST /api/safety-pin/setup`
- **Auth**: Bearer token
- **Description**: Enrolls an initial 6-digit Safety PIN or Duress PIN.
- **Request Body**:
  ```json
  {
    "pin": "849201",
    "confirm_pin": "849201",
    "credential_type": "PRIMARY_PIN" // or "DURESS_PIN"
  }
  ```
- **Validation**: Enforces non-sequential, non-repeated 6-digit format. Rejects primary and duress PIN collisions.

### `POST /api/incidents/{incident_id}/resolve`
- **Auth**: Bearer token (Victim only)
- **Description**: Concludes an active emergency. Validates PIN against Argon2id hash.
- **Request Body**:
  ```json
  {
    "pin": "849201",
    "reason": "USER_RESOLVED"
  }
  ```
- **Responses**:
  - `200 OK` (Standard PIN):
    ```json
    {
      "resolution_status": "CONFIRMED",
      "incident_status": "RESOLVED",
      "message": "Incident resolved successfully."
    }
    ```
  - `200 OK` (Duress PIN):
    ```json
    {
      "resolution_status": "DURESS_SIMULATION",
      "incident_status": "ESCALATION_REQUIRED",
      "message": "Incident resolved."
    }
    ```
  - `403 Forbidden`: Incorrect PIN. Increments attempt counter.
  - `429 Too Many Requests`: 5 failed attempts reached; returns `retry_after_seconds` (300s cooldown).

---

## 6. Trusted Emergency Contacts & Escalation API (Phase 13)

### `GET /api/trusted-contacts`
- **Auth**: Bearer token
- **Description**: Returns all trusted emergency contacts configured by the caller, sorted with the primary contact first.
- **Response**: `200 OK`
  ```json
  [
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "owner_user_id": "7b08ecaa-9b7e-40ec-8ee8-bca77259f972",
      "display_name": "Mom",
      "phone_number": "+91 98765 43210",
      "normalized_phone_number": "+919876543210",
      "label": "Parent",
      "is_primary": true,
      "is_verified": false,
      "source": "MANUAL",
      "notify_on_sos": true,
      "include_location_link": true,
      "notify_on_closure": true,
      "allow_live_tracking": true,
      "preferred_channel": "SMS",
      "created_at": "2026-11-21T09:00:00Z",
      "updated_at": "2026-11-21T09:00:00Z"
    }
  ]
  ```

### `POST /api/trusted-contacts`
- **Auth**: Bearer token
- **Description**: Creates a new trusted emergency contact. Rejects duplicate phone numbers for the same account (`409 Conflict`). Enforces primary contact invariant.
- **Request Body**:
  ```json
  {
    "display_name": "Sister",
    "phone_number": "+91 91234 56789",
    "label": "Sibling",
    "is_primary": false,
    "notify_on_sos": true,
    "include_location_link": true,
    "notify_on_closure": true,
    "allow_live_tracking": true,
    "preferred_channel": "SMS"
  }
  ```
- **Response**: `201 Created`

### `PUT /api/trusted-contacts/{contact_id}`
- **Auth**: Bearer token (Contact owner)
- **Description**: Updates contact display name, phone number, and notification preference flags.
- **Response**: `200 OK`

### `DELETE /api/trusted-contacts/{contact_id}`
- **Auth**: Bearer token (Contact owner)
- **Description**: Deletes a trusted contact. If the deleted contact was primary, automatically promotes the next available contact to primary.
- **Response**: `204 No Content`

### `POST /api/trusted-contacts/{contact_id}/primary`
- **Auth**: Bearer token (Contact owner)
- **Description**: Promotes the specified contact to primary, demoting any existing primary contact.
- **Response**: `200 OK`

### `POST /api/incidents/{incident_id}/trusted-contacts/alert`
- **Auth**: Bearer token (Incident owner)
- **Description**: Dispatches emergency alert outbox jobs for trusted contacts with `notify_on_sos = true` and provisions time-bounded live tracking grants (`trusted_contact_grants`).
- **Response**: `200 OK`
  ```json
  {
    "incident_id": "c138f18d-6c17-4889-a5c2-f19bca0214ee",
    "alerts_queued": 1,
    "primary_contact_name": "Mom",
    "statuses": [
      {
        "contact_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        "display_name": "Mom",
        "masked_phone": "+91*****3210",
        "is_primary": true,
        "delivery_status": "PROVIDER_ACCEPTED",
        "message_preview": "SurakshaNet SOS: I may need urgent help. My last available location is: https://maps.google.com/?q=28.613900,77.209000. Location recorded at: 2026-11-21 09:00:00 UTC. If you believe I am in immediate danger, contact emergency services (112).",
        "location_disclosed": true,
        "tracking_granted": true,
        "updated_at": "2026-11-21T09:00:01Z"
      }
    ]
  }
  ```

### `GET /api/incidents/{incident_id}/trusted-contacts`
- **Auth**: Bearer token (Incident owner)
- **Description**: Returns real-time delivery statuses for all configured contacts during an incident.
- **Response**: `200 OK`

