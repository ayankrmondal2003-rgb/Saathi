"""Metadata for files kept in private object storage (R2). The bytes never touch the API server.

Idempotent like 0009-0011.
"""
from alembic import op

revision = "0012"
down_revision = "0011"


def upgrade():
    op.execute("""
        CREATE TABLE IF NOT EXISTS stored_files (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind text NOT NULL CHECK (kind IN ('VOLUNTEER_DOC', 'SOS_PHOTO', 'SOS_VOICE', 'PROFILE_PHOTO')),
            doc_type text CHECK (doc_type IN ('GOVT_ID', 'ADDRESS_PROOF', 'POLICE_CERT', 'PHOTO', 'DECLARATION')),
            incident_id uuid REFERENCES incidents(id) ON DELETE CASCADE,
            object_key text NOT NULL UNIQUE,
            mime_type text NOT NULL,
            size_bytes bigint NOT NULL CHECK (size_bytes > 0),
            status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'UPLOADED')),
            created_at timestamptz NOT NULL DEFAULT now(),
            uploaded_at timestamptz,
            CHECK ((kind = 'VOLUNTEER_DOC') = (doc_type IS NOT NULL)),
            CHECK ((kind IN ('SOS_PHOTO', 'SOS_VOICE')) = (incident_id IS NOT NULL))
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS ix_stored_files_owner ON stored_files(owner_user_id, kind)")


def downgrade():
    op.execute("DROP TABLE IF EXISTS stored_files")
