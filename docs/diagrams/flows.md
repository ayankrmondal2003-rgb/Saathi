# Other flows

Part of the [project handbook](../../PROJECT_HANDBOOK.md#6-key-flows-end-to-end). Sign-in, startup, trips and
check-ins, volunteer review and keep-alive are drawn inline in section 6. These are the two longer ones.

## Circle linking: invite code, phone match and add back

```mermaid
sequenceDiagram
  actor A as Owner (A)
  participant AA as A's app
  participant API as API (circle.py)
  participant DB as Neon
  participant FCM as FCM
  participant BA as B's app
  actor B as Friend (B)

  A->>AA: Add contact B (name, phone)
  AA->>API: POST /api/trusted-contacts
  API->>DB: INSERT trusted_contacts
  alt B's phone is Firebase-verified
    API->>DB: circle_invites (target_user_id = B) + inbox CIRCLE_REQUEST
    API->>FCM: push to B
    B->>BA: Accept in Inbox
    BA->>API: POST /api/circle/requests/{id}/accept
  else invite code
    AA->>API: POST /api/circle/invites
    API-->>AA: 8-char code (24 h, stored as SHA-256)
    A->>B: shares the code (any messenger)
    B->>BA: Enter a code from a friend
    BA->>API: POST /api/circle/invites/accept (10 tries/hour)
  end
  API->>DB: INSERT circle_links (owner A -> recipient B)
  API->>FCM: CIRCLE_LINKED to A
  Note over AA,BA: A's SOS now reaches B as a push (one way)
  B->>BA: Circles you're in -> Add A to my circle too
  BA->>API: POST /api/circle/memberships/{link}/add-back
  API->>DB: B's trusted_contacts (A) + circle_invites target A
  API->>FCM: CIRCLE_REQUEST to A
  A->>AA: Accept -> circle_links (B -> A): now mutual
```

## Presigned upload and download

```mermaid
sequenceDiagram
  participant App
  participant API as API (files.py)
  participant DB as Neon
  participant R2 as Cloudflare R2
  App->>API: POST /api/files/upload-url {kind, mime_type, size_bytes}
  API->>API: type allowlist, <= 10 MB, kind rules
  API->>DB: INSERT stored_files (PENDING, random object key)
  API-->>App: presigned PUT URL (300 s)
  App->>R2: PUT bytes (the API never sees them)
  App->>API: POST /api/files/{id}/complete
  API->>DB: stored_files UPLOADED (+ volunteer_applications.has_*)
  Note over App,R2: Later
  App->>API: GET /api/files/{id}/download-url
  API->>API: owner, admin (logged) or allowed circle member?
  API-->>App: presigned GET URL (300 s)
  App->>R2: GET bytes
```
