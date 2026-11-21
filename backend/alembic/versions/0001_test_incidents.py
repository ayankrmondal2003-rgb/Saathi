"""Initial test incident schema. An administrator provisions PostGIS first."""
from alembic import op
from app.database.v1 import metadata, users, devices, locations, incidents, invitations, responders, events

revision = "0001"
down_revision = None

INITIAL_TABLES = [users, devices, locations, incidents, invitations, responders, events]


def upgrade():
    metadata.create_all(op.get_bind(), tables=INITIAL_TABLES, checkfirst=False)


def downgrade():
    metadata.drop_all(op.get_bind(), tables=INITIAL_TABLES, checkfirst=False)
