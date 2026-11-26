"""Responder history (GET /api/responders/history).

Everything the history shows is kept on rows that already exist for RESPONDER_HISTORY_DAYS, never coordinates:

- incident_invitations.invite_distance_m: the responder's straight-line distance to the SOS when invited, rounded to
  50 m; invite_direction: the 8-point compass direction from the responder to the SOS at that moment. Both are
  frozen at invite time, because the SOS location is erased 7 days after it ends.
- incident_invitations.outcome / outcome_at: LEFT or CANT_HELP, copied from incident_responders, which is deleted
  7 days after access ends.
- incidents.close_reason: RESOLVED (Safety PIN), ORPHANED (auto-closed after inactivity) or OTHER. Backfilled from
  the audit events that still exist.

Idempotent like 0009-0018.
"""
from alembic import op

revision = "0019"
down_revision = "0018"


def upgrade():
    op.execute("ALTER TABLE incident_invitations ADD COLUMN IF NOT EXISTS invite_distance_m integer")
    op.execute("ALTER TABLE incident_invitations ADD COLUMN IF NOT EXISTS invite_direction varchar(2)")
    op.execute("ALTER TABLE incident_invitations ADD COLUMN IF NOT EXISTS outcome varchar(16)")
    op.execute("ALTER TABLE incident_invitations ADD COLUMN IF NOT EXISTS outcome_at timestamptz")
    op.execute("ALTER TABLE incident_invitations DROP CONSTRAINT IF EXISTS incident_invitations_direction_check")
    op.execute("ALTER TABLE incident_invitations ADD CONSTRAINT incident_invitations_direction_check "
               "CHECK (invite_direction IN ('N','NE','E','SE','S','SW','W','NW'))")
    op.execute("ALTER TABLE incident_invitations DROP CONSTRAINT IF EXISTS incident_invitations_outcome_check")
    op.execute("ALTER TABLE incident_invitations ADD CONSTRAINT incident_invitations_outcome_check "
               "CHECK (outcome IN ('LEFT','CANT_HELP'))")
    op.execute("ALTER TABLE incident_invitations DROP CONSTRAINT IF EXISTS incident_invitations_distance_check")
    op.execute("ALTER TABLE incident_invitations ADD CONSTRAINT incident_invitations_distance_check "
               "CHECK (invite_distance_m >= 0)")
    op.execute("CREATE INDEX IF NOT EXISTS ix_invitations_history ON incident_invitations "
               "(recipient_user_id, invited_at DESC, id DESC)")
    op.execute("ALTER TABLE incidents ADD COLUMN IF NOT EXISTS close_reason varchar(16)")
    op.execute("ALTER TABLE incidents DROP CONSTRAINT IF EXISTS incidents_close_reason_check")
    op.execute("ALTER TABLE incidents ADD CONSTRAINT incidents_close_reason_check "
               "CHECK (close_reason IN ('RESOLVED','ORPHANED','OTHER'))")
    # Backfill from what is still known: the audit events (30 days) and the responder rows (7 days).
    op.execute("""UPDATE incidents i SET close_reason = CASE
        WHEN EXISTS (SELECT 1 FROM incident_events e WHERE e.incident_id = i.id
                     AND e.event_type = 'INCIDENT_RESOLVED_WITH_SAFETY_PIN') THEN 'RESOLVED'
        WHEN EXISTS (SELECT 1 FROM incident_events e WHERE e.incident_id = i.id
                     AND e.event_type = 'INCIDENT_AUTO_EXPIRED') THEN 'ORPHANED'
        ELSE 'OTHER' END
        WHERE i.status = 'CLOSED' AND i.close_reason IS NULL""")
    op.execute("""UPDATE incident_invitations v SET
        outcome = CASE r.assistance_status WHEN 'LEFT_INCIDENT' THEN 'LEFT' ELSE 'CANT_HELP' END,
        outcome_at = COALESCE(r.assistance_updated_at, r.revoked_at)
        FROM incident_responders r WHERE r.incident_id = v.incident_id AND r.responder_user_id = v.recipient_user_id
        AND r.assistance_status IN ('LEFT_INCIDENT', 'UNABLE_TO_HELP') AND v.outcome IS NULL""")


def downgrade():
    op.execute("ALTER TABLE incidents DROP CONSTRAINT IF EXISTS incidents_close_reason_check")
    op.execute("ALTER TABLE incidents DROP COLUMN IF EXISTS close_reason")
    op.execute("DROP INDEX IF EXISTS ix_invitations_history")
    for constraint in ("direction", "outcome", "distance"):
        op.execute(f"ALTER TABLE incident_invitations DROP CONSTRAINT IF EXISTS incident_invitations_{constraint}_check")
    for column in ("invite_distance_m", "invite_direction", "outcome", "outcome_at"):
        op.execute(f"ALTER TABLE incident_invitations DROP COLUMN IF EXISTS {column}")
