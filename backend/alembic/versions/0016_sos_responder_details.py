"""Consent to share the owner's phone number with accepted responders during an SOS (screen 07 "Call").

Idempotent like 0009-0015.
"""
from alembic import op

revision = "0016"
down_revision = "0015"


def upgrade():
    op.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS share_phone_in_sos boolean NOT NULL DEFAULT false")


def downgrade():
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS share_phone_in_sos")
