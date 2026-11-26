# SOS flow, end to end

Part of the [project handbook](../../PROJECT_HANDBOOK.md#63-sos-end-to-end), which has the step table with code
citations.

```mermaid
sequenceDiagram
  autonumber
  actor Victim
  participant VA as Victim app
  participant API as API (Render)
  participant DB as Neon (PostGIS)
  participant FCM as Firebase Cloud Messaging
  participant RA as Volunteer app
  actor Vol as Volunteer
  participant CA as Circle member app
  participant R2 as Cloudflare R2

  Victim->>VA: hold SOS 3 s
  VA->>VA: session key saved; SosLocationService starts
  VA->>API: POST /api/incidents (Idempotency-Key)
  API->>DB: INSERT incidents (rate limit SOS_PER_HOUR)
  API-->>VA: 201 incident (or 409 INCIDENT_ALREADY_OPEN -> adopt)
  par circle alert
    VA->>API: POST /api/incidents/{id}/trusted-contacts/alert
    API->>DB: inbox_items CIRCLE_SOS (linked) + incident_contact_alerts
    API-->>VA: sms_contacts + live link /l/{token}
    VA->>Victim: offer SMS composer (user taps Send)
    API->>FCM: CIRCLE_SOS data message
    FCM->>CA: push; member opens live location
  and location + geofence
    loop every ~5 s
      VA->>API: POST /api/incidents/{id}/location
      API->>DB: UPDATE incidents.latest_location
      API->>DB: ST_DWithin(1 km): approved, available volunteers,<br/>location < 30 min -> incident_invitations + notification_outbox
    end
  end
  API->>FCM: RESPONDER_INVITATION_CREATED (ids, expiry; no coordinates)
  FCM->>RA: high-priority data message (also in Doze, app swiped away)
  RA->>API: GET /api/responders/invitations (distance band, <= 2.5 s)
  RA->>Vol: heads-up / full-screen alert, keeps vibrating (<= 2 min)
  Vol->>RA: Accept (unlock if locked)
  RA->>API: PUT /api/responders/location (fresh fix)
  RA->>API: POST /api/responders/invitations/{id}/response ACCEPTED
  API->>DB: approval + fresh fix + radius checks; INSERT incident_responders
  API-->>VA: WebSocket RESPONDER_JOINED
  RA->>API: GET /snapshot + WebSocket (screen 07, live location)
  opt evidence
    VA->>API: POST /api/files/upload-url (SOS_VOICE / SOS_PHOTO)
    VA->>R2: PUT file (presigned)
    VA->>API: POST /api/files/{id}/complete
    CA->>API: GET /api/files/incidents/{id}/media -> download URL
  end
  Victim->>VA: End SOS + Safety PIN
  VA->>API: POST /api/incidents/{id}/resolve
  API->>DB: Argon2 check (5 tries -> 5 min lock); incidents CLOSED
  API->>DB: revoke_incident_access: expire invitations, revoke responders/grants
  API->>FCM: CIRCLE_SOS_ENDED (circle), SOS_REQUEST_CLOSED (open requests)
  API-->>RA: WebSocket INCIDENT_CLOSED
  Note over DB,R2: Retention: location erased 7 days after closing;<br/>photos and voice notes deleted after 30 days
```
