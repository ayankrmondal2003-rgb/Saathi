# SurakshaNet — FCM Token Diagnostics & Lifecycle Architecture

## 1. Executive Summary & Root Cause Analysis

### 1.1 The "Pending initialization" Root Cause
Prior to Phase 16, the Profile & Settings screen displayed a permanent status:
`FCM Cloud Messaging Token: CHECK — Pending initialization`

**Root Cause Breakdown**:
1. **Silent Fallback Without Error Propagation**:
   `SafetyApp.kt` initialized Firebase using `runCatching { FirebaseApp.initializeApp(context) }.getOrNull()`. When `app/google-services.json` is missing or unconfigured, `FirebaseApp.initializeApp()` threw `IllegalStateException` or returned null.
2. **Missing State Model**:
   `SafetyReadinessSection` in `CommunityScreens.kt` simply checked whether `getLocalFcmToken(context)` was non-null. When null, it permanently displayed `"Pending initialization"`, providing no indication of whether the failure was due to missing configuration, Google Play Services absence, network timeouts, or unauthenticated user state.
3. **Missing Token Acquisition Lifecycle**:
   The Profile screen never initiated token retrieval or registration. If token registration failed during startup, no retry mechanism existed.

---

## 2. Strongly Typed FCM Lifecycle (`FcmTokenState`)

To eliminate ambiguous UI states, Phase 16 introduces [FcmTokenState.kt](file:///C:/Users/ayank/AndroidStudioProjects/SurakshaNet/app/src/main/java/com/example/surakshanet/notification/FcmTokenState.kt):

```
+--------------------------------------------------------------------------------------------------------+
|                                        FCM TOKEN LIFECYCLE                                             |
+--------------------------------------------------------------------------------------------------------+

 [ Initializing ]
         |
         +---> (No google-services.json) ----> [ ConfigurationError ]  (Badge: CONFIG REQUIRED)
         |
         +---> (No Google Play Services) ----> [ PlayServicesUnavailable ] (Badge: UNAVAILABLE)
         |
         v
 [ ConnectingToFirebase ]
         |
         +---> (Network timeout/error) ------> [ NetworkError ] (Badge: OFFLINE)
         |
         +---> (FCM rejection/exception) ----> [ RegistrationFailed ] (Badge: FAILED)
         |
         v
 [ TokenReady (token) ]
         |
         +---> (User not signed in) ---------> [ AuthenticationRequired ] (Badge: SIGN IN)
         |
         v
 [ RegisteringWithBackend (token) ]
         |
         +---> (Backend connection drop) ----> [ NetworkError ] (Badge: OFFLINE)
         |
         +---> (Backend rejection 4xx/5xx) --> [ RegistrationFailed ] (Badge: FAILED)
         |
         v
 [ Registered (token) ]  <=================== ACTIVE (Verified and Ready)
```

### State Definitions & UI Mappings

| State | Badge Text | UI Description | Action Available |
| :--- | :--- | :--- | :--- |
| `Initializing` | `INIT` | Initializing Firebase & GMS checks... | None (in progress) |
| `ConnectingToFirebase` | `CONNECTING` | Connecting to Firebase Cloud Messaging... | None (in progress) |
| `ConfigurationError` | `CONFIG REQUIRED` | Check Firebase setup (google-services.json missing) | Setup Guide Link |
| `PlayServicesUnavailable` | `UNAVAILABLE` | Google Play services unavailable on this device | System Update Prompt |
| `NetworkError` | `OFFLINE` | Network unavailable · Check connection | Retry Button |
| `TokenReady` | `READY` | Token ready · Pending authenticated backend sync | Retry / Sync Button |
| `AuthenticationRequired` | `SIGN IN` | Sign in required to register device token | Auth Navigation |
| `RegisteringWithBackend` | `REGISTERING` | Registering device with backend... | Progress indicator |
| `Registered` | `ACTIVE` | Ready to receive test invitations (`{token[:8]}...`) | Send Test Alert CTA |
| `RegistrationFailed` | `FAILED` | Registration failed · Tap Retry | Retry Button |

---

## 3. Backend Device Token Security & Registration

The FastAPI endpoint `POST /api/devices/register` enforces strict multi-tenant security in [routes.py](file:///C:/Users/ayank/AndroidStudioProjects/SurakshaNet/backend/app/routes.py#L374):

1. **Advisory Lock Concurrency**:
   `SELECT pg_advisory_xact_lock(hashtextextended(:token, 0))` prevents race conditions during simultaneous device token updates.
2. **Account Switching Protection**:
   ```sql
   UPDATE devices 
   SET is_active = false, disabled_at = clock_timestamp()
   WHERE fcm_token = :token AND is_active AND (user_id <> :uid OR device_identifier <> :identifier)
   ```
   If User A signs out and User B logs into the same physical phone, User A's token association is immediately deactivated so User B never receives User A's emergency invitations.
3. **Idempotent Device Registration**:
   `ON CONFLICT (user_id, device_identifier) DO UPDATE SET fcm_token = EXCLUDED.fcm_token, is_active = true`.

---

## 4. End-to-End Test Notification Workflow

When the user taps **"Send Test Alert to This Phone"**:
1. [DeviceRepository.kt](file:///C:/Users/ayank/AndroidStudioProjects/SurakshaNet/app/src/main/java/com/example/surakshanet/notification/DeviceRepository.kt) verifies that `tokenState` is `Registered`. If not, it displays a descriptive notice guiding the user to complete registration.
2. The authenticated client invokes `POST /api/devices/test-alert`.
3. The backend validates that the calling user possesses an active device record.
4. An authorized `TEST_ALERT` payload is enqueued and dispatched via Firebase Admin SDK.
5. The Android device displays the notification on the `urgent_sos_v1` high-importance channel with emergency vibration.
6. The test timestamp is stored and reflected in the UI under **Last Received Emergency Alert**.

---

## 5. Local Hardware Diagnostic Independence

Local testing buttons function 100% independently of cloud services:
- **Post Local Test Notification**: Fires `NotificationHelper.showSosNotification()` directly using the Android `NotificationManager`.
- **Test Emergency Vibration Pattern**: Fires the ~5.2-second 6-pulse pattern `longArrayOf(0, 500, 200, 500, 200, 500)` directly via Android `Vibrator` / `VibratorManager`.
- **Open Android Notification Settings**: Launches an intent directly to `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` for `urgent_sos_v1`.
