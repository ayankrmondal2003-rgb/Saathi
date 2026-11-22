"""Separate consent-based trips/check-ins; no changes to emergency incident tables."""
from alembic import op

revision = "0008"
down_revision = "0007"


def upgrade():
    statements = """
        CREATE TABLE circle_pairing_codes (
            token_hash text PRIMARY KEY,
            recipient_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_at timestamptz NOT NULL DEFAULT now(),
            expires_at timestamptz NOT NULL,
            consumed_at timestamptz,
            CHECK (owner_id <> recipient_id)
        );
        CREATE TABLE circle_links (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            contact_id uuid NOT NULL REFERENCES trusted_contacts(id) ON DELETE CASCADE,
            recipient_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_at timestamptz NOT NULL DEFAULT now(),
            revoked_at timestamptz,
            CHECK (owner_id <> recipient_id)
        );
        CREATE UNIQUE INDEX uq_circle_active_contact ON circle_links(contact_id) WHERE revoked_at IS NULL;
        CREATE INDEX ix_circle_recipient ON circle_links(recipient_id);
        CREATE TABLE circle_sessions (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind text NOT NULL CHECK (kind IN ('TRIP', 'CHECK_IN')),
            idempotency_key uuid NOT NULL,
            request_hash text NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now(),
            expires_at timestamptz NOT NULL,
            stopped_at timestamptz,
            latest_fix jsonb,
            fix_received_at timestamptz,
            UNIQUE(owner_id, idempotency_key)
        );
        CREATE INDEX ix_circle_session_expiry ON circle_sessions(expires_at);
        CREATE TABLE circle_session_recipients (
            session_id uuid NOT NULL REFERENCES circle_sessions(id) ON DELETE CASCADE,
            link_id uuid NOT NULL REFERENCES circle_links(id) ON DELETE CASCADE,
            notification_status text NOT NULL DEFAULT 'AVAILABLE_IN_APP'
                CHECK (notification_status IN ('AVAILABLE_IN_APP', 'OPENED')),
            opened_at timestamptz,
            PRIMARY KEY(session_id, link_id)
        );
    """
    for statement in statements.split(";"):
        if statement.strip():
            op.execute(statement)


def downgrade():
    # Explicit downgrade is destructive; never called automatically during deployment.
    op.execute("DROP TABLE circle_session_recipients")
    op.execute("DROP TABLE circle_sessions")
    op.execute("DROP TABLE circle_links")
    op.execute("DROP TABLE circle_pairing_codes")
