# SurakshaNet — Privacy Architecture & Data Retention Policy

SurakshaNet is built on the principle of **privacy by design**. Location data is considered sensitive personal information and is processed with strict minimization and retention boundaries.

---

## 1. Core Privacy Principles

### 1.1 Data Minimization
- **No Idle Tracking**: The Android application does not track, record, or transmit user coordinates in the background unless the user has actively triggered an emergency SOS or opted in as an available responder.
- **Zero Responder Coordinate Broadcast**: Responders do not broadcast their precise GPS to victims or other responders. Only the victim's location is shared with authorized, accepted responders. Responders are identified to the victim solely by masked IDs and assistance status (e.g., `ON_THE_WAY`, `HELPING`).
- **Zero GPS in Push Notifications**: Push alerts dispatched via Firebase Cloud Messaging contain no coordinates, street names, or victim profile information.

### 1.2 Local Device Storage for Sensitive Assets
- **Trusted Contacts**: Contact names and emergency telephone numbers are stored exclusively in on-device encrypted storage (`SharedPreferences` / Room) and are never uploaded to the centralized backend server.
- **Device Identifiers**: Push notification tokens are periodically refreshed and disassociated upon account sign-out.

---

## 2. Ephemeral Location Processing

### 2.1 Moving Geofence Processing
- During an active incident, GPS fixes received from the victim are used immediately to recalculate the dynamic 1 km geofence.
- Only the latest fix (`latest_location`) is maintained as the active incident center. Historical telemetry points are marked ephemeral.

### 2.2 Responder Location Processing
- When a responder enables "Available" duty mode, their location is posted periodically (`POST /api/responders/location`).
- The database maintains only a single row per responder (`responder_locations`), overwriting previous coordinates upon each update. No historical breadcrumb trail of responders is retained.

---

## 3. Data Retention & Automatic Purging

### 3.1 Incident Closure & Access Termination
- Upon verified resolution of an incident:
  - All responder access authorizations (`incident_responders`) are revoked.
  - Active WebSocket connections are immediately terminated.
  - The Android client purges all incident coordinates and snapshots from memory.

### 3.2 Automated Retention Cleanup (`app/maintenance.py`)
The backend provides scheduled database maintenance tasks to enforce strict retention windows:

| Data Type | Retention Window | Purge Mechanism |
|---|---|---|
| Inactive Incidents (`RESOLVED`, `CANCELLED`) | 7 days | Automated cleanup script (`purge_expired_incidents`) |
| Expired Invitations | 24 hours | Automated deletion |
| Notification Outbox Entries (`SENT`, `FAILED`) | 48 hours | Partition truncate / row deletion |
| Inactive Responder Locations | 30 minutes | Overwritten or purged if no fresh fix reported |
| Ephemeral Resolution Attempts | 30 days | Audit log anonymization |

---

## 4. User Consent & Control

1. **Explicit Responder Opt-In**: Users must explicitly opt into the responder network via the app settings. Responder mode can be paused or disabled at any time with a single toggle.
2. **Account Deletion**: Users may request complete deletion of their account profile, which purges all credentials, device registrations, and associated incident history.
