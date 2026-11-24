"""DATABASE_SCHEMA.md matches the schema the migrations build (every table, column and index).

To regenerate after a migration: set UPDATE_SCHEMA_DOC=1 and run this test through tests/run_isolated.py:
    UPDATE_SCHEMA_DOC=1 .venv\\Scripts\\python.exe tests\\run_isolated.py -q tests/test_schema_doc.py
"""
import os
from pathlib import Path

import pytest
from sqlalchemy import text

from tests.test_moving_geofence import world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio
DOC = Path(__file__).resolve().parents[2] / "DATABASE_SCHEMA.md"
MARK = "<!-- generated below by backend/tests/test_schema_doc.py -->"

HEADER = f"""# Maitri database schema

PostgreSQL 16 with PostGIS (Neon in production). The schema is built only by Alembic migrations in
`backend/alembic/versions` (0001-0017, idempotent raw SQL), which run on every deploy before the API starts.

## How the tables fit together

- **Accounts:** `users` (one per Firebase account; profile, responder availability, encrypted medical notes,
  profile photo) and `devices` (push tokens per phone).
- **SOS:** `incidents` (one per SOS; owner, status and only the latest location, no trail) with
  `incident_invitations` (nearby volunteers asked to help), `incident_responders` (who accepted and their status)
  and `incident_events` (the audit trail, including responder reports).
- **Ending an SOS:** `safety_credentials` (hashed safety and duress PINs), `incident_resolution_attempts`
  (PIN tries, for lockout) and `incident_resolution_requests`.
- **Volunteers:** `responder_locations` (where available volunteers are, for the nearby search),
  `volunteer_applications` and `volunteer_documents` (documents and the review decision), `admin_audit_log`
  (what reviewers opened and decided).
- **Circle:** `trusted_contacts` (the owner's contacts), `circle_invites` / `circle_invite_attempts` /
  `circle_links` (contacts linked to a Maitri account by code), `incident_contact_alerts` (how each contact was
  alerted for an SOS), `trusted_contact_grants`, and `circle_sessions` / `circle_session_recipients` /
  `circle_pairing_codes` (shared trips and check-ins).
- **Messages:** `inbox_items` (circle alerts and reminders; also the push queue) and `notification_outbox`
  (volunteer SOS requests waiting to be pushed).
- **Files:** `stored_files` (metadata for files in private R2 storage: volunteer documents, SOS photos and voice
  notes, profile photos).

## Retention

Closed SOS locations are erased after 168 hours, SOS photos and voice notes after 30 days, and expired invitations,
grants and audit rows on a schedule (see `backend/app/maintenance.py` and `app/privacy.py`). Deleting an account
removes its rows, its files and its Firebase sign-in.

## Tables

{MARK}
"""


async def render(db):
    tables = [r[0] for r in (await db.execute(text("""
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' AND table_name <> 'alembic_version'
        ORDER BY table_name"""))).all()]
    out = [HEADER]
    for t in tables:
        cols = (await db.execute(text("""
            SELECT column_name, CASE WHEN data_type = 'USER-DEFINED' THEN udt_name ELSE data_type END,
                   is_nullable, column_default
            FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = :t
            ORDER BY ordinal_position"""), {"t": t})).all()
        idx = (await db.execute(text("""
            SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = :t
            ORDER BY indexname"""), {"t": t})).all()
        out += [f"### `{t}`", "", "| Column | Type | Null | Default |", "|---|---|---|---|"]
        for name, typ, nullable, default in cols:
            d = (default or "").replace("|", "/")
            if "nextval(" in d or len(d) > 60:
                d = d[:57] + "..." if len(d) > 60 else d
            out.append(f"| `{name}` | {typ} | {'yes' if nullable == 'YES' else 'no'} | {('`' + d + '`') if d else ''} |")
        if idx:
            out += ["", "Indexes: " + "; ".join(
                f"`{n}`" + (" (unique)" if "UNIQUE" in d else "") + (" (GiST)" if "USING gist" in d else "") for n, d in idx)]
        out.append("")
    return "\n".join(out).rstrip() + "\n"


async def test_schema_doc_matches_the_migrations(world):
    _, sessions, _ = world
    async with sessions() as db:
        text_doc = await render(db)
    if os.environ.get("UPDATE_SCHEMA_DOC") == "1":
        DOC.write_text(text_doc, encoding="utf8", newline="\n")
    current = DOC.read_text(encoding="utf8").replace("\r\n", "\n") if DOC.exists() else ""
    assert current == text_doc, "DATABASE_SCHEMA.md is out of date: run with UPDATE_SCHEMA_DOC=1 (see module docstring)"
