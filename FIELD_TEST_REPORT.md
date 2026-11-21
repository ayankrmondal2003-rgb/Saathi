# SurakshaNet — Field Test & Real-Device Telemetry Report

## 1. Physical Device & Environment Profile

- **Device Hardware**: Samsung Galaxy F62 (`samsung SM-E625F`)
- **ADB Serial**: `RZ8R903LKWR`
- **Android OS Version**: Android 13 (API Level 33, One UI 5.1)
- **Architecture**: ARM64-v8a (`exynos9825`)
- **Battery Capacity**: 7,000 mAh
- **Location Hardware**: Dual-band GNSS (GPS, GLONASS, Galileo, BeiDou)
- **Network Interface**: Wi-Fi 802.11ac / 4G LTE VoLTE

---

## 2. GPS & Battery Benchmark Observations

### 2.1 GPS Fix Characteristics & Latency
Under clear-sky conditions in Bangalore, India:
- **Cold TTFF (Time-To-First-Fix)**: 6.8 seconds.
- **Warm / Hot TTFF**: 1.1 seconds.
- **Reported Accuracy**: ±3.8 m to ±6.2 m outdoor; ±18.4 m indoor near window.
- **Update Frequency**: 1 Hz (1 fix/sec) during active emergency streaming.
- **WebSocket Telemetry Ingress Latency**: ~28 ms round-trip over 4G LTE.

### 2.2 Battery Consumption Benchmarks (Samsung SM-E625F)

| Operational State | Duration | Screen State | GPS State | Measured Battery Drain | Normalized Hourly Rate |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Idle Background** | 60 mins | OFF | Standby | 0.4% | ~0.4% / hour |
| **Active SOS (Foreground)** | 30 mins | ON (100% brightness) | 1 Hz Active GNSS | 3.8% | ~7.6% / hour |
| **Active SOS (Background)** | 45 mins | OFF | 1 Hz FG Service | 2.1% | ~2.8% / hour |
| **Responder Live Tracking** | 30 mins | ON (Map Canvas) | 1 Hz Rx WebSocket | 3.2% | ~6.4% / hour |

*Note: Samsung One UI "Sleeping apps" and "Deep sleeping apps" features must be set to "Never auto-sleep" or "Unrestricted" battery usage for SurakshaNet to ensure the Foreground Service is not terminated during long-duration incidents.*

---

## 3. Background Survival & Connectivity Recovery Test Matrix

| Scenario | Trigger / Action | Expected Result | Observed Behavior | Status |
| :--- | :--- | :--- | :--- | :--- |
| **App Minimized** | Home button tapped during active SOS | Notification persists, fixes continue | Fixes continue streaming at 1s intervals | **VERIFIED** |
| **Screen Locked** | Power button tapped | Lockscreen notification, no GPS drop | Location fixes continue, accuracy maintained | **VERIFIED** |
| **GPS Toggled Off** | Android Location disabled in quick settings | UI warns, updates halt safely | State shifts to `LOCATION_UNAVAILABLE` | **VERIFIED** |
| **GPS Restored** | Location re-enabled | Tracking automatically resumes | Fixes resume streaming within 1.5s | **VERIFIED** |
| **Network Loss** | Airplane mode during active SOS | Fixes cached in local offline queue | Offline queue records fixes with timestamps | **VERIFIED** |
| **Network Restored** | Airplane mode disabled | Offline queue replayed to server | Fixes replayed; valid fixes ingested | **VERIFIED** |
| **Process Force Stop** | User clicks "Force Stop" in Android settings | App process killed completely | Process killed; OS prevents auto-restart | **DOCUMENTED BOUNDARY** |
| **Device Shutdown** | Phone powered off | Power lost | System terminates; restart requires user launch | **DOCUMENTED BOUNDARY** |
