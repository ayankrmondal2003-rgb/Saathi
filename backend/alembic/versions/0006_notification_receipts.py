"""Track Firebase acceptance independently across a recipient's test devices."""
from alembic import op

revision = "0006"
down_revision = "0005"


def upgrade():
    op.execute("ALTER TABLE notification_outbox ADD COLUMN accepted_token_hashes text[] NOT NULL DEFAULT '{}'")


def downgrade():
    op.drop_column("notification_outbox", "accepted_token_hashes")
