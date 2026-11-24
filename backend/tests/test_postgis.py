"""Exercise real migrated PostGIS tables in a transaction rolled back after each test."""
import os
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import httpx
import pytest
import pytest_asyncio
from fastapi import Request
from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from app.config import Settings
from app.core import Identity, database, identity
from app.database.v1 import invitations, users
from app.main import create_app

pytestmark = pytest.mark.asyncio


@pytest_asyncio.fixture
async def api():
    url = os.getenv("TEST_DATABASE_URL")
    if not url:
        pytest.skip("Set TEST_DATABASE_URL to a migrated PostGIS database ending in _test")
    assert make_url(url).database.endswith("_test")
    settings = Settings(_env_file=None, database_url=url, firebase_project_id="test", environment="test", min_search_interval_seconds=0)
    app = create_app(settings, initialize_firebase=False)
    engine = create_async_engine(url)
    prefix = uuid4().hex
    async with engine.connect() as connection:
        tx = await connection.begin()
        sessions = async_sessionmaker(connection, join_transaction_mode="create_savepoint")
        async def db():
            async with sessions.begin() as session:
                yield session

        def who(request: Request):
            name = request.headers["X-Test-Actor"]
            settings.test_account_uids.add(prefix + name)
            return Identity(prefix + name, name + "@example.invalid", name, True)

        app.dependency_overrides.update({identity:who, database:db})
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            async def call(actor, method, path, **kwargs):
                headers = kwargs.pop("headers", {})
                return await client.request(method, "/api" + path, headers={"X-Test-Actor":actor, **headers}, **kwargs)
            yield call, connection
        await tx.rollback()
    await engine.dispose()


async def test_moving_discovery_and_authorization(api):
    call, db = api
    now = datetime.now(timezone.utc)
    def position(lon, timestamp=now):
        return dict(latitude=0, longitude=lon, accuracy_meters=5, recorded_at=timestamp.isoformat(), is_mock=True)
    # At the equator one WGS84 degree of longitude spans this many metres.
    def lon(metres):
        return metres / 111319.49079327357
    names = {"near":500, "inside":999.9, "outside":1000.1, "far":1500, "decliner":10, "late":20,
             "off":5, "unavailable":5, "suspended":5, "stale":5}
    for name, distance in names.items():
        profile = dict(display_name=name, responder_opt_in=name != "off", responder_available=name not in ("off","unavailable"))
        assert (await call(name,"PUT","/users/me",json=profile)).status_code == 200
        result = await call(name,"PUT","/responders/location",json=position(lon(distance)))
        assert result.status_code == (403 if name in ("off","unavailable") else 204)
    from app.database.v1 import locations
    await db.execute(update(users).where(users.c.display_name == "suspended").values(account_status="SUSPENDED"))
    await db.execute(update(locations).where(locations.c.user_id.in_(select(users.c.id).where(users.c.display_name=="stale")))
                     .values(recorded_at=now-timedelta(minutes=40)))  # beyond responder_location_max_age (30 min)
    key = {"Idempotency-Key":str(uuid4())}
    body = {"is_test":True,"location":position(0)}
    result = await call("owner","POST","/incidents",headers=key,json=body)
    assert result.status_code == 200, result.text
    incident = result.json()["id"]
    path = "/incidents/" + incident
    assert (await call("owner","POST","/incidents",headers=key,json=body)).json()["id"] == incident
    assert (await call("owner","POST","/incidents",headers={"Idempotency-Key":str(uuid4())},json=body)).status_code == 409
    async def inbox(name):
        return (await call(name,"GET","/responders/invitations")).json()
    for name in ("near","inside","decliner","late"):
        assert len(await inbox(name)) == 1
    for name in ("outside","far","off","unavailable","stale","owner"):
        assert not await inbox(name)
    count = select(func.count()).select_from(invitations).where(invitations.c.incident_id==UUID(incident))
    assert await db.scalar(count) == 4
    assert (await call("near","GET",path+"/location")).status_code == 404
    assert (await call("far","POST",path+"/location",json=position(0))).status_code == 404
    async def answer(actor, invite, decision):
        return await call(actor,"POST","/responders/invitations/"+invite+"/response",json={"decision":decision})
    invite = (await inbox("near"))[0]["id"]
    assert (await answer("far", invite, "ACCEPTED")).status_code == 404
    for _ in range(2):
        assert (await answer("near", invite, "ACCEPTED")).json()["status"] == "ACCEPTED"
    assert (await answer("decliner",(await inbox("decliner"))[0]["id"],"DECLINED")).json()["status"] == "DECLINED"
    assert (await call("decliner","GET",path+"/location")).status_code == 404
    expired = (await inbox("late"))[0]["id"]
    await db.execute(update(invitations).where(invitations.c.id==UUID(expired)).values(expires_at=now-timedelta(seconds=1)))
    assert (await answer("late",expired,"ACCEPTED")).json()["status"] == "EXPIRED"
    assert (await call("late","GET",path+"/location")).status_code == 404
    moved = position(lon(2400), now+timedelta(seconds=1))
    for _ in range(2):
        assert (await call("owner","POST",path+"/location",json=moved)).status_code == 204
    assert (await call("owner","POST",path+"/location",json=position(0))).status_code == 409
    assert len(await inbox("far")) == 1
    assert len(await inbox("near")) == 1
    assert (await call("near","GET",path+"/location")).json()["longitude"] == pytest.approx(lon(2400))
    inside_invite = (await inbox("inside"))[0]["id"]
    assert (await answer("inside", inside_invite, "ACCEPTED")).status_code == 403
    assert (await call("inside","GET",path+"/location")).status_code == 404
    assert (await call("inside","PUT","/responders/location",json=position(lon(2400), now+timedelta(seconds=2)))).is_success
    assert (await answer("inside", inside_invite, "ACCEPTED")).status_code == 200
    assert (await call("inside","GET",path+"/location")).status_code == 200
    assert (await call("owner","POST",path+"/invitations/"+inside_invite+"/revoke")).status_code == 204
    assert (await call("inside","GET",path+"/location")).status_code == 404
    assert (await answer("inside", inside_invite, "ACCEPTED")).status_code == 404
    for action in ("request-resolution","close-test"):
        assert (await call("owner","POST",path+"/"+action)).status_code == 404
    assert (await call("owner", "POST", "/security/safety-pin/setup", json={"pin": "839162", "confirm_pin": "839162"})).is_success
    assert (await call("owner", "POST", path + "/resolve", json={"pin": "839162"})).is_success
    assert (await call("near","GET",path+"/location")).status_code == 404


async def test_database_constraints(api):
    _, db = api
    with pytest.raises(IntegrityError):
        async with db.begin_nested():
            await db.execute(users.insert().values(firebase_uid=str(uuid4()), email="x@example.invalid",
                email_verified=True, display_name="x", responder_opt_in=False, responder_available=True))
