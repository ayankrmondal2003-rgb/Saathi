"""Medical notes stored encrypted on the server (AES-256-GCM, key MEDICAL_NOTES_KEY; see app/privacy.py).

Idempotent like 0009-0016.
"""
from alembic import op

revision = "0017"
down_revision = "0016"


def upgrade():
    op.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS medical_notes bytea")


def downgrade():
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS medical_notes")
