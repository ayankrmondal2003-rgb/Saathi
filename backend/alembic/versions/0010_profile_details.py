"""Optional profile contact details edited from Maitri screen 14.

Idempotent like 0003: revision 0001 creates tables from the current metadata, so on a fresh
database these columns already exist when this revision runs.
"""
from alembic import op

revision = "0010"
down_revision = "0009"


def upgrade():
    op.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_number varchar(32)")
    op.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS city varchar(128)")
    op.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_phone_number")
    op.execute(r"""ALTER TABLE users ADD CONSTRAINT ck_users_phone_number
        CHECK (phone_number IS NULL OR phone_number ~ '^\+?[0-9][0-9 ()-]{5,30}$')""")
    op.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_city")
    op.execute("""ALTER TABLE users ADD CONSTRAINT ck_users_city
        CHECK (city IS NULL OR length(btrim(city)) BETWEEN 1 AND 128)""")


def downgrade():
    op.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_city")
    op.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_phone_number")
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS city")
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS phone_number")
