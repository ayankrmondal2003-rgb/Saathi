"""Safety credentials, incident resolution requests, attempts, and escalation state."""
from alembic import op

revision = "0005"
down_revision = "0004"


def upgrade():
    # 1. Update incidents status check to support ESCALATION_REQUIRED
    op.execute("ALTER TABLE incidents DROP CONSTRAINT IF EXISTS incidents_status_check")
    op.execute("""ALTER TABLE incidents ADD CONSTRAINT incidents_status_check
        CHECK(status IN ('ACTIVE','RESOLUTION_REQUESTED','CLOSED','ESCALATION_REQUIRED'))""")

    # 2. Safety credentials table for hashed PINs and lockout tracking
    op.execute("""CREATE TABLE IF NOT EXISTS safety_credentials (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        credential_type text NOT NULL CHECK (credential_type IN ('PRIMARY_PIN', 'DURESS_PIN')),
        pin_hash text NOT NULL,
        created_at timestamptz NOT NULL DEFAULT now(),
        updated_at timestamptz NOT NULL DEFAULT now(),
        last_changed_at timestamptz NOT NULL DEFAULT now(),
        failed_attempt_count integer NOT NULL DEFAULT 0 CHECK (failed_attempt_count >= 0),
        locked_until timestamptz,
        CONSTRAINT uq_user_credential_type UNIQUE (user_id, credential_type)
    )""")
    op.execute("CREATE INDEX IF NOT EXISTS ix_safety_credentials_user ON safety_credentials(user_id)")

    # 3. Incident resolution requests table
    op.execute("""CREATE TABLE IF NOT EXISTS incident_resolution_requests (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
        requester_user_id uuid NOT NULL REFERENCES users(id),
        status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'DURESS_FLAGGED')),
        requested_at timestamptz NOT NULL DEFAULT now(),
        verified_at timestamptz,
        completed_at timestamptz,
        reason text
    )""")
    op.execute("CREATE INDEX IF NOT EXISTS ix_resolution_requests_incident ON incident_resolution_requests(incident_id)")

    # 4. Incident resolution attempts table for auditing verification attempts
    op.execute("""CREATE TABLE IF NOT EXISTS incident_resolution_attempts (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
        actor_user_id uuid NOT NULL REFERENCES users(id),
        attempt_result text NOT NULL CHECK (attempt_result IN ('SUCCESS', 'INCORRECT_PIN', 'RATE_LIMITED', 'DURESS_SIMULATION', 'UNAUTHORIZED', 'USER_NOT_ENROLLED')),
        created_at timestamptz NOT NULL DEFAULT now(),
        request_id text,
        metadata jsonb NOT NULL DEFAULT '{}'::jsonb
    )""")
    op.execute("CREATE INDEX IF NOT EXISTS ix_resolution_attempts_incident ON incident_resolution_attempts(incident_id)")
    op.execute("CREATE INDEX IF NOT EXISTS ix_resolution_attempts_actor ON incident_resolution_attempts(actor_user_id)")


def downgrade():
    op.execute("DROP TABLE IF EXISTS incident_resolution_attempts")
    op.execute("DROP TABLE IF EXISTS incident_resolution_requests")
    op.execute("DROP TABLE IF EXISTS safety_credentials")
    op.execute("ALTER TABLE incidents DROP CONSTRAINT IF EXISTS incidents_status_check")
    op.execute("ALTER TABLE incidents ADD CONSTRAINT incidents_status_check CHECK(status IN ('ACTIVE','RESOLUTION_REQUESTED','CLOSED'))")
