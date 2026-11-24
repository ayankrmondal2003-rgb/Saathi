"""Volunteer review by admins (audit log, decision inbox item) and server-stored profile photos.

Idempotent like 0009-0014.
"""
from alembic import op

revision = "0015"
down_revision = "0014"

KINDS = ("'CIRCLE_SOS', 'CIRCLE_SOS_ENDED', 'CHECKIN_REMINDER', 'CHECKIN_MISSED', 'TRIP_STARTED', "
         "'CHECKIN_STARTED', 'CIRCLE_REQUEST', 'CIRCLE_LINKED'")


def upgrade():
    for statement in f"""
        CREATE TABLE IF NOT EXISTS admin_audit_log (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            admin_uid text NOT NULL,
            action text NOT NULL CHECK (action IN ('APPLICATION_OPENED', 'DOCUMENT_VIEWED', 'APPLICATION_DECIDED')),
            application_id uuid REFERENCES volunteer_applications(id) ON DELETE SET NULL,
            file_id uuid,
            decision text,
            note text,
            created_at timestamptz NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS ix_admin_audit_created ON admin_audit_log(created_at DESC);
        ALTER TABLE users ADD COLUMN IF NOT EXISTS photo_file_id uuid REFERENCES stored_files(id) ON DELETE SET NULL;
        ALTER TABLE inbox_items DROP CONSTRAINT IF EXISTS inbox_items_kind_check;
        ALTER TABLE inbox_items ADD CONSTRAINT inbox_items_kind_check CHECK (kind IN ({KINDS}, 'VOLUNTEER_DECISION'))
    """.split(";"):
        if statement.strip():
            op.execute(statement)


def downgrade():
    for statement in f"""
        ALTER TABLE inbox_items DROP CONSTRAINT IF EXISTS inbox_items_kind_check;
        DELETE FROM inbox_items WHERE kind = 'VOLUNTEER_DECISION';
        ALTER TABLE inbox_items ADD CONSTRAINT inbox_items_kind_check CHECK (kind IN ({KINDS}));
        ALTER TABLE users DROP COLUMN IF EXISTS photo_file_id;
        DROP TABLE IF EXISTS admin_audit_log
    """.split(";"):
        if statement.strip():
            op.execute(statement)
