"""Shared fixtures for the isolated PostGIS suite.

Production only invites volunteers whose application is APPROVED (there is no admin review panel yet).
Legacy integration tests predate that rule and opt synthetic users in directly, so the isolated *test*
database gets a trigger that approves an opted-in synthetic user's application. Tests that exercise the
approval gate itself move the application back to UNDER_REVIEW explicitly. The trigger is never part of
an Alembic migration and is refused for any database whose name does not end in "_test".
"""
import asyncio
import os

import pytest
from sqlalchemy import text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import create_async_engine

TRIGGER_SQL = [
    """CREATE OR REPLACE FUNCTION test_only_auto_approve_volunteer() RETURNS trigger AS $$
       BEGIN
         INSERT INTO volunteer_applications(user_id, status) VALUES (NEW.id, 'APPROVED')
         ON CONFLICT (user_id) DO NOTHING;
         RETURN NEW;
       END $$ LANGUAGE plpgsql""",
    "DROP TRIGGER IF EXISTS test_only_auto_approve_volunteer ON users",
    """CREATE TRIGGER test_only_auto_approve_volunteer AFTER INSERT OR UPDATE OF responder_opt_in ON users
       FOR EACH ROW WHEN (NEW.responder_opt_in) EXECUTE FUNCTION test_only_auto_approve_volunteer()""",
]


async def install_test_volunteer_approval(conn):
    """Install the test-only trigger on the connection's current search_path (public or a per-test schema)."""
    if await conn.scalar(text("SELECT to_regclass('volunteer_applications')")) is None:
        return
    for statement in TRIGGER_SQL:
        await conn.execute(text(statement))


async def _install(url: str):
    engine = create_async_engine(url)
    try:
        async with engine.begin() as conn:
            await install_test_volunteer_approval(conn)
    finally:
        await engine.dispose()


@pytest.fixture(scope="session", autouse=True)
def approve_legacy_test_volunteers():
    url = os.environ.get("TEST_DATABASE_URL")
    if url and make_url(url).database.endswith("_test"):
        asyncio.run(_install(url))
    yield
