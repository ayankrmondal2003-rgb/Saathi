# Three-phone moving geofence rehearsal

For Render, complete [cloud acceptance steps 1–5](CLOUD_TEST_GUIDE.md) before starting the movement sequence. Use three verified inboxes and private UID values. The positions and actors below are rehearsal inputs.

Before activation, confirm each phone has a different verified allowlisted UID, a synchronized profile, a registered device, and permission for notifications. Run a self-test on each phone. Record acceptance, handset receipt, display and opening separately. Use synthetic locations. Stop the rehearsal if any device can read coordinates before acceptance or after closure.

## 1. Demonstration Topology & Participant Roles

This demonstration models a dynamic emergency incident with a moving victim and two community responders entering and leaving proximity.

```
                    Point A (0m)                Point B (+1,800m)
                     [Victim]                      [Victim moves here]
                        |                                  |
            < 1 km      |      > 1 km          < 1 km      |      < 1 km
          +-------------+-------------+      +-------------+-------------+
          |                           |      |                           |
      [Phone B]                   [Phone C]  [Phone B]               [Phone C]
    (Responder 1)               (Responder 2) (Grant Kept)          (Newly Invited)
     (400m away)                (2,200m away) (Now 1,400m away)       (400m away)
    INVITED & ACCEPTED           NOT INVITED   TRACKING CONTINUES    INVITED & ACCEPTS
```

### Participant Devices

| Device | Role | Initial Position | Position at Step 6 | Identity |
| :--- | :--- | :--- | :--- | :--- |
| **Phone A** | Victim (SOS Initiator) | Point A (0 m) | Point B (+1,800 m North) | `demo_victim@surakshanet.org` |
| **Phone B** | Responder 1 (Initial Nearby) | 400 m North | Stationary (400 m North) | `demo_resp1@surakshanet.org` |
| **Phone C** | Responder 2 (Newly In-Range) | 2,200 m North | Stationary (2,200 m North) | `demo_resp2@surakshanet.org` |

---

## 2. Step-by-Step Execution Protocol

### Step 1: Baseline Registration & Responder Opt-In
1. **Phone B & Phone C**: Launch app, sign in with authorized test accounts.
2. Toggle "Opt-in as Emergency Responder" to ON.
3. Toggle "Available for Alerts" to ON.
4. Verify both phones show **Safety Readiness Score >= 85%**.

### Step 2: Victim Initiates Emergency SOS at Point A (0 m)
1. **Phone A**: Hold primary pink SOS button for 2.0 seconds until tactile haptic pulse triggers.
2. Audio siren sounds, background location service begins streaming.
3. Backend ingests Point A coordinates (`28.6139, 77.2090`).
4. PostGIS `ST_DWithin` calculates responders within 1,000 metres.

### Step 3: Spatial Filtering & Invitation Delivery
- **Phone B** (400 m away): Distance < 1,000 m. PostGIS generates invitation; FCM outbox dispatches high-priority push notification.
- **Phone C** (2,200 m away): Distance > 1,000 m. PostGIS skips Phone C. No push notification sent.

### Step 4: Responder 1 Accepts & Establishes WebSocket Tracking
1. **Phone B**: Receives urgent notification with vibration. Taps notification to launch `Incoming` screen.
2. Taps "Accept & Assist".
3. Backend issues single-use WebSocket ticket (`POST /api/incidents/{id}/ws-ticket`).
4. Phone B connects to `ws/incidents/{id}?ticket={ticket}` and receives PostgreSQL initial snapshot.
5. Live radar canvas on Phone B renders victim’s distance and bearing.

### Step 5: Victim Moves to Point B (+1,800 m North)
1. **Phone A**: Simulates or walks 1,800 metres north along meridian.
2. Telemetry update sent to backend (`POST /api/incidents/{id}/location`).
3. Distributed Redis bus forwards `LOCATION_UPDATED` event to Phone B’s active WebSocket connection.

### Step 6: Dynamic Geofence Re-Evaluation
- **Phone B** is now 1,400 m away (> 1 km from victim). **Security Rule**: Moving > 1 km does **NOT** revoke a previously accepted valid grant. Phone B continues receiving live telemetry.
- **Phone C** is now 400 m away (< 1 km from victim). PostGIS detects Phone C entering 1 km radius.
- Backend enqueues invitation for Phone C without duplicating Phone B.
- **Phone C** receives FCM push notification and accepts. Both Phone B and Phone C are now actively tracking Phone A.

### Step 7: Incident Resolution & Global Revocation
1. **Phone A**: Taps "Resolve Incident" on the Active Incident screen.
2. Enters enrolled 6-digit Safety PIN (`839162`).
3. Backend validates PBKDF2 hash, marks incident `CLOSED`, and sets `revoked_at = clock_timestamp()` across all responder grants.
4. Distributed event bus broadcasts `INCIDENT_CLOSED`.
5. WebSockets on Phone B and Phone C receive `INCIDENT_CLOSED` and close immediately with code 4403.
6. Subsequent attempts to fetch location return HTTP 404. All tracking ceases.

---

## 3. Test Status Across Execution Environments

- **Automated Multi-Worker Backend**: **VERIFIED** via [test_multi_worker_and_geofence.py](file:///C:/Users/ayank/AndroidStudioProjects/SurakshaNet/backend/tests/test_multi_worker_and_geofence.py) (Deterministic A → B → C → D movement test).
- **Physical Multi-Device Demonstration**: **IMPLEMENTED BUT UNTESTED ON THREE SIMULTANEOUS PHYSICAL PHONES** (Requires 3 physical devices provisioned with Google Play Services and separate SIM cards; currently tested on 1 physical Samsung SM-E625F + 2 Android emulators).
