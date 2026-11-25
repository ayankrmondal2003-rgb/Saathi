# Maitri API reference

The endpoint table below is generated from the server code by `backend/scripts/api_docs.py`. Don't edit it by hand:
`backend/tests/test_api_docs.py` fails when it no longer matches the routes.

## Basics

- **Base URL:** the Render service, for example `https://surakshanet-api.onrender.com`. The app reads it from
  `maitriProdApiUrl` (release) or `maitriDevApiUrl` (debug) in `local.properties`.
- **Sign-in:** every `/api/...` call except the public ones in the table needs `Authorization: Bearer <Firebase ID
  token>`. The server verifies the token with Firebase Admin and finds or creates the account. Accounts must be
  active; in `ENVIRONMENT=beta` any verified account may use the service, otherwise only `TEST_ACCOUNT_UIDS`.
- **Admin:** `/api/admin/...` additionally needs the Firebase custom claim `{"admin": true}`
  (`backend/scripts/set_admin_claim.py`). The `/admin` page itself is public HTML that signs in with Firebase.
- **Idempotency:** `POST /api/incidents` and `POST /api/circle-sharing/sessions` take an `Idempotency-Key` header
  (a UUID). Repeating a request with the same key returns the original result instead of creating a second one.
- **Errors:** JSON `{"detail": ...}`. Where the app needs to react to a specific case, `detail` is
  `{"code": "...", "message": "..."}`, for example `SOS_RATE_LIMITED` (429), `STORAGE_NOT_CONFIGURED` (503),
  `MEDICAL_NOTES_UNAVAILABLE` (503), `INVITE_NOT_FOUND` (404). Messages are safe to show; they never contain
  other people's data. Rate limits answer 429.
- **Real time:** `POST /api/incidents/{id}/ws-ticket` returns a short-lived ticket for the `/ws/...` WebSocket, which
  streams incident updates. Pushes go through Firebase Cloud Messaging as data messages.
- **Live links:** `/l/{token}` is a public page with a person's live location during their SOS; the token is an
  HMAC (LIVE_LINK_SECRET) and stops working when the SOS ends.
- **Files:** uploads go straight to private Cloudflare R2 storage with a presigned URL from
  `POST /api/files/upload-url`, then `POST /api/files/{id}/complete`. Downloads use short-lived presigned URLs.
  SOS photos and voice notes are visible to the sender and to the circle members who got that SOS's alert;
  volunteer responders never see them.

## Endpoints

Access: **public** (no sign-in), **account** (Firebase ID token), **admin** (token with the admin claim).


### `app.admin`

| Method | Path | Access | What it does |
|---|---|---|---|
| GET | `/admin` | public | Volunteer review page (HTML). Signs in with Firebase; its API calls need the admin claim. |
| GET | `/api/admin/applications` | admin | Volunteer applications waiting for review, newest first. |
| GET | `/api/admin/applications/{application_id}` | admin | One application with its documents' metadata. |
| POST | `/api/admin/applications/{application_id}/decision` | admin | Approve or reject an application, with a note to the applicant. |
| POST | `/api/admin/applications/{application_id}/documents/{file_id}/url` | admin | Short-lived download link for one document of the application. Logged. |

### `app.circle`

| Method | Path | Access | What it does |
|---|---|---|---|
| POST | `/api/circle/invites` | account | Creates an 8-character invite code for one trusted contact (valid 7 days). |
| POST | `/api/circle/invites/accept` | account | The contact enters the code: links their account to that trusted contact. Rate limited. |
| GET | `/api/circle/memberships` | account | Circles this account belongs to (whose SOS alerts it receives), and whether it has added each owner back. |
| POST | `/api/circle/memberships/{link_id}/add-back` | account | Save the circle's owner as my contact and send them a circle request (their choice to accept). |
| GET | `/api/circle/requests` | account | Circle requests waiting for this account's answer (made by phone-number match). |
| POST | `/api/circle/requests/{request_id}/{decision}` | account | Accept or decline a circle request (`decision` is accept or decline). |

### `app.circle_sharing`

| Method | Path | Access | What it does |
|---|---|---|---|
| GET | `/api/circle-sharing/links` | account | This account's linked contacts (people who receive its alerts in the app). |
| POST | `/api/circle-sharing/links` | account | Links a contact using a pairing code. |
| DELETE | `/api/circle-sharing/links/{link_id}` | account | Removes a link; that person stops receiving alerts and shared locations. |
| POST | `/api/circle-sharing/pairing-code` | account | Creates a short-lived pairing code for a contact. |
| GET | `/api/circle-sharing/requests/{key}` | account | Looks up a trip or check-in by the Idempotency-Key it was created with (retry after a lost reply). |
| GET | `/api/circle-sharing/sessions` | account | Trips and check-ins shared with this account or started by it. |
| POST | `/api/circle-sharing/sessions` | account | Starts a shared trip or a timed check-in for chosen contacts. Needs Idempotency-Key. |
| GET | `/api/circle-sharing/sessions/{session_id}` | account | A trip or check-in: status, due time and, while active, the latest location. |
| PUT | `/api/circle-sharing/sessions/{session_id}/location` | account | The owner's latest location for an active trip. |
| POST | `/api/circle-sharing/sessions/{session_id}/opened` | account | A recipient opened the shared trip or check-in (shown to the owner). |
| POST | `/api/circle-sharing/sessions/{session_id}/stop` | account | Ends a trip, or checks in safely. |

### `app.files`

| Method | Path | Access | What it does |
|---|---|---|---|
| GET | `/api/files/incidents/{incident_id}/media` | account | Uploaded SOS photos and voice notes for one SOS, oldest first, for its owner and their alerted circle. |
| GET | `/api/files/profile-photo` | account | Download link for this account's profile photo, if one is set. |
| POST | `/api/files/profile-photo` | account | Makes an uploaded image the profile photo (the previous one is deleted). |
| POST | `/api/files/upload-url` | account | Presigned upload to private storage for a volunteer document, SOS photo or voice note, or profile photo. |
| DELETE | `/api/files/{file_id}` | account | Deletes one of this account's files from storage. |
| POST | `/api/files/{file_id}/complete` | account | Confirms an upload: the server checks the stored object's size before the file is usable. |
| GET | `/api/files/{file_id}/download-url` | account | Short-lived download link: own files, or SOS media from an SOS this account was alerted to. |

### `app.inbox`

| Method | Path | Access | What it does |
|---|---|---|---|
| GET | `/api/inbox` | account | Inbox items: circle SOS alerts, SOS ended, circle requests, check-in reminders and missed check-ins. |
| POST | `/api/inbox/read-all` | account | Marks every inbox item read. |
| POST | `/api/inbox/{item_id}/delivered` | account | The app calls this when the push reached the phone: the only honest basis for "Delivered". |
| POST | `/api/inbox/{item_id}/read` | account | Marks one inbox item read. |

### `app.live_link`

| Method | Path | Access | What it does |
|---|---|---|---|
| GET | `/l/{token}` | public | Public live-location page for an active SOS (map, last update). Stops working when the SOS ends. |
| GET | `/l/{token}/location` | public | The live-location page's data: latest position and time, while the SOS is active. |

### `app.main`

| Method | Path | Access | What it does |
|---|---|---|---|
| GET | `/health` | public | Liveness: the process is up. |
| GET | `/health/db` | public | Deep check for manual diagnosis. It wakes the database; never point a monitor or keep-alive at it. |
| GET | `/ready` | public | Cheap: answers from this process only and never opens a database connection, so Render's health check and the cron-job.org keep-alive keep the API warm without waking a suspended Neon compute. Database problems surface on the first API call and on /health/db. |

### `app.privacy`

| Method | Path | Access | What it does |
|---|---|---|---|
| DELETE | `/api/users/me` | account | Deletes the account everywhere: database rows, private files in R2, and the Firebase sign-in. |
| GET | `/api/users/me/export` | account | Everything Maitri keeps about this account, as JSON. Files are listed, not included (they're private). |
| GET | `/api/users/me/medical-notes` | account | The account's medical notes (stored encrypted; shown to an accepted responder during an SOS). |
| PUT | `/api/users/me/medical-notes` | account | Saves medical notes (encrypted with MEDICAL_NOTES_KEY; 503 if the key isn't set). |

### `app.routes`

| Method | Path | Access | What it does |
|---|---|---|---|
| POST | `/api/devices/register` | account | Registers this phone's push token for the account. |
| POST | `/api/devices/{device_id}/test-alert` | account | Sends a test push to one of the account's phones. |
| DELETE | `/api/devices/{token}` | account | Removes a push token (sign-out). |
| POST | `/api/incidents` | account | Starts an SOS with the first location. Needs Idempotency-Key; at most SOS_PER_HOUR real SOS per hour. |
| GET | `/api/incidents/active` | account | The caller's own open (non-CLOSED) incident, so the app can resume it after losing local state. |
| POST | `/api/incidents/{incident_id}/invitations/{invitation_id}/revoke` | account | The owner removes a responder who accepted. |
| GET | `/api/incidents/{incident_id}/location` | account | Latest location of the person in need, for the owner and authorized responders. |
| POST | `/api/incidents/{incident_id}/location` | account | The owner's new location fix during the SOS. |
| POST | `/api/incidents/{incident_id}/report` | account | An accepted responder flags an SOS (e.g. a false alarm). Recorded for review; nothing changes for the owner. |
| POST | `/api/incidents/{incident_id}/request-help` | account | An authorized responder asks for more nearby responders ("Bring in help"). |
| POST | `/api/incidents/{incident_id}/resolve` | account | Ends the SOS with the safety PIN. A duress PIN looks the same but escalates. |
| PUT | `/api/incidents/{incident_id}/responders/status` | account | A responder's status: on the way, helping, can't help. |
| GET | `/api/incidents/{incident_id}/snapshot` | account | Full incident state for the owner: responders with names, distance and status; location; circle delivery. |
| GET | `/api/incidents/{incident_id}/trusted-contacts` | account | Delivery status of the SOS for each trusted contact. |
| POST | `/api/incidents/{incident_id}/trusted-contacts/alert` | account | Alerts trusted contacts: pushes to linked ones, returns the others for an SMS from the phone. |
| POST | `/api/incidents/{incident_id}/trusted-contacts/sms-prepared` | account | The owner's messaging app was opened with these contacts and came back. Not proof of sending. |
| POST | `/api/incidents/{incident_id}/ws-ticket` | account | One-time ticket for the incident WebSocket. |
| GET | `/api/responders/invitations` | account | Pending SOS requests for this volunteer. |
| POST | `/api/responders/invitations/{invitation_id}/response` | account | Accept or decline an SOS request. |
| PUT | `/api/responders/location` | account | A volunteer's current location, used to find nearby responders. |
| POST | `/api/security/safety-pin/change` | account | Changes the safety and duress PINs (needs the current PIN). |
| POST | `/api/security/safety-pin/recover` | account | Resets the PINs after a fresh sign-in. |
| POST | `/api/security/safety-pin/setup` | account | Sets the safety PIN (and optional duress PIN) for the first time. |
| GET | `/api/security/safety-pin/status` | account | Whether PINs are set, and any lockout. |
| GET | `/api/trusted-contacts` | account | The account's trusted contacts. |
| POST | `/api/trusted-contacts` | account | Adds a trusted contact. |
| DELETE | `/api/trusted-contacts/{contact_id}` | account | Removes a trusted contact (and its circle link). |
| PUT | `/api/trusted-contacts/{contact_id}` | account | Edits a trusted contact, including whether they get SOS alerts. |
| POST | `/api/trusted-contacts/{contact_id}/primary` | account | Makes a contact the primary one. |
| GET | `/api/users/me` | account | The signed-in account: profile, responder settings, volunteer status. |
| PATCH | `/api/users/me` | account | Updates part of the profile. |
| PUT | `/api/users/me` | account | Updates the profile and responder availability. |
| WS | `/api/ws/incidents/{incident_id}` | ticket | Live incident updates (same as /ws/incidents/...; kept for older app builds). |
| WS | `/ws/incidents/{incident_id}` | ticket | Live incident updates for the owner and authorized responders. Needs a ws-ticket. |

### `app.volunteer`

| Method | Path | Access | What it does |
|---|---|---|---|
| POST | `/api/volunteer/consent` | account | Records consent to document verification and the volunteer declaration. |
| POST | `/api/volunteer/intent` | account | "Yes, I'd like to help" or "Not now" on the volunteer screen. |
| GET | `/api/volunteer/status` | account | Volunteer application status and which documents are uploaded. |
| POST | `/api/volunteer/submit` | account | Submits the application for review (all five documents and consent required). |
| POST | `/api/volunteer/upload-document` | account | Per Security Policy 2.3 identity documents go only to private storage with short-lived signed URLs. Returns a presigned upload (see app/files.py); 503 while that storage is not configured. |
