"""Real-time responder coordination assistance status."""
from alembic import op

revision = "0004"
down_revision = "0003"


def upgrade():
    op.execute("""ALTER TABLE incident_responders
        ADD COLUMN IF NOT EXISTS assistance_status text NOT NULL DEFAULT 'ACCEPTED'
        CHECK(assistance_status IN ('ACCEPTED','ON_THE_WAY','HELPING','UNABLE_TO_HELP','LEFT_INCIDENT'))""")
    op.execute("ALTER TABLE incident_responders ADD COLUMN IF NOT EXISTS assistance_updated_at timestamptz DEFAULT now()")


def downgrade():
    op.execute("ALTER TABLE incident_responders DROP COLUMN IF EXISTS assistance_updated_at")
    op.execute("ALTER TABLE incident_responders DROP COLUMN IF EXISTS assistance_status")
