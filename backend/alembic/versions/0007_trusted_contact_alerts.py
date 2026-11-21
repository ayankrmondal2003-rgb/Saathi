"""Trusted emergency contact alerts, notification preferences, and incident tracking grants."""
from alembic import op

revision = "0007"
down_revision = "0006"


def upgrade():
    # 1. Trusted contacts table
    op.execute("""CREATE TABLE IF NOT EXISTS trusted_contacts (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        display_name text NOT NULL,
        phone_number text NOT NULL,
        normalized_phone_number text NOT NULL,
        label text NOT NULL DEFAULT 'Emergency Contact',
        is_primary boolean NOT NULL DEFAULT false,
        is_verified boolean NOT NULL DEFAULT false,
        source text NOT NULL DEFAULT 'MANUAL',
        notify_on_sos boolean NOT NULL DEFAULT true,
        include_location_link boolean NOT NULL DEFAULT true,
        notify_on_closure boolean NOT NULL DEFAULT true,
        allow_live_tracking boolean NOT NULL DEFAULT true,
        preferred_channel text NOT NULL DEFAULT 'SMS',
        created_at timestamptz NOT NULL DEFAULT now(),
        updated_at timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT uq_owner_normalized_phone UNIQUE (owner_user_id, normalized_phone_number)
    )""")
    op.execute("CREATE INDEX IF NOT EXISTS ix_trusted_contacts_owner ON trusted_contacts(owner_user_id)")

    # 2. Trusted contact tracking grants table (isolated from community responder authorizations)
    op.execute("""CREATE TABLE IF NOT EXISTS trusted_contact_grants (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
        contact_id uuid NOT NULL REFERENCES trusted_contacts(id) ON DELETE CASCADE,
        recipient_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
        authorization_status text NOT NULL DEFAULT 'AUTHORIZED' CHECK(authorization_status IN ('AUTHORIZED', 'REVOKED')),
        access_expires_at timestamptz NOT NULL,
        granted_at timestamptz NOT NULL DEFAULT now(),
        revoked_at timestamptz,
        CONSTRAINT uq_incident_contact_grant UNIQUE (incident_id, contact_id)
    )""")
    op.execute("CREATE INDEX IF NOT EXISTS ix_trusted_contact_grants_incident ON trusted_contact_grants(incident_id)")

    # 3. Extend notification_outbox for trusted contact alert jobs
    op.execute("ALTER TABLE notification_outbox ALTER COLUMN invitation_id DROP NOT NULL")
    op.execute("ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS contact_id uuid REFERENCES trusted_contacts(id) ON DELETE CASCADE")
    op.execute("ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_event_type_check")
    op.execute("""ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check
        CHECK(event_type IN (
            'RESPONDER_INVITATION_CREATED',
            'TRUSTED_CONTACT_SOS_ALERT',
            'TRUSTED_CONTACT_LOCATION_UPDATE',
            'TRUSTED_CONTACT_INCIDENT_CLOSED'
        ))""")
    op.execute("ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_delivery_status_check")
    op.execute("""ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_delivery_status_check
        CHECK(delivery_status IN (
            'PENDING',
            'QUEUED',
            'SENDING',
            'FCM_ACCEPTED',
            'PROVIDER_ACCEPTED',
            'DELIVERED_WHEN_CONFIRMED',
            'RETRY_SCHEDULED',
            'FAILED',
            'UNKNOWN',
            'PERMANENT_FAILURE',
            'CANCELLED'
        ))""")


def downgrade():
    op.execute("ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_delivery_status_check")
    op.execute("""ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_delivery_status_check
        CHECK(delivery_status IN ('PENDING','SENDING','FCM_ACCEPTED','RETRY_SCHEDULED','PERMANENT_FAILURE','CANCELLED'))""")
    op.execute("ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_event_type_check")
    op.execute("ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check CHECK(event_type = 'RESPONDER_INVITATION_CREATED')")
    op.execute("ALTER TABLE notification_outbox DROP COLUMN IF EXISTS contact_id")
    op.execute("ALTER TABLE notification_outbox ALTER COLUMN invitation_id SET NOT NULL")
    op.execute("DROP TABLE IF EXISTS trusted_contact_grants")
    op.execute("DROP TABLE IF EXISTS trusted_contacts")
