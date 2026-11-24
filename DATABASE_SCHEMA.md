# Maitri database schema

PostgreSQL 16 with PostGIS (Neon in production). The schema is built only by Alembic migrations in
`backend/alembic/versions` (0001-0017, idempotent raw SQL), which run on every deploy before the API starts.

## How the tables fit together

- **Accounts:** `users` (one per Firebase account; profile, responder availability, encrypted medical notes,
  profile photo) and `devices` (push tokens per phone).
- **SOS:** `incidents` (one per SOS; owner, status and only the latest location, no trail) with
  `incident_invitations` (nearby volunteers asked to help), `incident_responders` (who accepted and their status)
  and `incident_events` (the audit trail, including responder reports).
- **Ending an SOS:** `safety_credentials` (hashed safety and duress PINs), `incident_resolution_attempts`
  (PIN tries, for lockout) and `incident_resolution_requests`.
- **Volunteers:** `responder_locations` (where available volunteers are, for the nearby search),
  `volunteer_applications` and `volunteer_documents` (documents and the review decision), `admin_audit_log`
  (what reviewers opened and decided).
- **Circle:** `trusted_contacts` (the owner's contacts), `circle_invites` / `circle_invite_attempts` /
  `circle_links` (contacts linked to a Maitri account by code), `incident_contact_alerts` (how each contact was
  alerted for an SOS), `trusted_contact_grants`, and `circle_sessions` / `circle_session_recipients` /
  `circle_pairing_codes` (shared trips and check-ins).
- **Messages:** `inbox_items` (circle alerts and reminders; also the push queue) and `notification_outbox`
  (volunteer SOS requests waiting to be pushed).
- **Files:** `stored_files` (metadata for files in private R2 storage: volunteer documents, SOS photos and voice
  notes, profile photos).

## Retention

Closed SOS locations are erased after 168 hours, SOS photos and voice notes after 30 days, and expired invitations,
grants and audit rows on a schedule (see `backend/app/maintenance.py` and `app/privacy.py`). Deleting an account
removes its rows, its files and its Firebase sign-in.

## Tables

<!-- generated below by backend/tests/test_schema_doc.py -->

### `admin_audit_log`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `admin_uid` | text | no |  |
| `action` | text | no |  |
| `application_id` | uuid | yes |  |
| `file_id` | uuid | yes |  |
| `decision` | text | yes |  |
| `note` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

Indexes: `admin_audit_log_pkey` (unique); `ix_admin_audit_created`

### `circle_invite_attempts`

| Column | Type | Null | Default |
|---|---|---|---|
| `firebase_uid` | text | no |  |
| `created_at` | timestamp with time zone | no | `now()` |

Indexes: `ix_circle_invite_attempts`

### `circle_invites`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `owner_id` | uuid | no |  |
| `contact_id` | uuid | no |  |
| `code_hash` | text | yes |  |
| `target_user_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `expires_at` | timestamp with time zone | no |  |
| `accepted_at` | timestamp with time zone | yes |  |
| `declined_at` | timestamp with time zone | yes |  |

Indexes: `circle_invites_code_hash_key` (unique); `circle_invites_pkey` (unique); `ix_circle_invites_target`

### `circle_links`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `owner_id` | uuid | no |  |
| `contact_id` | uuid | no |  |
| `recipient_id` | uuid | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `revoked_at` | timestamp with time zone | yes |  |

Indexes: `circle_links_pkey` (unique); `ix_circle_recipient`; `uq_circle_active_contact` (unique)

### `circle_pairing_codes`

| Column | Type | Null | Default |
|---|---|---|---|
| `token_hash` | text | no |  |
| `recipient_id` | uuid | no |  |
| `owner_id` | uuid | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `expires_at` | timestamp with time zone | no |  |
| `consumed_at` | timestamp with time zone | yes |  |

Indexes: `circle_pairing_codes_pkey` (unique)

### `circle_session_recipients`

| Column | Type | Null | Default |
|---|---|---|---|
| `session_id` | uuid | no |  |
| `link_id` | uuid | no |  |
| `notification_status` | text | no | `'AVAILABLE_IN_APP'::text` |
| `opened_at` | timestamp with time zone | yes |  |

Indexes: `circle_session_recipients_pkey` (unique)

### `circle_sessions`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `owner_id` | uuid | no |  |
| `kind` | text | no |  |
| `idempotency_key` | uuid | no |  |
| `request_hash` | text | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `expires_at` | timestamp with time zone | no |  |
| `stopped_at` | timestamp with time zone | yes |  |
| `latest_fix` | jsonb | yes |  |
| `fix_received_at` | timestamp with time zone | yes |  |
| `due_at` | timestamp with time zone | yes |  |
| `reminded_at` | timestamp with time zone | yes |  |
| `missed_at` | timestamp with time zone | yes |  |

Indexes: `circle_sessions_owner_id_idempotency_key_key` (unique); `circle_sessions_pkey` (unique); `ix_circle_checkins_due`; `ix_circle_session_expiry`

### `devices`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `user_id` | uuid | no |  |
| `device_identifier` | character varying | no |  |
| `platform` | character varying | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `last_seen_at` | timestamp with time zone | no | `now()` |
| `fcm_token` | text | yes |  |
| `is_active` | boolean | no | `true` |
| `disabled_at` | timestamp with time zone | yes |  |

Indexes: `devices_pkey` (unique); `devices_user_id_device_identifier_key` (unique); `ix_devices_user_active`; `uq_devices_active_token` (unique)

### `inbox_items`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `user_id` | uuid | no |  |
| `kind` | text | no |  |
| `title` | text | no |  |
| `body` | text | no |  |
| `data` | jsonb | no | `'{}'::jsonb` |
| `incident_id` | uuid | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `read_at` | timestamp with time zone | yes |  |
| `push_status` | text | no | `'PENDING'::text` |
| `push_attempts` | integer | no | `0` |
| `next_push_at` | timestamp with time zone | yes |  |
| `pushed_at` | timestamp with time zone | yes |  |
| `delivered_at` | timestamp with time zone | yes |  |

Indexes: `inbox_items_pkey` (unique); `ix_inbox_push_pending`; `ix_inbox_user_created`

### `incident_contact_alerts`

| Column | Type | Null | Default |
|---|---|---|---|
| `incident_id` | uuid | no |  |
| `contact_id` | uuid | no |  |
| `channel` | text | no |  |
| `inbox_item_id` | uuid | yes |  |
| `sms_prepared_at` | timestamp with time zone | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |

Indexes: `incident_contact_alerts_pkey` (unique)

### `incident_events`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `incident_id` | uuid | no |  |
| `event_type` | character varying | no |  |
| `actor_user_id` | uuid | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `metadata` | jsonb | no | `'{}'::jsonb` |

Indexes: `incident_events_pkey` (unique); `ix_incident_events_incident_id`

### `incident_invitations`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `incident_id` | uuid | no |  |
| `recipient_user_id` | uuid | no |  |
| `status` | character varying | no | `'PENDING'::character varying` |
| `invited_at` | timestamp with time zone | no | `now()` |
| `expires_at` | timestamp with time zone | no |  |
| `responded_at` | timestamp with time zone | yes |  |

Indexes: `incident_invitations_incident_id_recipient_user_id_key` (unique); `incident_invitations_pkey` (unique); `ix_incident_invitations_recipient_user_id`

### `incident_resolution_attempts`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `incident_id` | uuid | no |  |
| `actor_user_id` | uuid | no |  |
| `attempt_result` | text | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `request_id` | text | yes |  |
| `metadata` | jsonb | no | `'{}'::jsonb` |

Indexes: `incident_resolution_attempts_pkey` (unique); `ix_resolution_attempts_actor`; `ix_resolution_attempts_incident`

### `incident_resolution_requests`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `incident_id` | uuid | no |  |
| `requester_user_id` | uuid | no |  |
| `status` | text | no | `'PENDING'::text` |
| `requested_at` | timestamp with time zone | no | `now()` |
| `verified_at` | timestamp with time zone | yes |  |
| `completed_at` | timestamp with time zone | yes |  |
| `reason` | text | yes |  |

Indexes: `incident_resolution_requests_pkey` (unique); `ix_resolution_requests_incident`

### `incident_responders`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `incident_id` | uuid | no |  |
| `responder_user_id` | uuid | no |  |
| `authorization_status` | character varying | no | `'AUTHORIZED'::character varying` |
| `assistance_status` | character varying | no | `'ACCEPTED'::character varying` |
| `accepted_at` | timestamp with time zone | no | `now()` |
| `access_expires_at` | timestamp with time zone | no |  |
| `revoked_at` | timestamp with time zone | yes |  |
| `assistance_updated_at` | timestamp with time zone | yes | `now()` |

Indexes: `incident_responders_incident_id_responder_user_id_key` (unique); `incident_responders_pkey` (unique)

### `incidents`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `victim_user_id` | uuid | no |  |
| `idempotency_key` | uuid | no |  |
| `request_hash` | character varying | no |  |
| `is_test` | boolean | no | `true` |
| `status` | character varying | no | `'ACTIVE'::character varying` |
| `latest_location` | geography | yes |  |
| `location_accuracy_meters` | double precision | yes |  |
| `location_is_mock` | boolean | yes |  |
| `location_recorded_at` | timestamp with time zone | yes |  |
| `location_received_at` | timestamp with time zone | yes |  |
| `radius_meters` | integer | no | `1000` |
| `started_at` | timestamp with time zone | no | `now()` |
| `ended_at` | timestamp with time zone | yes |  |
| `evaluated_at` | timestamp with time zone | yes |  |
| `evaluated_center` | geography | yes |  |

Indexes: `incidents_pkey` (unique); `incidents_victim_user_id_idempotency_key_key` (unique); `ix_due_incident`; `ix_incidents_owner_started`; `ix_open_center` (GiST); `uq_open_incident_owner` (unique)

### `notification_outbox`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `invitation_id` | uuid | yes |  |
| `incident_id` | uuid | no |  |
| `recipient_user_id` | uuid | no |  |
| `event_type` | text | no | `'RESPONDER_INVITATION_CREATED'::text` |
| `delivery_status` | text | no | `'PENDING'::text` |
| `created_at` | timestamp with time zone | no | `now()` |
| `attempt_count` | integer | no | `0` |
| `fcm_message_id` | text | yes |  |
| `last_attempt_at` | timestamp with time zone | yes |  |
| `next_attempt_at` | timestamp with time zone | yes |  |
| `failure_reason` | text | yes |  |
| `accepted_token_hashes` | ARRAY | no | `'{}'::text[]` |
| `contact_id` | uuid | yes |  |

Indexes: `ix_outbox_due`; `ix_outbox_pending`; `notification_outbox_invitation_id_key` (unique); `notification_outbox_pkey` (unique)

### `responder_locations`

| Column | Type | Null | Default |
|---|---|---|---|
| `user_id` | uuid | no |  |
| `location` | geography | no |  |
| `accuracy_meters` | double precision | no |  |
| `recorded_at` | timestamp with time zone | no |  |
| `received_at` | timestamp with time zone | no | `now()` |
| `is_mock` | boolean | no |  |
| `searched_at` | timestamp with time zone | yes |  |
| `searched_center` | geography | yes |  |

Indexes: `ix_responder_location_gist` (GiST); `responder_locations_pkey` (unique)

### `safety_credentials`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `user_id` | uuid | no |  |
| `credential_type` | text | no |  |
| `pin_hash` | text | no |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `last_changed_at` | timestamp with time zone | no | `now()` |
| `failed_attempt_count` | integer | no | `0` |
| `locked_until` | timestamp with time zone | yes |  |

Indexes: `ix_safety_credentials_user`; `safety_credentials_pkey` (unique); `uq_user_credential_type` (unique)

### `stored_files`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `owner_user_id` | uuid | no |  |
| `kind` | text | no |  |
| `doc_type` | text | yes |  |
| `incident_id` | uuid | yes |  |
| `object_key` | text | no |  |
| `mime_type` | text | no |  |
| `size_bytes` | bigint | no |  |
| `status` | text | no | `'PENDING'::text` |
| `created_at` | timestamp with time zone | no | `now()` |
| `uploaded_at` | timestamp with time zone | yes |  |

Indexes: `ix_stored_files_owner`; `stored_files_object_key_key` (unique); `stored_files_pkey` (unique)

### `trusted_contact_grants`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `incident_id` | uuid | no |  |
| `contact_id` | uuid | no |  |
| `recipient_user_id` | uuid | yes |  |
| `authorization_status` | text | no | `'AUTHORIZED'::text` |
| `access_expires_at` | timestamp with time zone | no |  |
| `granted_at` | timestamp with time zone | no | `now()` |
| `revoked_at` | timestamp with time zone | yes |  |

Indexes: `ix_trusted_contact_grants_incident`; `trusted_contact_grants_pkey` (unique); `uq_incident_contact_grant` (unique)

### `trusted_contacts`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `owner_user_id` | uuid | no |  |
| `display_name` | text | no |  |
| `phone_number` | text | no |  |
| `normalized_phone_number` | text | no |  |
| `label` | text | no | `'Emergency Contact'::text` |
| `is_primary` | boolean | no | `false` |
| `is_verified` | boolean | no | `false` |
| `source` | text | no | `'MANUAL'::text` |
| `notify_on_sos` | boolean | no | `true` |
| `include_location_link` | boolean | no | `true` |
| `notify_on_closure` | boolean | no | `true` |
| `allow_live_tracking` | boolean | no | `true` |
| `preferred_channel` | text | no | `'SMS'::text` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

Indexes: `ix_trusted_contacts_owner`; `trusted_contacts_pkey` (unique); `uq_owner_normalized_phone` (unique)

### `users`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `firebase_uid` | character varying | no |  |
| `display_name` | character varying | no |  |
| `email` | character varying | no |  |
| `email_verified` | boolean | no |  |
| `phone_verified` | boolean | no | `false` |
| `responder_opt_in` | boolean | no | `false` |
| `responder_available` | boolean | no | `false` |
| `account_status` | character varying | no | `'ACTIVE'::character varying` |
| `phone_number` | character varying | yes |  |
| `city` | character varying | yes |  |
| `share_phone_in_sos` | boolean | no | `false` |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |
| `photo_file_id` | uuid | yes |  |
| `medical_notes` | bytea | yes |  |

Indexes: `users_firebase_uid_key` (unique); `users_pkey` (unique)

### `volunteer_applications`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `user_id` | uuid | no |  |
| `status` | text | no | `'NOT_STARTED'::text` |
| `intent` | text | no | `'HELP'::text` |
| `has_govt_id` | boolean | no | `false` |
| `has_address_proof` | boolean | no | `false` |
| `has_police_cert` | boolean | no | `false` |
| `has_photo` | boolean | no | `false` |
| `has_declaration` | boolean | no | `false` |
| `consent_declared` | boolean | no | `false` |
| `submitted_at` | timestamp with time zone | yes |  |
| `reviewed_at` | timestamp with time zone | yes |  |
| `reviewer_notes` | text | yes |  |
| `created_at` | timestamp with time zone | no | `now()` |
| `updated_at` | timestamp with time zone | no | `now()` |

Indexes: `ix_volunteer_app_status`; `ix_volunteer_app_user`; `volunteer_applications_pkey` (unique); `volunteer_applications_user_id_key` (unique)

### `volunteer_documents`

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` |
| `application_id` | uuid | no |  |
| `user_id` | uuid | no |  |
| `doc_type` | text | no |  |
| `file_name` | text | no |  |
| `file_size_bytes` | integer | no |  |
| `mime_type` | text | no |  |
| `storage_key` | text | yes |  |
| `uploaded_at` | timestamp with time zone | no | `now()` |

Indexes: `volunteer_documents_application_id_doc_type_key` (unique); `volunteer_documents_pkey` (unique)
