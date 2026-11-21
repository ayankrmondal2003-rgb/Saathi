# SurakshaNet hackathon demonstration

## Four-minute script

- 0:00–0:30. “SurakshaNet helps a moving person invite nearby, opted-in responders while keeping exact location behind incident-specific acceptance.”
- 0:30–1:15. On A show server health and registered-device status. Trigger the self-test. Show acceptance, the tray entry and opening separately.
- 1:15–2:30. Activate a simulated incident. B accepts inside the radius. Show C's empty inbox. Move A using the synthetic positions below.
- 2:30–3:30. C accepts its new invitation. Compare the latest authorized fixes on B and C. Close with A's PIN, then refresh both sessions and confirm access ends.
- 3:30–4:00. Explain that proximity controls recruitment, acceptance controls disclosure, and closure revokes access.

**Fallback.** Use the Samsung plus two emulators with Google Play services and independent verified accounts when the API is ready. If cloud access is unavailable, show the Android navigation on an emulator and run the isolated PostGIS scenario separately from `backend` with `.\.venv\Scripts\python.exe tests/run_isolated.py -v -k deterministic_a_b_c_d`. Introduce this explicitly as backend test evidence.

**Judges' questions.** Does the first responder drop out as the victim moves? An accepted grant persists until revocation or closure. Did Firebase deliver? Show the receiving phone before claiming receipt. Does it dispatch 112? The controlled demo operates with test users only.

The following steps describe the intended rehearsal. Use three inboxes you control in place of example addresses.

This guide describes the repeatable **Three-Phone Live Demonstration** showcasing SurakshaNet's dynamic moving 1 km geofence, high-priority notifications, persistent responder authorization, and secure Safety PIN deactivation.

---

## 1. Demonstration Setup & Roles

### 1.1 Device Allocation
- **Phone A — Victim**:
  - Account: `victim@test.local` (UID in `TEST_ACCOUNT_UIDS`).
  - Role: Triggers emergency SOS; travels through Points A -> B -> C -> D.
- **Phone B — Responder 1 (Initially Nearby)**:
  - Account: `responder1@test.local` (UID in `TEST_ACCOUNT_UIDS`).
  - Initial Position: **400 metres** from Point A (Inside 1 km alert radius).
  - Role: Receives immediate high-priority FCM notification; accepts and tracks victim.
- **Phone C — Responder 2 (Initially Distant)**:
  - Account: `responder2@test.local` (UID in `TEST_ACCOUNT_UIDS`).
  - Initial Position: **2,400 metres** from Point A (Outside 1 km alert radius).
  - Role: Receives **no alert** initially. Becomes eligible when Phone A reaches Point B.

---

## 2. Synthetic Trajectory & Coordinate Plan

For controlled demonstration on physical or simulated devices, synthetic equatorial coordinates are used (`latitude = 0.0`, `longitude = offset_in_meters / 111319.49`):

| Point | Description | Offset (m) | Latitude | Longitude |
|---|---|---|---|---|
| **Point A** | Victim SOS Origin | 0 m | `0.000000` | `0.000000` |
| **Point B** | Victim Midpoint 1 | 1,900 m | `0.000000` | `0.017068` |
| **Point C** | Victim Midpoint 2 | 3,000 m | `0.000000` | `0.026950` |
| **Point D** | Victim Final Safe Point | 4,200 m | `0.000000` | `0.037730` |
| **Responder 1 (B)** | Stationary Responder | 400 m | `0.000000` | `0.003593` |
| **Responder 2 (C)** | Stationary Responder | 2,400 m | `0.000000` | `0.021559` |

---

## 3. Step-by-Step Live Demonstration Script

### Step 1: Pre-Demo Readiness Check
1. On all 3 devices, launch SurakshaNet.
2. Verify all 3 are logged into their respective test accounts.
3. On Phones B and C:
   - Navigate to the **Responder** tab.
   - Toggle **Duty Mode: ON** (Status shows `DUTY ON · AVAILABLE`).
   - Phone B reports its fix at 400m; Phone C reports its fix at 2400m.
4. On Phone A:
   - Ensure a 6-digit Safety PIN is configured (e.g., `849201`).

### Step 2: Emergency SOS Trigger (Point A)
1. On **Phone A**, press and hold the **Emergency SOS button** for 2 seconds.
2. **Observed on Phone A**:
   - Immediate haptic feedback and expanding red halo.
   - Status transitions to `EMERGENCY SOS RUNNING`.
   - Native foreground service starts (`SosLocationService`), showing notification in system tray.
   - Active Incident Screen displays live digital duration timer and 1 km radar visualization.

### Step 3: Proximity Matching & High-Priority Alert
1. The backend evaluates the 1 km geofence around Point A (0m).
   - Phone B (400m away) <= 1000m: **MATCHED**.
   - Phone C (2400m away) > 1000m: **NOT MATCHED**.
2. **Observed on Phone B**:
   - Receives high-priority push notification: *"Urgent SOS Alert: Nearby responder needed"*.
   - Device executes the **5.2-second multi-pulse vibration pattern** (`[0, 700, 200, 700, 200, 700, 200, 700, 200, 700, 200, 700]`).
3. **Observed on Phone C**:
   - Completely silent; zero notifications received.

### Step 4: Responder 1 Accepts & Initiates Live Tracking
1. On **Phone B**, tap the notification or open the incoming alert sheet.
2. Tap **"Accept & Assist"**.
3. **Observed on Phone B**:
   - App requests single-use WebSocket ticket and opens real-time room `/ws/incidents/{id}`.
   - Live map renders victim's coordinates at Point A.
4. **Observed on Phone A**:
   - Responders count updates from `0` to `1 Accepted`.

### Step 5: Victim Moves to Point B (Dynamic Geofence Expansion)
1. Advance **Phone A**'s position to **Point B (1,900m)**.
2. Distance analysis:
   - Distance between Phone A (1,900m) and Responder 1 (400m) is now **1,500m** (> 1,000m).
   - Distance between Phone A (1,900m) and Responder 2 (2,400m) is now **500m** (<= 1,000m).
3. **Observed on Phone B (Responder 1)**:
   - **Remains fully connected!** Live map updates in real time to show Phone A at Point B.
4. **Observed on Phone C (Responder 2)**:
   - Receives high-priority push notification and 5.2s vibration!
   - Taps **"Accept & Assist"** and joins the incident room.
5. **Observed on Phone A**:
   - Accepted responders counter increases to `2 Accepted`.

### Step 6: Incorrect PIN Rejection Test
1. On **Phone A**, tap **"End SOS with Safety PIN"**.
2. Enter an incorrect PIN: `000000`.
3. **Observed**:
   - Dialog displays error: *"Incorrect Safety PIN. Incident remains active."*
   - Incident timer continues running; foreground service continues tracking; both responders remain connected.

### Step 7: Correct PIN Resolution & Instant Revocation
1. On **Phone A**, enter the correct PIN: `849201`.
2. **Observed on Phone A**:
   - Backend validates Argon2id hash.
   - Status transitions to `CONFIRMED / RESOLVED`.
   - Foreground service terminates; notification removed.
   - Screen returns to Home dashboard in `READY` status.
3. **Observed on Phones B and C**:
   - WebSocket receives `ACCESS_REVOKED` and `INCIDENT_CLOSED`.
   - WebSocket connection immediately closes.
   - Screen displays *"Incident Resolved by User"*.
   - **All coordinates and markers are purged from device memory.**

### Step 8: Trusted Emergency Contacts & Fallback Alert Demonstration
1. Navigate to the **Contacts** tab on **Phone A**.
2. Tap **"From Phone"** or **"Add Manually"**.
3. Choose or enter a contact (e.g. "Mom" at `+91 98765 43210`).
4. Review granular notification preferences:
   - Alert on SOS Trigger: **ON**
   - Include Live Location Link: **ON**
   - Allow Live Incident Tracking: **ON**
   - Safe Resolution Notification: **ON**
5. Tap **"Save Contact"**. Contact appears with Liquid Glass card and preference chips (`SOS`, `TRACK`, `SAFE`).
6. Tap the **"Test Alert"** action button:
   - Dialog renders simulated emergency alert with masked phone `+91*****3210`.
   - Message preview includes Google Maps coordinates link and 112 emergency services advisory.
   - Status badge shows `PROVIDER_ACCEPTED (TEST)`.
   - Tapping **"Open SMS Composer"** launches the native Android SMS composer (`ACTION_SENDTO` with `smsto:`) with pre-composed text—requiring zero broad permissions.
7. During active SOS:
   - Dedicated **Trusted Contact Alerts** card displays real-time dispatch state.
   - **"Send Emergency SMS Fallback"** button provides one-tap manual carrier SMS composer.
   - **"Call 112 Emergency Services"** button opens the native phone dialer (`ACTION_DIAL` with `tel:112`).

---

## 4. Demonstration Checklist Summary

- [x] Initial 1 km filter notifies only Phone B (Phone C silent).
- [x] 5.2-second multi-pulse vibration verified.
- [x] Authorized live streaming verified on Phone B.
- [x] Moving geofence discovers Phone C at Point B.
- [x] Persistent authorization keeps Phone B connected at 1,500m.
- [x] Wrong PIN rejected; SOS preserved.
- [x] Correct PIN verified; incident closed; access revoked; memory purged.
- [x] Trusted contact selection and granular preferences verified.
- [x] Simulated contact alert dispatch verified.
- [x] Zero-permission native SMS composer (`ACTION_SENDTO`) verified.
- [x] Zero-permission emergency dialer (`ACTION_DIAL` 112) verified.
