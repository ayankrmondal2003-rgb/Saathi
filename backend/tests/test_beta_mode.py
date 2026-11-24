"""ENVIRONMENT=beta: any verified account, real SOS dispatched to approved volunteers, at most N real SOS per hour."""
import asyncio
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from sqlalchemy import text

from app import core
from app.config import Settings
from app.services.outbox_worker import claim_pending_work
from tests.test_moving_geofence import create, enable, fix, world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio


class NoAllowlist(set):
    """The fixture adds every caller to the allowlist; this keeps it empty so open access is really tested."""
    def add(self, _):
        pass


async def close_all(sessions):
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incidents SET status='CLOSED' WHERE status='ACTIVE'"))


async def test_real_incident_refused_outside_beta(world):
    call, _, settings = world
    assert settings.environment == "test"
    response = await create(call, str(uuid4()), {"is_test": False, "location": fix(0)})
    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "REAL_INCIDENTS_DISABLED"


async def test_beta_dispatches_a_real_sos_to_accounts_outside_the_allowlist(world):
    call, sessions, settings = world
    settings.environment = "beta"
    settings.test_account_uids = NoAllowlist()
    await enable(call, "nearby", 500)
    created = await create(call, str(uuid4()), {"is_test": False, "location": fix(0)})
    assert created.status_code == 200 and created.json()["is_test"] is False
    invitations = (await call("nearby", "GET", "/responders/invitations")).json()
    assert len(invitations) == 1
    async with sessions.begin() as db:
        claimed = await claim_pending_work(db, allowed_uids=settings.allowed_uids())
        assert len(claimed) == 1
        assert await db.scalar(text("SELECT event_type FROM incident_events ORDER BY created_at LIMIT 1")) == "INCIDENT_CREATED"


async def test_allowlist_still_applies_outside_beta(world):
    call, _, settings = world
    settings.test_account_uids = NoAllowlist()
    assert (await call("stranger", "GET", "/users/me")).status_code == 200  # the fixture bypasses identity()
    assert settings.allowed_uids() == []
    await enable(call, "nearby", 500)
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    assert (await call("nearby", "GET", "/responders/invitations")).json() == []


async def test_real_sos_rate_limit(world):
    call, sessions, settings = world
    settings.environment = "beta"
    settings.sos_per_hour = 3
    for _ in range(3):
        assert (await create(call, str(uuid4()), {"is_test": False})).status_code == 200
        await close_all(sessions)
    limited = await create(call, str(uuid4()), {"is_test": False})
    assert limited.status_code == 429
    assert limited.json()["detail"]["code"] == "SOS_RATE_LIMITED"
    assert limited.headers["X-Error-Code"] == "SOS_RATE_LIMITED" and "112" in limited.json()["detail"]["message"]
    # A replay of an accepted request is not a new SOS and is never limited.
    key = str(uuid4())
    async with sessions.begin() as db:
        # The oldest SOS leaves the one-hour window.
        await db.execute(text("UPDATE incidents SET started_at = now() - interval '2 hours' "
                              "WHERE id = (SELECT id FROM incidents ORDER BY started_at LIMIT 1)"))
    first = await create(call, key, {"is_test": False})
    assert first.status_code == 200
    assert (await create(call, key, {"is_test": False})).json()["id"] == first.json()["id"]


def request_for(settings):
    return SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(settings=settings, firebase=None)))


@pytest.mark.parametrize("environment, allowed", [("beta", True), ("production", False), ("development", False)])
async def test_identity_opens_only_in_beta(monkeypatch, environment, allowed):
    settings = Settings(_env_file=None, database_url="postgresql+asyncpg://x/y_test", firebase_project_id="p",
                        environment=environment)
    claims = {"uid": "someone", "email": "a@example.invalid", "email_verified": True}
    monkeypatch.setattr(core.auth, "verify_id_token", lambda *_a, **_k: claims)
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials="token")
    if allowed:
        assert (await core.identity(request_for(settings), credentials)).uid == "someone"
    else:
        with pytest.raises(HTTPException) as error:
            await core.identity(request_for(settings), credentials)
        assert error.value.status_code == 403


async def test_unverified_accounts_stay_out_in_beta(monkeypatch):
    settings = Settings(_env_file=None, database_url="postgresql+asyncpg://x/y_test", firebase_project_id="p",
                        environment="beta")
    monkeypatch.setattr(core.auth, "verify_id_token",
                        lambda *_a, **_k: {"uid": "u", "email": "a@example.invalid", "email_verified": False})
    with pytest.raises(HTTPException) as error:
        await core.identity(request_for(settings), HTTPAuthorizationCredentials(scheme="Bearer", credentials="t"))
    assert error.value.status_code == 403
    await asyncio.sleep(0)
