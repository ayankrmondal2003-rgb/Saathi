# Known limitations

Phase 17 has no observed Render deployment, real FCM delivery, locked-screen GPS trace or live Redis interruption test. The self-test sends directly through FCM. Invitation dispatch uses the outbox. A force-stop or shutdown can interrupt tracking. A successful health probe does not validate Firebase, Redis, notification permission or device receipt.

**IMPORTANT DISCLAIMER**: SurakshaNet is an experimental research and demonstration platform designed to evaluate moving geofence protocols and community responder coordination. It is **NOT** a certified emergency service, Public Safety Answering Point (PSAP), or substitute for official government emergency helplines (e.g., 112 in India, 911 in North America, or 999 in the UK).

---

## 1. Operating System & Hardware Boundaries

### 1.1 OEM Background Task Policies ("Battery Killers")
- Android manufacturers (particularly Xiaomi/MIUI, Samsung/OneUI, Huawei, Oppo, and Vivo) enforce aggressive, non-standard background process termination policies.
- Even with `FOREGROUND_SERVICE` and `WAKE_LOCK` permissions, the OS may terminate or freeze background tracking if:
  - The device enters deep Doze mode.
  - The battery level drops below manufacturer-specific thresholds (e.g. < 15%).
  - The user has not manually toggled "Unrestricted Battery Usage" in OS system settings.
- The Profile screen in SurakshaNet provides a direct deep-link to Android battery optimization settings with explicit user instructions.

### 1.2 Force Stop & Device Shutdown Boundary
- **Android Security Sandbox Contract**: If the application process is manually terminated via Android System Settings ("Force Stop") or if the device is powered off, Android strictly forbids background services, alarms, or broadcast receivers from restarting until the user explicitly re-launches the application. SurakshaNet cannot guarantee continuous tracking under force-stop or powered-off conditions.

### 1.3 GPS Accuracy & Sensor Limitations
- **Indoor Environments**: GPS satellite signals degrade significantly inside multi-story concrete structures, basements, and underground transit systems. In these scenarios, the Fused Location Provider falls back to Wi-Fi / cell tower triangulation, which may exhibit accuracy errors exceeding 50–200 metres.
- **Urban Canyons**: High-rise buildings reflect satellite signals (multipath interference), occasionally resulting in artificial location jumps.
- **Sensor Warm-up Delay**: Cold starts on GPS hardware can take 5–15 seconds to achieve a lock with accuracy < 20 metres.

---

## 2. Network & Notification Pipeline Realities

### 2.1 Firebase Cloud Messaging (FCM) Requirements
- Real push notification delivery requires `app/google-services.json` to be configured and Google Play Services to be available.
- On AOSP emulators or devices without Google Play Services, FCM push notifications are unavailable. SurakshaNet transitions to `FcmTokenState.PlayServicesUnavailable` or `ConfigurationError` and displays clear actionable diagnostics.
- Local notification and vibration tests function 100% independently of cloud services.

### 2.2 FCM Delivery Latency under Doze Mode
- While `urgent_sos_v1` is configured with `NotificationCompat.PRIORITY_HIGH` and FCM high-priority flags, delivery time is contingent on Google Play Services and carrier connectivity.
- Under severe network congestion, Doze maintenance windows, or low-bandwidth cellular conditions (2G/EDGE), push delivery may experience delays ranging from a few seconds to several minutes.

### 2.3 Distributed Realtime Bus Scalability
- Phase 15 integrated Redis Pub/Sub for cross-worker event broadcasting while preserving PostgreSQL as the authoritative store of record.
- In single-node development environments without Redis, the app falls back to in-memory message distribution.

### 2.4 Offline Queuing Window
- When network connectivity is lost during an ongoing SOS, the Android client queues GPS fixes in a local SQLite database (`TestIncidentSync`).
- Upon reconnection, fixes are replayed sequentially. However, fixes older than `max_location_age_seconds` (120 seconds) are flagged as stale by the backend to prevent outdated geofence reevaluations.

---

## 3. Regulatory & Legal Dispatch Boundaries

1. **Carrier A2P SMS Regulation (TRAI TCCCPR 2018)**: Direct automated SMS dispatch in India requires Principal Entity ID, Service Implicit Header registration (`SURNET`), and pre-approved DLT content templates. Automatic unsolicited carrier SMS is disabled in the client; the app launches the native Android SMS composer (`ACTION_SENDTO`) with user consent.
2. **National Emergency Helplines (ERSS-112)**: Direct machine-to-machine Computer Aided Dispatch (CAD) integration with 112 requires an institutional MoU with the Ministry of Home Affairs / state police and CERT-In security clearance. The app provides instant one-tap dialing via `Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))` without automated unattended dispatch.
3. **Public Broadcasting Safeguard**: Real public SOS broadcasting is intentionally disabled in this codebase. All emergency tests must remain restricted to authorized development accounts and simulated environments.
