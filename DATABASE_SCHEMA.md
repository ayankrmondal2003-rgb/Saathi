# SurakshaNet — Database Schema & PostGIS Reference

The SurakshaNet persistence layer is implemented in PostgreSQL 15+ using the PostGIS 3.x spatial extension and SQLAlchemy 2.0 / GeoAlchemy2. All coordinates are stored as WGS84 spherical geographies (`geography(POINT, 4326)`) to enable native, distortion-free metric distance calculations (`ST_DWithin` in metres).

---

## 1. Entity-Relationship Diagram

```
+--------------------+           +------------------------+
|       users        |           |  responder_locations   |
+--------------------+           +------------------------+
| id (PK)            |<----+     | user_id (PK, FK)       |
| firebase_uid (UQ)  |     |     | location (Point, 4326) |<-- GiST Index
| display_name       |     |     | accuracy_meters        |
| email              |     |     | recorded_at            |
| responder_opt_in   |     |     | searched_center        |
| responder_available|     |     | searched_at            |
+--------------------+     |     +------------------------+
         |                 |
         | 1:N             | 1:N
         v                 |
+--------------------+     |     +------------------------+
|     incidents      |     |     |   safety_credentials   |
+--------------------+     |     +------------------------+
| id (PK)            |     +---->| user_id (PK, FK)       |
| victim_user_id (FK)|     |     | credential_type (PK)   |
| status (ACTIVE,..) |     |     | pin_hash (Argon2id)    |
| latest_location    |<-- GiST   | failed_attempt_count   |
| evaluated_center   |     |     | locked_until           |
| radius_meters      |     |     +------------------------+
+--------------------+     |
         |                 |
         +-------+---------+
                 |
                 v
+-------------------------------+       +----------------------------+
|     incident_invitations      |       |    incident_responders     |
+-------------------------------+       +----------------------------+
| id (PK)                       |       | incident_id (PK, FK)       |
| incident_id (FK)              |       | responder_user_id (PK, FK) |
| recipient_user_id (FK)        |       | status (ON_THE_WAY, ...)   |
| status (PENDING, ACCEPTED,..) |       | joined_at                  |
| expires_at                    |       | left_at                    |
+-------------------------------+       +----------------------------+
                 |
                 v
+-------------------------------+
|      notification_outbox      |
+-------------------------------+
| id (PK)                       |
| invitation_id (FK)            |
| recipient_user_id (FK)        |
| status (PENDING, SENT,..)     |
| attempt_count                 |
+-------------------------------+
```

---

## 2. Table Specifications

### 2.1 `users`
Stores account identities mapped to Firebase Authentication UIDs.
- `id` (UUID, Primary Key)
- `firebase_uid` (VARCHAR(128), Unique, Indexed)
- `display_name` (VARCHAR(100))
- `email` (VARCHAR(255))
- `email_verified` (BOOLEAN, default FALSE)
- `account_status` (VARCHAR(32), default 'ACTIVE')
- `responder_opt_in` (BOOLEAN, default FALSE)
- `responder_available` (BOOLEAN, default FALSE)
- `created_at`, `updated_at` (TIMESTAMPTZ)

### 2.2 `responder_locations`
Maintains the latest reported telemetry for available responders to support incoming geofence queries.
- `user_id` (UUID, Primary Key, Foreign Key -> `users.id`)
- `location` (`geography(POINT, 4326)`, Not Null) — **Spatial Index**: `ix_responder_location_gist`
- `accuracy_meters` (DOUBLE PRECISION, Not Null)
- `recorded_at` (TIMESTAMPTZ, Not Null)
- `is_mock` (BOOLEAN, Not Null)
- `searched_center` (`geography(POINT, 4326)`) — Last query center point
- `searched_at` (TIMESTAMPTZ) — Last query timestamp

### 2.3 `incidents`
Tracks active and historical emergency SOS incidents.
- `id` (UUID, Primary Key)
- `victim_user_id` (UUID, Foreign Key -> `users.id`)
- `status` (VARCHAR(32), Not Null) — Enum: `ACTIVE`, `RESOLVED`, `CANCELLED`, `EXPIRED`, `ESCALATION_REQUIRED`
- `latest_location` (`geography(POINT, 4326)`, Not Null) — **Filtered Spatial Index**: `ix_open_center` (on `status = 'ACTIVE'`)
- `location_accuracy_meters` (DOUBLE PRECISION)
- `location_recorded_at` (TIMESTAMPTZ)
- `location_is_mock` (BOOLEAN)
- `evaluated_center` (`geography(POINT, 4326)`) — Center of last 1 km geofence calculation
- `evaluated_at` (TIMESTAMPTZ) — Timestamp of last evaluation
- `radius_meters` (INTEGER, default 1000)
- `is_test` (BOOLEAN, default TRUE)
- `created_at`, `closed_at` (TIMESTAMPTZ)

### 2.4 `incident_invitations`
Records dispatch invitations issued to nearby eligible responders.
- `id` (UUID, Primary Key)
- `incident_id` (UUID, Foreign Key -> `incidents.id`)
- `recipient_user_id` (UUID, Foreign Key -> `users.id`)
- `status` (VARCHAR(32), default 'PENDING') — Enum: `PENDING`, `ACCEPTED`, `DECLINED`, `EXPIRED`, `INVALIDATED`
- `created_at`, `updated_at`, `expires_at` (TIMESTAMPTZ)
- **Constraint**: `UNIQUE (incident_id, recipient_user_id)` prevents duplicate invitations for the same incident.

### 2.5 `incident_responders`
Maintains the set of active responders who accepted an invitation.
- `incident_id` (UUID, Foreign Key -> `incidents.id`)
- `responder_user_id` (UUID, Foreign Key -> `users.id`)
- `status` (VARCHAR(32), default 'ON_THE_WAY') — Enum: `ON_THE_WAY`, `HELPING`, `UNABLE_TO_HELP`, `LEFT_INCIDENT`
- `joined_at`, `left_at` (TIMESTAMPTZ)
- **Primary Key**: `(incident_id, responder_user_id)`

### 2.6 `notification_outbox`
Transactional queue for delivering asynchronous push notifications via FCM.
- `id` (BIGINT, Primary Key, Auto-increment)
- `invitation_id` (UUID, Foreign Key -> `incident_invitations.id`)
- `recipient_user_id` (UUID, Foreign Key -> `users.id`)
- `status` (VARCHAR(32), default 'PENDING') — Enum: `PENDING`, `DISPATCHED`, `FAILED`, `CANCELLED`
- `attempt_count` (INTEGER, default 0)
- `last_attempt_at`, `created_at` (TIMESTAMPTZ)

### 2.7 `safety_credentials`
Stores memory-hard Argon2id hashes of Safety PINs and Duress PINs.
- `user_id` (UUID, Foreign Key -> `users.id`)
- `credential_type` (VARCHAR(32)) — Enum: `PRIMARY_PIN`, `DURESS_PIN`
- `pin_hash` (VARCHAR(255), Not Null) — Argon2id hash with embedded salt
- `failed_attempt_count` (INTEGER, default 0)
- `locked_until` (TIMESTAMPTZ, Nullable)
- `last_changed_at`, `created_at`, `updated_at` (TIMESTAMPTZ)
- **Primary Key**: `(user_id, credential_type)`

### 2.8 `incident_resolution_attempts`
Audit trail of all PIN verification and deactivation attempts.
- `id` (BIGINT, Primary Key, Auto-increment)
- `incident_id` (UUID, Foreign Key -> `incidents.id`)
- `user_id` (UUID, Foreign Key -> `users.id`)
- `attempt_number` (INTEGER, Not Null)
- `outcome` (VARCHAR(32), Not Null) — Enum: `SUCCESS`, `INVALID_PIN`, `RATE_LIMITED`, `DURESS_TRIGGERED`
- `attempted_at` (TIMESTAMPTZ, default clock_timestamp())

### 2.9 `trusted_contacts` (Phase 13)
Maintains private emergency contacts configured per user account with granular notification preferences.
- `id` (UUID, Primary Key)
- `owner_user_id` (UUID, Foreign Key -> `users.id` ON DELETE CASCADE)
- `display_name` (VARCHAR(256), Not Null)
- `phone_number` (VARCHAR(32), Not Null)
- `normalized_phone_number` (VARCHAR(32), Not Null)
- `label` (VARCHAR(64), default 'Emergency Contact')
- `is_primary` (BOOLEAN, default FALSE)
- `is_verified` (BOOLEAN, default FALSE)
- `source` (VARCHAR(32), default 'MANUAL') — Enum: `MANUAL`, `PHONE_CONTACTS`
- `notify_on_sos` (BOOLEAN, default TRUE)
- `include_location_link` (BOOLEAN, default TRUE)
- `notify_on_closure` (BOOLEAN, default TRUE)
- `allow_live_tracking` (BOOLEAN, default TRUE)
- `preferred_channel` (VARCHAR(32), default 'SMS') — Enum: `SMS`, `APP`
- `created_at`, `updated_at` (TIMESTAMPTZ)
- **Constraint**: `UNIQUE (owner_user_id, normalized_phone_number)`

### 2.10 `trusted_contact_grants` (Phase 13)
Isolated, time-bounded live tracking authorizations granted to trusted contacts during active emergency incidents.
- `id` (UUID, Primary Key)
- `incident_id` (UUID, Foreign Key -> `incidents.id` ON DELETE CASCADE)
- `contact_id` (UUID, Foreign Key -> `trusted_contacts.id` ON DELETE CASCADE)
- `recipient_user_id` (UUID, Nullable, Foreign Key -> `users.id` ON DELETE SET NULL)
- `authorization_status` (VARCHAR(32), default 'AUTHORIZED') — Enum: `AUTHORIZED`, `REVOKED`
- `access_expires_at` (TIMESTAMPTZ, Not Null — default 2 hours)
- `granted_at` (TIMESTAMPTZ, default now())
- `revoked_at` (TIMESTAMPTZ, Nullable — set when incident resolved via Safety PIN)
- **Constraint**: `UNIQUE (incident_id, contact_id)`

---

## 3. Spatial Indexes & Performance Optimization

```sql
-- 1. GiST Index for Responder Locations
CREATE INDEX ix_responder_location_gist 
ON public.responder_locations 
USING gist (location);

-- 2. Partial GiST Index for Active Incidents
CREATE INDEX ix_open_center 
ON public.incidents 
USING gist (latest_location) 
WHERE (status = 'ACTIVE');
```

The PostGIS `ST_DWithin` function leverages these Generalized Search Tree (GiST) indexes to calculate bounding-box intersections prior to spherical geodesic distance evaluation, ensuring sub-10ms response times even under heavy concurrent loads.

---

## 4. Alembic Migration History

| Revision | Name | Description |
|---|---|---|
| `0001` | `0001_test_incidents` | Core tables: `users`, `devices`, `responder_locations`, `incidents`, `incident_invitations`, `incident_responders`, `incident_events` |
| `0002` | `0002_moving_geofence` | Checkpoints (`searched_at`, `evaluated_at`), partial GiST index `ix_open_center`, and `notification_outbox` |
| `0003` | `0003_fcm_notifications` | FCM device tokens and delivery status transitions |
| `0004` | `0004_realtime_tracking` | Responder coordination assistance states (`assistance_status`, `assistance_updated_at`) |
| `0005` | `0005_safety_pin_resolution` | `safety_credentials`, `incident_resolution_requests`, `incident_resolution_attempts` |
| `0006` | `0006_notification_receipts` | Per-token receipt verification array `accepted_token_hashes` |
| `0007` | `0007_trusted_contact_alerts` | `trusted_contacts`, `trusted_contact_grants`, outbox `contact_id` and delivery status constraints |

