"""Each case owns a migrated schema in an explicitly named test DB. Concurrent requests use separate connections."""
import asyncio
import os
from datetime import datetime, timedelta, timezone
from uuid import uuid4
import httpx
import pytest
import pytest_asyncio
from alembic import command
from alembic.config import Config
from fastapi import Request
from sqlalchemy import text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker
from app.config import Settings
from app.core import identity, Identity
from app.main import create_app
from app.maintenance import cleanup
from app.services.geofence import tick

pytestmark = pytest.mark.asyncio


@pytest_asyncio.fixture
async def world():
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.skip("Isolated PostGIS database required")
    name = make_url(url).database
    assert name and name.endswith("_test")
    schema = "phase5_" + uuid4().hex
    admin = create_async_engine(url)
    async with admin.begin() as db:
        assert await db.scalar(text("SELECT current_database()")) == name
        await db.scalar(text("SELECT postgis_version()"))
        await db.execute(text(f"CREATE SCHEMA {schema}"))
    engine = create_async_engine(url, connect_args={"server_settings":{"search_path":schema+",public"}})
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    settings = Settings(_env_file=None,database_url=url, firebase_project_id="test", environment="test", min_search_interval_seconds=0)
    try:
        def migrate(conn):
            cfg = Config("alembic.ini")
            cfg.attributes["connection"] = conn
            command.upgrade(cfg,"head")
        async with engine.begin() as db:
            # Public alembic_version must not be selected through search_path.
            await db.execute(text("CREATE TABLE alembic_version(version_num varchar(32) PRIMARY KEY)"))
            await db.run_sync(migrate)
            from tests.conftest import install_test_volunteer_approval
            await install_test_volunteer_approval(db)
        app = create_app(settings,initialize_firebase=False)
        app.state.sessions = sessions
        def who(request: Request):
            name = request.headers["X-Fixture-Actor"]
            settings.test_account_uids.add(name)
            return Identity(name,name+"@example.invalid",name,True)
        app.dependency_overrides[identity] = who
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url="http://test") as client:
            async def call(actor,method,path,**kwargs):
                headers = kwargs.pop("headers",{})
                return await client.request(method,"/api"+path,headers={"X-Fixture-Actor":actor,**headers},**kwargs)
            yield call, sessions, settings
    finally:
        await engine.dispose()
        async with admin.begin() as db:
            await db.execute(text(f"DROP SCHEMA {schema} CASCADE"))
        await admin.dispose()


def fix(metres, offset=0, mock=True):
    return dict(latitude=0,longitude=metres/111319.49079327357,accuracy_meters=5,is_mock=mock,
        recorded_at=(datetime.now(timezone.utc)+timedelta(seconds=offset)).isoformat())


async def enable(call,name,metres,mock=True):
    assert (await call(name,"PUT","/users/me",json=dict(display_name=name,responder_opt_in=True,responder_available=True))).is_success
    assert (await call(name,"PUT","/responders/location",json=fix(metres,mock=mock))).is_success


async def create(call,key,body):
    return await call("owner","POST","/incidents",headers={"Idempotency-Key":key},json=body)


async def test_moving_both_sides_and_atomic_outbox(world):
    call,sessions,settings = world
    await enable(call,"r1",500)
    await enable(call,"r2",3300)
    await enable(call,"r3",5000)
    await enable(call,"other-source",100,False)
    body = {"is_test":True,"location":fix(0)}
    key = str(uuid4())
    left,right = await asyncio.gather(create(call,key,body),create(call,key,body))
    assert left.json()["id"] == right.json()["id"]
    path = "/incidents/"+left.json()["id"]
    async def inbox(name):
        return (await call(name,"GET","/responders/invitations")).json()
    assert not await inbox("other-source")
    invitation = (await inbox("r1"))[0]["id"]
    assert (await call("r1","GET",path+"/location")).status_code == 404
    assert (await call("r1","POST",f"/responders/invitations/{invitation}/response",json={"decision":"ACCEPTED"})).is_success
    # R2 enters while the victim is stationary.
    assert (await call("r2","PUT","/responders/location",json=fix(600))).is_success
    assert len(await inbox("r2")) == 1
    for idx, meters in enumerate([2000, 4500, 7000, 0], 1):
        assert (await call("owner", "POST", path + "/location", json=fix(meters, offset=idx * 0.1))).is_success
        assert (await call("r1", "GET", path + "/location")).is_success
    assert len(await inbox("r3")) == 1
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT count(*) FROM notification_outbox")) == 3
        assert await db.scalar(text("SELECT count(*) FROM incident_invitations")) == 3
    newer,older = fix(7000,2), fix(4000,1)
    result = await asyncio.gather(*[call("owner","POST",path+"/location",json=x) for x in (newer,older)])
    assert result[0].is_success and result[1].status_code in (204,409)
    assert (await call("owner","GET",path+"/location")).json()["longitude"] == pytest.approx(newer["longitude"])
    accept,revoke = await asyncio.gather(
        call("r1","POST",f"/responders/invitations/{invitation}/response",json={"decision":"ACCEPTED"}),
        call("owner","POST",path+f"/invitations/{invitation}/revoke"))
    assert revoke.is_success and accept.status_code in (200,404)
    assert (await call("r1","GET",path+"/location")).status_code == 404


async def test_workers_expiry_closure_and_retention(world):
    call,sessions,settings = world
    await enable(call,"responder",1500)
    incident = (await create(call,str(uuid4()),{"is_test":True,"location":fix(0)})).json()["id"]
    path = "/incidents/"+incident
    async with sessions.begin() as db:
        await db.execute(text("UPDATE responder_locations SET location=ST_GeogFromText('POINT(0 0)')"))
        await db.execute(text("UPDATE incidents SET evaluated_at=NULL"))
    async def batch(fn):
        async with sessions.begin() as db:
            await fn(db,settings)
    await asyncio.gather(batch(tick),batch(tick))
    async with sessions.begin() as db:
        invite = await db.scalar(text("SELECT id FROM incident_invitations"))
        assert invite and await db.scalar(text("SELECT count(*) FROM notification_outbox")) == 1
        await db.execute(text("UPDATE incident_invitations SET expires_at=now()-interval '8 days'"))
    accepted,_ = await asyncio.gather(call("responder","POST",f"/responders/invitations/{invite}/response",
        json={"decision":"ACCEPTED"}),batch(cleanup))
    assert accepted.status_code == 409 or accepted.json()["status"] == "EXPIRED"
    assert (await call("responder","GET",path+"/location")).status_code == 404
    await enable(call,"new",3000)
    assert (await call("owner", "POST", "/security/safety-pin/setup", json={"pin": "839162", "confirm_pin": "839162"})).is_success
    moved,ending = await asyncio.gather(call("owner","POST",path+"/location",json=fix(3000)),
        call("owner", "POST", path + "/resolve", json={"pin": "839162"}))
    assert moved.status_code in (204,409) and ending.is_success
    assert ending.json()["status"] == "CLOSED"
    assert (await call("owner","POST",path+"/location",json=fix(2000))).status_code == 409
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incidents SET ended_at=now()-interval '169 hours'"))
    await batch(cleanup)
    await batch(cleanup)
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT latest_location IS NULL AND evaluated_center IS NULL FROM incidents"))
        assert not await db.scalar(text("SELECT count(*) FROM incident_invitations WHERE id=:id"), {"id": invite})
        assert not await db.scalar(text("SELECT count(*) FROM incident_invitations WHERE status='PENDING'"))
        assert not await db.scalar(text("SELECT count(*) FROM incident_responders"))


async def test_availability_throttle_and_expired_grant(world):
    call,sessions,settings = world
    settings.min_search_interval_seconds = 2
    await enable(call,"b",500)
    async def available(value):
        return await call("b","PUT","/users/me",json=dict(display_name="b",responder_opt_in=True,responder_available=value))
    assert (await available(False)).is_success
    row = (await create(call,str(uuid4()),{"is_test":True,"location":fix(0)})).json()
    path = "/incidents/"+row["id"]
    assert not (await call("b","GET","/responders/invitations")).json()
    assert (await available(True)).is_success
    invitation = (await call("b","GET","/responders/invitations")).json()[0]["id"]
    reply = "/responders/invitations/"+invitation+"/response"
    assert (await call("b","POST",reply,json={"decision":"ACCEPTED"})).is_success
    async with sessions.begin() as db:
        before = await db.scalar(text("SELECT evaluated_at FROM incidents"))
        await cleanup(db,settings)
    assert (await call("b","GET",path+"/location")).is_success
    assert (await call("owner","POST",path+"/location",json=fix(5))).is_success
    async with sessions.begin() as db:
        assert before == await db.scalar(text("SELECT evaluated_at FROM incidents"))
        await db.execute(text("UPDATE incident_responders SET access_expires_at=now()-interval '8 days'"))
        await db.execute(text("UPDATE incident_events SET created_at=now()-interval '31 days'"))
    assert (await call("b","POST",reply,json={"decision":"ACCEPTED"})).status_code == 404
    assert (await call("b","GET",path+"/location")).status_code == 404
    async with sessions.begin() as db:
        await cleanup(db,settings)
        assert await db.scalar(text("SELECT count(*) FROM incident_responders")) == 0
        assert await db.scalar(text("SELECT count(*) FROM incident_events")) == 0
        assert await db.scalar(text("SELECT count(*) FROM incidents WHERE latest_location IS NOT NULL")) == 1
        assert await db.scalar(text("SELECT count(*) FROM incident_invitations")) == 1


async def test_two_account_loopback_http(world):
    import secrets
    import socket
    import uvicorn
    from fastapi import HTTPException
    from app.core import database

    _, sessions, settings = world
    app = create_app(settings, initialize_firebase=False)
    tokens = {secrets.token_urlsafe(32):name for name in ("sender","helper")}
    settings.test_account_uids.update(tokens.values())
    def verified(request: Request):
        name = tokens.get(request.headers.get("Authorization","").removeprefix("Bearer "))
        if name is None:
            raise HTTPException(401)
        return Identity(name,name+"@example.invalid",name,True)
    async def db():
        async with sessions.begin() as session:
            yield session
    app.dependency_overrides.update({identity:verified, database:db})
    server = uvicorn.Server(uvicorn.Config(app,access_log=False,log_level="warning"))
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        sock.listen()
        task = asyncio.create_task(server.serve(sockets=[sock]))
        try:
            async with asyncio.timeout(10):
                while not server.started:
                    if task.done():
                        await task
                    await asyncio.sleep(0.02)
            async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{sock.getsockname()[1]}", trust_env=False) as client:
                async def call(name,method,path,**kwargs):
                    token = next(key for key,actor in tokens.items() if actor==name)
                    headers = dict(kwargs.pop("headers",{}),Authorization="Bearer "+token)
                    return await client.request(method,"/api"+path,headers=headers,**kwargs)
                result = await call("sender","POST","/incidents",headers={"Idempotency-Key":str(uuid4())},
                                    json={"is_test":True,"location":fix(0)})
                path = "/incidents/"+result.json()["id"]
                await enable(call,"helper",300)
                invite = (await call("helper","GET","/responders/invitations")).json()[0]["id"]
                assert (await call("helper","GET",path+"/location")).status_code == 404
                assert (await call("helper","POST",f"/responders/invitations/{invite}/response",
                                   json={"decision":"ACCEPTED"})).is_success
                assert (await call("sender","POST",path+"/location",json=fix(4000))).is_success
                assert (await call("helper","GET",path+"/location")).json()["longitude"] == pytest.approx(4000/111319.49079327357)
                assert (await call("sender","POST",path+f"/invitations/{invite}/revoke")).is_success
                assert (await call("helper","GET",path+"/location")).status_code == 404
        finally:
            server.should_exit = True
            await task


async def test_security_concurrency_and_boundaries(world):
    call, sessions, settings = world
    now = datetime.now(timezone.utc)

    # 1. Boundary condition: 999.9m vs 1000.1m
    await enable(call, "boundary-inside", 999.9)
    await enable(call, "boundary-outside", 1000.1)
    incident_resp = await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    incident_id = incident_resp.json()["id"]
    path = f"/incidents/{incident_id}"

    async def inbox(name):
        return (await call(name, "GET", "/responders/invitations")).json()

    assert len(await inbox("boundary-inside")) == 1
    assert len(await inbox("boundary-outside")) == 0

    # 2. Input validation on location update
    # Victim SOS fixes are kept up to max_sos_accuracy_meters (5000 m); beyond that they are rejected with a reason.
    bad_acc = fix(0)
    bad_acc["accuracy_meters"] = 5001.0
    rejected = await call("owner", "POST", path + "/location", json=bad_acc)
    assert rejected.status_code == 422 and rejected.json()["detail"]["code"] == "LOCATION_TOO_INACCURATE"

    # Stale > 120s on responder returns 422, on incident with newer fix returns 409
    stale_fix = fix(0)
    stale_fix["recorded_at"] = (now - timedelta(seconds=125)).isoformat()
    assert (await call("boundary-inside", "PUT", "/responders/location", json=stale_fix)).status_code == 422
    assert (await call("owner", "POST", path + "/location", json=stale_fix)).status_code == 409

    # Future > 5s
    future_fix = fix(0)
    future_fix["recorded_at"] = (now + timedelta(seconds=10)).isoformat()
    assert (await call("owner", "POST", path + "/location", json=future_fix)).status_code == 422

    # 3. Out-of-order GPS updates & idempotent duplicate
    fix_t1 = fix(50, offset=1)
    assert (await call("owner", "POST", path + "/location", json=fix_t1)).status_code == 204
    # Replay of exact same fix succeeds (idempotent duplicate)
    assert (await call("owner", "POST", path + "/location", json=fix_t1)).status_code == 204
    # Older timestamp with different location returns 409
    fix_older = fix(20, offset=0.5)
    assert (await call("owner", "POST", path + "/location", json=fix_older)).status_code == 409

    # 4. Unauthorized operations
    # Stranger read
    assert (await call("stranger", "GET", path + "/location")).status_code == 404
    # Non-owner update
    assert (await call("stranger", "POST", path + "/location", json=fix(100, offset=2))).status_code == 404
    # Attempting to respond to another user's invitation
    inside_invite = (await inbox("boundary-inside"))[0]["id"]
    assert (await call("stranger", "POST", f"/responders/invitations/{inside_invite}/response", json={"decision": "ACCEPTED"})).status_code == 404

    malformed = await call("boundary-inside", "POST", "/responders/invitations/test-example/response",
                           json={"decision": "ACCEPTED"})
    assert malformed.status_code == 422
    assert [(item["loc"], item["type"]) for item in malformed.json()["detail"]] == [
        (["path", "invitation_id"], "uuid_parsing")]
    wrong_id = await call("boundary-inside", "POST", f"/responders/invitations/{incident_id}/response",
                          json={"decision": "ACCEPTED"})
    assert wrong_id.status_code == 404

    respond_path = f"/responders/invitations/{inside_invite}/response"
    async with sessions.begin() as db:
        await db.execute(text("""UPDATE responder_locations SET recorded_at=now()-interval '1 hour'
            WHERE user_id=(SELECT id FROM users WHERE display_name='boundary-inside')"""))
    assert (await call("boundary-inside", "POST", respond_path, json={"decision": "ACCEPTED"})).status_code == 403
    assert (await call("boundary-inside", "GET", path + "/location")).status_code == 404

    # 5. Blocked / Suspended accounts
    async with sessions.begin() as db:
        await db.execute(text("UPDATE users SET account_status='BLOCKED' WHERE display_name='boundary-inside'"))
    assert (await call("boundary-inside", "GET", "/users/me")).status_code == 403
    assert (await call("boundary-inside", "POST", f"/responders/invitations/{inside_invite}/response", json={"decision": "ACCEPTED"})).status_code == 403

    # Cannot open second incident while one is active
    assert (await create(call, str(uuid4()), {"is_test": True, "location": fix(0, offset=3)})).status_code == 409

    # 6. Declined invitation prevents access and deduplicates
    # Close first incident so owner can create a new one, or use victim2
    assert (await call("owner", "POST", "/security/safety-pin/setup", json={"pin": "839162", "confirm_pin": "839162"})).is_success
    assert (await call("owner", "POST", path + "/resolve", json={"pin": "839162"})).is_success

    await enable(call, "decliner", 400)
    dec_incident = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0, offset=3)})).json()["id"]
    dec_path = f"/incidents/{dec_incident}"
    dec_invite = (await inbox("decliner"))[0]["id"]
    assert (await call("decliner", "POST", f"/responders/invitations/{dec_invite}/response", json={"decision": "DECLINED"})).json()["status"] == "DECLINED"
    assert (await call("decliner", "GET", dec_path + "/location")).status_code == 404
    # Victim moves; decliner is NOT invited again
    assert (await call("owner", "POST", dec_path + "/location", json=fix(450, offset=4))).status_code == 204
    assert len(await inbox("decliner")) == 1

    # 7. Closed incident rejects updates
    assert (await call("owner", "POST", dec_path + "/resolve", json={"pin": "839162"})).is_success
    assert (await call("owner", "POST", dec_path + "/location", json=fix(500, offset=5))).status_code == 409

