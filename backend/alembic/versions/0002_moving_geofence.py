"""Moving search checkpoints and transactional invitation outbox."""
from alembic import op

revision = "0002"
down_revision = "0001"


def upgrade():
    op.execute("ALTER TABLE responder_locations ADD COLUMN searched_at timestamptz, ADD COLUMN searched_center geography(Point,4326)")
    op.execute("ALTER TABLE incidents ADD COLUMN evaluated_at timestamptz, ADD COLUMN evaluated_center geography(Point,4326)")
    op.execute("ALTER TABLE incidents DROP CONSTRAINT incidents_radius_meters_check, ADD CONSTRAINT incidents_radius_meters_check CHECK(radius_meters BETWEEN 1 AND 1000)")
    op.execute("ALTER TABLE users DROP CONSTRAINT users_account_status_check, ADD CONSTRAINT users_account_status_check CHECK(account_status IN ('ACTIVE','BLOCKED','SUSPENDED'))")
    op.execute("CREATE INDEX ix_open_center ON incidents USING gist(latest_location) WHERE status='ACTIVE'")
    op.execute("CREATE INDEX ix_due_incident ON incidents(evaluated_at NULLS FIRST) WHERE status='ACTIVE'")
    op.execute("""CREATE TABLE notification_outbox (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        invitation_id uuid NOT NULL UNIQUE REFERENCES incident_invitations(id) ON DELETE CASCADE,
        incident_id uuid NOT NULL REFERENCES incidents(id),
        recipient_user_id uuid NOT NULL REFERENCES users(id),
        event_type text NOT NULL DEFAULT 'RESPONDER_INVITATION_CREATED' CHECK(event_type='RESPONDER_INVITATION_CREATED'),
        delivery_status text NOT NULL DEFAULT 'PENDING' CHECK(delivery_status IN ('PENDING','CANCELLED')),
        created_at timestamptz NOT NULL DEFAULT now(),
        attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count >= 0))""")
    op.execute("CREATE INDEX ix_outbox_pending ON notification_outbox(created_at) WHERE delivery_status='PENDING'")


def downgrade():
    op.execute("ALTER TABLE responder_locations DROP COLUMN searched_at, DROP COLUMN searched_center")
    op.execute("DROP TABLE notification_outbox")
    op.execute("DROP INDEX ix_due_incident")
    op.execute("DROP INDEX ix_open_center")
    op.execute("ALTER TABLE incidents DROP COLUMN evaluated_center, DROP COLUMN evaluated_at")
    op.execute("ALTER TABLE incidents DROP CONSTRAINT incidents_radius_meters_check, ADD CONSTRAINT incidents_radius_meters_check CHECK(radius_meters=1000)")
    op.execute("ALTER TABLE users DROP CONSTRAINT users_account_status_check, ADD CONSTRAINT users_account_status_check CHECK(account_status IN ('ACTIVE','SUSPENDED'))")
