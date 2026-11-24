"""SOS recovery: the app can find and resume its open incident, 409s are structured, and orphaned incidents expire."""
import hashlib
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from sqlalchemy import text

from app.schemas import CreateIncident
from app.services.incidents import expire_orphaned
from tests.test_maitri_features import make_fix, maitri_env  # noqa: F401  (fixture)


def create(client, key=None, actor="VICTIM_A", **body):
    return client.post("/api/incidents", headers={"X-Test-Actor": actor, "Idempotency-Key": key or str(uuid4())},
                       json={"is_test": True, **body})


def active(client, actor="VICTIM_A"):
    return client.get("/api/incidents/active", headers={"X-Test-Actor": actor})


def sql(client, sessions, query, **params):
    async def run():
        async with sessions.begin() as db:
            result = await db.execute(text(query), params)
            return result.all() if result.returns_rows else None
    return client.portal.call(run)


def test_active_endpoint_returns_only_the_callers_open_incident(maitri_env):
    client, _, _, _ = maitri_env
    assert active(client).status_code == 204
    key = str(uuid4())
    created = create(client, key).json()
    mine = active(client)
    assert mine.status_code == 200
    assert mine.json()["id"] == created["id"] and mine.json()["status"] == "ACTIVE"
    assert mine.json()["idempotency_key"] == key and mine.json()["started_at"]
    # Another account never sees it.
    assert active(client, "TRUSTED_CONTACT_C").status_code == 204


def test_second_session_gets_incident_already_open_with_the_id(maitri_env):
    client, _, _, _ = maitri_env
    first = create(client).json()
    again = create(client)  # new idempotency key: the phone lost its local session
    assert again.status_code == 409
    assert again.headers["X-Error-Code"] == "INCIDENT_ALREADY_OPEN"
    assert again.json()["detail"]["code"] == "INCIDENT_ALREADY_OPEN"
    assert again.json()["detail"]["incident_id"] == first["id"]


def test_retry_with_a_newer_fix_is_the_same_request(maitri_env):
    client, _, _, _ = maitri_env
    key = str(uuid4())
    first = create(client, key)
    retry = create(client, key, location=make_fix())
    assert first.status_code == retry.status_code == 200
    assert retry.json()["id"] == first.json()["id"]
    located = client.get(f"/api/incidents/{first.json()['id']}/location", headers={"X-Test-Actor": "VICTIM_A"}).json()
    assert located["latitude"] is not None


def test_incidents_hashed_before_this_release_still_replay(maitri_env):
    client, sessions, _, _ = maitri_env
    key = str(uuid4())
    incident = create(client, key).json()["id"]
    legacy = hashlib.sha256(CreateIncident(is_test=True).model_dump_json().encode()).hexdigest()
    sql(client, sessions, "UPDATE incidents SET request_hash=:h WHERE id=:id", h=legacy, id=incident)
    assert create(client, key).json()["id"] == incident


def test_idempotency_mismatch_is_structured(maitri_env):
    client, sessions, _, _ = maitri_env
    key = str(uuid4())
    incident = create(client, key).json()["id"]
    sql(client, sessions, "UPDATE incidents SET request_hash=:h WHERE id=:id", h="0" * 64, id=incident)
    mismatch = create(client, key)
    assert mismatch.status_code == 409
    assert mismatch.json()["detail"] == {"code": "IDEMPOTENCY_MISMATCH",
        "message": "Idempotency key already used with different input", "incident_id": incident}


def test_a_bad_fix_never_blocks_creating_the_sos(maitri_env):
    client, _, _, _ = maitri_env
    stale = make_fix(seconds=-3600)  # far older than max_location_age_seconds
    created = create(client, location=stale)
    assert created.status_code == 200 and created.json()["status"] == "ACTIVE"
    located = client.get(f"/api/incidents/{created.json()['id']}/location", headers={"X-Test-Actor": "VICTIM_A"}).json()
    assert located["latitude"] is None


def test_superseded_fix_is_a_structured_location_conflict(maitri_env):
    client, _, _, _ = maitri_env
    incident = create(client, location=make_fix()).json()["id"]
    older = make_fix(lat=28.7, seconds=-30)
    rejected = client.post(f"/api/incidents/{incident}/location", headers={"X-Test-Actor": "VICTIM_A"}, json=older)
    assert rejected.status_code == 409 and rejected.json()["detail"]["code"] == "LOCATION_CONFLICT"


def test_orphaned_incidents_expire_but_live_and_duress_ones_do_not(maitri_env):
    client, sessions, settings, _ = maitri_env
    orphan = create(client).json()["id"]
    live = create(client, actor="TRUSTED_CONTACT_C").json()["id"]
    duress = create(client, actor="RESPONDER_B").json()["id"]
    idle = datetime.now(timezone.utc) - timedelta(hours=settings.orphaned_incident_hours + 1)
    sql(client, sessions, "UPDATE incidents SET started_at=:t, location_received_at=NULL WHERE id IN (:a, :c)",
        t=idle, a=orphan, c=duress)
    sql(client, sessions, "UPDATE incidents SET started_at=:t, location_received_at=now() WHERE id=:b", t=idle, b=live)
    sql(client, sessions, "UPDATE incidents SET status='ESCALATION_REQUIRED' WHERE id=:c", c=duress)

    async def run():
        async with sessions.begin() as db:
            return await expire_orphaned(db, settings)
    expired = client.portal.call(run)

    assert [str(i) for i in expired if str(i) in (orphan, live, duress)] == [orphan]
    states = dict(sql(client, sessions, "SELECT id::text, status FROM incidents WHERE id IN (:a, :b, :c)",
                      a=orphan, b=live, c=duress))
    assert states == {orphan: "CLOSED", live: "ACTIVE", duress: "ESCALATION_REQUIRED"}
    event = sql(client, sessions, "SELECT metadata->>'actor' FROM incident_events WHERE incident_id=:a "
                "AND event_type='INCIDENT_AUTO_EXPIRED'", a=orphan)
    assert event == [("system",)]
    # The owner can start a fresh SOS once the orphan is gone.
    assert active(client).status_code == 204
    assert create(client).status_code == 200
