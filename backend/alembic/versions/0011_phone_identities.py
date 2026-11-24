"""Accounts verified by phone OTP (Firebase Phone Auth) instead of email.

Idempotent like 0003/0010: revision 0001 creates tables from current metadata.
"""
from alembic import op

revision = "0011"
down_revision = "0010"


def upgrade():
    op.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_verified boolean NOT NULL DEFAULT false")


def downgrade():
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS phone_verified")
