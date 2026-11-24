"""Circle alerts without an SMS provider, the in-app inbox, circle invites and missed check-ins.

- inbox_items: one row per thing a user should see (circle SOS, check-in reminder/missed, trip started,
  circle requests). Each row also carries its push state, so the inbox is the push queue.
- incident_contact_alerts: per incident and trusted contact, how the contact was reached
  (PUSH through a linked Maitri account, or SMS prepared in the owner's own messaging app).
- circle_invites: owner-created codes (and phone-verified requests) that link a trusted contact to the
  Maitri account of the person behind it. Accepting is always the recipient's action.
- circle_sessions: due_at / reminded_at / missed_at for check-ins.

Idempotent like 0009-0013.
"""
from alembic import op

revision = "0014"
down_revision = "0013"


def upgrade():
    for statement in """
        CREATE TABLE IF NOT EXISTS inbox_items (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind text NOT NULL CHECK (kind IN ('CIRCLE_SOS', 'CIRCLE_SOS_ENDED', 'CHECKIN_REMINDER', 'CHECKIN_MISSED',
                'TRIP_STARTED', 'CHECKIN_STARTED', 'CIRCLE_REQUEST', 'CIRCLE_LINKED')),
            title text NOT NULL,
            body text NOT NULL,
            data jsonb NOT NULL DEFAULT '{}'::jsonb,
            incident_id uuid REFERENCES incidents(id) ON DELETE CASCADE,
            created_at timestamptz NOT NULL DEFAULT now(),
            read_at timestamptz,
            push_status text NOT NULL DEFAULT 'PENDING'
                CHECK (push_status IN ('PENDING', 'SENT', 'DELIVERED', 'NO_DEVICE', 'FAILED', 'NOT_NEEDED')),
            push_attempts int NOT NULL DEFAULT 0,
            next_push_at timestamptz,
            pushed_at timestamptz,
            delivered_at timestamptz
        );
        CREATE INDEX IF NOT EXISTS ix_inbox_user_created ON inbox_items(user_id, created_at DESC);
        CREATE INDEX IF NOT EXISTS ix_inbox_push_pending ON inbox_items(next_push_at) WHERE push_status = 'PENDING';
        CREATE TABLE IF NOT EXISTS incident_contact_alerts (
            incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            contact_id uuid NOT NULL REFERENCES trusted_contacts(id) ON DELETE CASCADE,
            channel text NOT NULL CHECK (channel IN ('PUSH', 'SMS')),
            inbox_item_id uuid REFERENCES inbox_items(id) ON DELETE SET NULL,
            sms_prepared_at timestamptz,
            created_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (incident_id, contact_id)
        );
        CREATE TABLE IF NOT EXISTS circle_invites (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            contact_id uuid NOT NULL REFERENCES trusted_contacts(id) ON DELETE CASCADE,
            code_hash text UNIQUE,
            target_user_id uuid REFERENCES users(id) ON DELETE CASCADE,
            created_at timestamptz NOT NULL DEFAULT now(),
            expires_at timestamptz NOT NULL,
            accepted_at timestamptz,
            declined_at timestamptz,
            CHECK ((code_hash IS NULL) <> (target_user_id IS NULL))
        );
        CREATE INDEX IF NOT EXISTS ix_circle_invites_target ON circle_invites(target_user_id) WHERE target_user_id IS NOT NULL;
        -- Keyed by Firebase uid, written in its own transaction: a wrong code rolls back the request (and a
        -- first-time caller's user row with it). Rows are deleted after a day.
        CREATE TABLE IF NOT EXISTS circle_invite_attempts (
            firebase_uid text NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS ix_circle_invite_attempts ON circle_invite_attempts(firebase_uid, created_at);
        ALTER TABLE circle_sessions ADD COLUMN IF NOT EXISTS due_at timestamptz;
        ALTER TABLE circle_sessions ADD COLUMN IF NOT EXISTS reminded_at timestamptz;
        ALTER TABLE circle_sessions ADD COLUMN IF NOT EXISTS missed_at timestamptz;
        CREATE INDEX IF NOT EXISTS ix_circle_checkins_due ON circle_sessions(due_at)
            WHERE kind = 'CHECK_IN' AND stopped_at IS NULL AND missed_at IS NULL
    """.split(";"):
        if statement.strip():
            op.execute(statement)


def downgrade():
    for statement in """
        DROP INDEX IF EXISTS ix_circle_checkins_due;
        ALTER TABLE circle_sessions DROP COLUMN IF EXISTS missed_at;
        ALTER TABLE circle_sessions DROP COLUMN IF EXISTS reminded_at;
        ALTER TABLE circle_sessions DROP COLUMN IF EXISTS due_at;
        DROP TABLE IF EXISTS circle_invite_attempts;
        DROP TABLE IF EXISTS circle_invites;
        DROP TABLE IF EXISTS incident_contact_alerts;
        DROP TABLE IF EXISTS inbox_items
    """.split(";"):
        if statement.strip():
            op.execute(statement)
