"""FCM device registration and delivery tracking outbox."""
from alembic import op

revision = "0003"
down_revision = "0002"


def upgrade():
    op.execute("ALTER TABLE devices ADD COLUMN IF NOT EXISTS fcm_token text")
    op.execute("ALTER TABLE devices ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true")
    op.execute("ALTER TABLE devices ADD COLUMN IF NOT EXISTS disabled_at timestamptz")
    op.execute("CREATE INDEX IF NOT EXISTS ix_devices_user_active ON devices(user_id) WHERE is_active")
    op.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_devices_active_token ON devices(fcm_token) WHERE is_active")
    op.execute("ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_delivery_status_check")
    op.execute("""ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_delivery_status_check
        CHECK(delivery_status IN ('PENDING','SENDING','FCM_ACCEPTED','RETRY_SCHEDULED','PERMANENT_FAILURE','CANCELLED'))""")
    op.execute("ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS fcm_message_id text")
    op.execute("ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS last_attempt_at timestamptz")
    op.execute("ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz")
    op.execute("ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS failure_reason text")
    op.execute("CREATE INDEX IF NOT EXISTS ix_outbox_due ON notification_outbox(created_at) WHERE delivery_status IN ('PENDING','RETRY_SCHEDULED')")


def downgrade():
    op.execute("DROP INDEX IF EXISTS ix_outbox_due")
    op.execute("ALTER TABLE notification_outbox DROP COLUMN IF EXISTS failure_reason")
    op.execute("ALTER TABLE notification_outbox DROP COLUMN IF EXISTS next_attempt_at")
    op.execute("ALTER TABLE notification_outbox DROP COLUMN IF EXISTS last_attempt_at")
    op.execute("ALTER TABLE notification_outbox DROP COLUMN IF EXISTS fcm_message_id")
    op.execute("ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_delivery_status_check")
    op.execute("ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_delivery_status_check CHECK(delivery_status IN ('PENDING','CANCELLED'))")
    op.execute("DROP INDEX IF EXISTS uq_devices_active_token")
    op.execute("DROP INDEX IF EXISTS ix_devices_user_active")
    op.execute("ALTER TABLE devices DROP COLUMN IF EXISTS disabled_at")
    op.execute("ALTER TABLE devices DROP COLUMN IF EXISTS is_active")
    op.execute("ALTER TABLE devices DROP COLUMN IF EXISTS fcm_token")
