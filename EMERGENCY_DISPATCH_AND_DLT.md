# SurakshaNet — Emergency SMS Gateway, Indian DLT Compliance & ERSS-112 Integration Architecture

## 1. Executive Summary & Regulatory Safety Notice

SurakshaNet is designed as a privacy-preserving, decentralized community safety application with a moving 1 km SOS responder discovery network. In adherence to legal and emergency standards, **SurakshaNet does NOT execute automated live carrier SMS transmissions without user intent, nor does it automatically dispatch requests to the national emergency switchboard (112).**

All emergency routing must pass through explicit user interaction (Android dialer intent `Intent.ACTION_DIAL tel:112` and system SMS composer intents) or certified enterprise gateways subject to regulatory licensing.

---

## 2. Indian DLT Compliance Requirements (TRAI TCCCPR 2018)

Any production deployment sending automated or transactional A2P (Application-to-Person) SMS in India must comply with the Telecom Regulatory Authority of India (TRAI) Telecom Commercial Communications Customer Preference Regulations (TCCCPR, 2018).

### 2.1 Entity & Header Registration
1. **Principal Entity (PE) Registration**:
   - The operating organization (trust, NGO, or commercial entity) must register on a DLT portal (e.g., Jio, Airtel, Vodafone Idea, or BSNL DLT).
   - Requires Corporate PAN, GSTIN, and corporate authorization letters.
   - Generates a unique **Principal Entity ID (PE ID)**.

2. **Sender ID (Header) Registration**:
   - Category: **Service Implicit** (for transactional emergency safety alerts) or **Government/Public Utility**.
   - Header: 6-character alphabetic string, e.g., `SURNET`, `SURKSH`, or `EMRNET`.
   - Bound to the registered PE ID.

### 2.2 Content Template Registration
All SMS messages sent via enterprise SMS APIs must strictly adhere to pre-approved templates with immutable wording and variable placeholders:

```text
Template Name: SURAKSHANET_EMERGENCY_SOS_ALERT
Category: Service Implicit
Registered Sender: SURNET
DLT Template ID: 1407168923000012345

Template Text:
EMERGENCY ALERT: {#var#} has triggered an SOS alert via SurakshaNet. Last known location: {#var#}. Live tracking link: {#var#}. Immediate assistance requested.
```

Variable Tokens:
- `{#var#}` 1: Victim Name or Anonymous Victim ID
- `{#var#}` 2: Geolocation string (e.g., `12.9716, 77.5946` or landmark)
- `{#var#}` 3: Secure temporary tokenized URL (e.g., `https://surakshanet.org/track/{ticket}`)

### 2.3 Carrier Consent Scrubbing & DND Bypass
- **Service Implicit** category bypasses Do Not Disturb (DND) National Customer Preference Register (NCPR) restrictions.
- Message routing must use Priority Routing queues (TTL < 10 seconds) on telecom operator SMSCs to ensure sub-second delivery during life-threatening incidents.

---

## 3. SMS Gateway Integration (Twilio / Gupshup / Karix)

SurakshaNet provides the `EmergencyServiceGateway` and `FakeSmsProvider` abstractions in [EmergencyServiceGateway.kt](file:///C:/Users/ayank/AndroidStudioProjects/SurakshaNet/app/src/main/java/com/example/surakshanet/notification/EmergencyServiceGateway.kt).

### 3.1 Architecture Overview

```
+---------------------+        +------------------------+        +--------------------------+
|  SurakshaNet App    |  --->  | FastAPI Backend        |  --->  | SMS Provider             |
|  (Emergency Trigger)|        | (Outbox & DLT Enforcer)|        | (Gupshup / Karix / Twilio)
+---------------------+        +------------------------+        +--------------------------+
                                           |                                   |
                                           v                                   v
                               +------------------------+        +--------------------------+
                               | PostGIS Audit Store    |        | Indian Telecom SMSC      |
                               +------------------------+        +--------------------------+
```

### 3.2 Gupshup / Karix REST Integration (Production Spec)
When production enterprise SMS is provisioned, the backend background worker executes transactional posts:

```python
async def send_dlt_sos_sms(
    recipient_phone: str,
    victim_name: str,
    location_summary: str,
    live_track_url: str
) -> bool:
    payload = {
        "channel": "sms",
        "source": "SURNET",
        "destination": recipient_phone,
        "message": f"EMERGENCY ALERT: {victim_name} has triggered an SOS alert via SurakshaNet. Last known location: {location_summary}. Live tracking link: {live_track_url}. Immediate assistance requested.",
        "dltTemplateId": "1407168923000012345",
        "dltEntityId": "1101569870000043210"
    }
    # Secure transmission over mTLS / API Token
    ...
```

---

## 4. ERSS-112 (Emergency Response Support System) Integration

Emergency Response Support System (ERSS) 112 is the pan-India single emergency number administered by the Ministry of Home Affairs (MHA) and implemented technically by C-DAC (Centre for Development of Advanced Computing).

### 4.1 Legal & Institutional Prerequisites
Direct machine-to-machine (M2M) API integration with the ERSS-112 Computer Aided Dispatch (CAD) system is strictly restricted:
1. **Institutional Memorandum of Understanding (MoU)**: Must be executed between the deploying state police department / MHA and the SurakshaNet operational entity.
2. **Security Certification**: Application and backend infrastructure must undergo CERT-In (Indian Computer Emergency Response Team) empanelled security auditing.
3. **mTLS & Dedicated VPN**: ERSS-112 CAD endpoints are private and require IPsec tunnel peering with mutual TLS (mTLS) authentication.

### 4.2 ERSS-112 CAD REST API Specification (M2M Standard)
Upon formal clearance, the backend initiates dispatch incidents via authenticated CAD ingress:

```json
POST /erss/v1/cad/incident/ingest
Content-Type: application/json
Authorization: Bearer <STATE_CAD_JWT_TOKEN>

{
  "agencyCode": "POLICE",
  "incidentType": "WOMEN_SAFETY_SOS",
  "caller": {
    "phone": "+919876543210",
    "name": "Citizen User",
    "verifiedPhone": true
  },
  "location": {
    "latitude": 12.9715987,
    "longitude": 77.5945627,
    "accuracy": 8.5,
    "addressDescription": "MG Road near Metro Station",
    "timestampIso": "2026-11-21T05:14:00Z"
  },
  "telemetry": {
    "liveTrackToken": "https://surakshanet.org/cad-view/3fa85f64",
    "batteryLevel": 84,
    "movingGeofenceRadiusMeters": 1000
  }
}
```

### 4.3 Fallback and Safety Guarantees
- In all non-certified and client environments, `DisabledEmergencyServiceGateway` logs rejection with code `GATEWAY_DISABLED_MHA_MOU_REQUIRED`.
- The user is provided instantaneous access to native phone dialer pre-filled with `112`:
  ```kotlin
  val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
  context.startActivity(dialIntent)
  ```
- No silent or unattended dispatch occurs without user consent.
