"""Push-only inbox kind SOS_REQUEST_CLOSED: tells a volunteer their open request closed (SOS ended, or enough
volunteers accepted) so the phone stops alerting. Hidden from the inbox list and unread count (app/inbox.py).

Idempotent like 0009-0017.
"""
from alembic import op

revision = "0018"
down_revision = "0017"

KINDS = ("'CIRCLE_SOS', 'CIRCLE_SOS_ENDED', 'CHECKIN_REMINDER', 'CHECKIN_MISSED', 'TRIP_STARTED', "
         "'CHECKIN_STARTED', 'CIRCLE_REQUEST', 'CIRCLE_LINKED', 'VOLUNTEER_DECISION'")


def upgrade():
    op.execute("ALTER TABLE inbox_items DROP CONSTRAINT IF EXISTS inbox_items_kind_check")
    op.execute(f"ALTER TABLE inbox_items ADD CONSTRAINT inbox_items_kind_check CHECK (kind IN ({KINDS}, 'SOS_REQUEST_CLOSED'))")


def downgrade():
    op.execute("DELETE FROM inbox_items WHERE kind = 'SOS_REQUEST_CLOSED'")
    op.execute("ALTER TABLE inbox_items DROP CONSTRAINT IF EXISTS inbox_items_kind_check")
    op.execute(f"ALTER TABLE inbox_items ADD CONSTRAINT inbox_items_kind_check CHECK (kind IN ({KINDS}))")
