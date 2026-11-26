"""GET /api/responders/history: paging, filters, outcomes, live close push, retention and the privacy rules
(no coordinates and no name or phone of the person in need, ever; an approximate area only after accepting)."""
import json
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import text

from app.maintenance import cleanup
from app.services.incidents import expire_orphaned
from tests.test_moving_geofence import enable, fix, world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio

PIN = {"pin": "839162", "confirm_pin": "839162"}
NAME, PHONE = "Priya Sharma", "+91 98000 11111"
FORBIDDEN_KEYS = {"latitude", "longitude", "lat", "lon", "lng", "location", "latest_location", "display_name",
                  "name", "phone", "victim_phone", "phone_number"}


async def sos(call, owner, metres_east=0):
    """An SOS from `owner` (with a name and phone that must never reach the history)."""
    await call(owner, "GET", "/users/me")
    assert (await call(owner, "PATCH", "/users/me", json={"display_name": NAME, "phone_number": PHONE,
                                                          "share_phone_in_sos": True})).is_success
    response = await call(owner, "POST", "/incidents", headers={"Idempotency-Key": str(uuid4())},
                          json={"is_test": True, "location": fix(metres_east)})
    assert response.is_success, response.text
    return response.json()["id"]


async def invitation_for(call, helper, incident):
    return next(v for v in (await call(helper, "GET", "/responders/invitations")).json() if v["incident_id"] == incident)


async def answer(call, helper, incident, decision):
    invitation = await invitation_for(call, helper, incident)
    response = await call(helper, "POST", f"/responders/invitations/{invitation['id']}/response", json={"decision": decision})
    assert response.is_success, response.text


async def resolve(call, owner, incident):
    assert (await call(owner, "POST", "/security/safety-pin/setup", json=PIN)).is_success
    assert (await call(owner, "POST", f"/incidents/{incident}/resolve", json={"pin": PIN["pin"]})).is_success


async def history(call, actor, **params):
    response = await call(actor, "GET", "/responders/history", params=params)
    assert response.status_code == 200, response.text
    return response.json()


def assert_private(payload):
    """No coordinates, name or phone anywhere in the response, at any depth."""
    def walk(value):
        if isinstance(value, dict):
            assert not FORBIDDEN_KEYS & set(value), set(value) & FORBIDDEN_KEYS
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)
    walk(payload)
    body = json.dumps(payload)
    assert NAME not in body and "Priya" not in body and "98000" not in body


async def test_resolved_sos_shows_area_and_timeline_but_no_coordinates_or_identity(world):
    call, sessions, _ = world
    await enable(call, "helper", 430)
    incident = await sos(call, "owner")
    await answer(call, "helper", incident, "ACCEPTED")
    assert (await call("helper", "PUT", f"/incidents/{incident}/responders/status", json={"status": "ON_THE_WAY"})).is_success

    active = (await history(call, "helper"))["items"][0]
    assert active["incident_state"] == "ACTIVE" and active["my_response"] == "ACCEPTED"
    assert active["closed_at"] is None and active["duration_seconds"] >= 0
    assert_private(active)

    await resolve(call, "owner", incident)
    page = await history(call, "helper")
    assert_private(page)
    item = page["items"][0]
    assert item["incident_id"] == incident and item["is_test"] is True
    assert item["my_response"] == "ACCEPTED" and item["incident_state"] == "RESOLVED"
    assert item["closed_at"] and item["accepted_at"] and item["responders"] == 1
    # The helper was 430 m east of the SOS: "about 450 m west of you", rounded to 50 m, and the band they saw.
    assert item["area"] == {"distance_m": 450, "direction": "W"}
    assert item["distance_band"] == "Under 500 m"
    assert [entry["type"] for entry in item["timeline"]] == ["INVITED", "ACCEPTED", "ON_THE_WAY", "RESOLVED"]
    assert page["summary"] == {"total": 1, "helped": 0, "resolved": 0, "practice": 1}

    # The helper's phone was told at once ("Incident resolved"), with ids only.
    async with sessions.begin() as db:
        row = (await db.execute(text("""SELECT i.kind, i.title, i.data FROM inbox_items i JOIN users u ON u.id = i.user_id
            WHERE u.firebase_uid = 'helper' AND i.kind = 'SOS_REQUEST_CLOSED'"""))).mappings().one()
    assert row["title"] == "Incident resolved"
    assert row["data"]["reason"] == "RESOLVED" and row["data"]["accepted"] == "true"
    assert row["data"]["incident_id"] == incident
    assert_private(row["data"])


async def test_declined_and_missed_keep_only_the_distance_band(world):
    call, _, _ = world
    await enable(call, "helper", 700)
    declined = await sos(call, "owner-a")
    await answer(call, "helper", declined, "DECLINED")
    missed = await sos(call, "owner-b")
    await resolve(call, "owner-b", missed)  # closed before the helper answered
    page = await history(call, "helper")
    assert_private(page)
    by_id = {item["incident_id"]: item for item in page["items"]}
    assert by_id[declined]["my_response"] == "DECLINED" and by_id[declined]["incident_state"] == "ACTIVE"
    assert by_id[missed]["my_response"] == "EXPIRED" and by_id[missed]["incident_state"] == "RESOLVED"
    for item in by_id.values():
        assert item["area"] is None and item["distance_band"] == "500 m – 1 km"
        assert item["accepted_at"] is None and item["duration_seconds"] is None
    assert [e["type"] for e in by_id[missed]["timeline"]] == ["INVITED", "MISSED", "RESOLVED"]
    assert page["summary"] == {"total": 2, "helped": 0, "resolved": 0, "practice": 0}


async def test_paging_filters_and_outcomes_over_many_requests(world):
    call, sessions, settings = world
    await enable(call, "helper", 300)
    expected = {}
    # Seven SOSes, oldest first: resolved, timed out (orphaned), left, can't help, declined, missed, active.
    for index, kind in enumerate(["resolved", "orphaned", "left", "cant_help", "declined", "missed", "active"]):
        owner = f"owner-{index}"
        incident = await sos(call, owner)
        expected[incident] = kind
        if kind == "declined":
            await answer(call, "helper", incident, "DECLINED")
        elif kind != "missed":
            await answer(call, "helper", incident, "ACCEPTED")
        if kind == "left":
            assert (await call("helper", "PUT", f"/incidents/{incident}/responders/status",
                               json={"status": "LEFT_INCIDENT"})).is_success
        if kind == "cant_help":
            assert (await call("helper", "PUT", f"/incidents/{incident}/responders/status",
                               json={"status": "UNABLE_TO_HELP"})).is_success
        if kind == "resolved":
            await resolve(call, owner, incident)
        if kind == "missed":
            async with sessions.begin() as db:
                await db.execute(text("UPDATE incident_invitations SET expires_at = now() - interval '1 minute' "
                                      "WHERE incident_id = :id"), {"id": incident})
        if kind == "orphaned":
            async with sessions.begin() as db:
                hours = settings.orphaned_incident_hours + 1
                await db.execute(text("UPDATE incidents SET started_at = now() - make_interval(hours => :h), "
                                      "location_received_at = now() - make_interval(hours => :h) WHERE id = :id"),
                                 {"id": incident, "h": hours})
                await db.execute(text("UPDATE incident_invitations SET invited_at = now() - make_interval(hours => :h) + "
                                      "interval '1 second' * :i, responded_at = now() - make_interval(hours => :h) "
                                      "WHERE incident_id = :id"), {"id": incident, "h": hours, "i": index})
                await db.execute(text("UPDATE incident_responders SET accepted_at = now() - make_interval(hours => :h), "
                                      "assistance_updated_at = now() - make_interval(hours => :h) "
                                      "WHERE incident_id = :id"), {"id": incident, "h": hours})
                await db.execute(text("UPDATE incident_events SET created_at = now() - make_interval(hours => :h) "
                                      "WHERE incident_id = :id"), {"id": incident, "h": hours})
                assert incident in [str(i) for i in await expire_orphaned(db, settings)]

    # Every page in order, newest first, no repeats, three at a time.
    seen, cursor = [], None
    while True:
        page = await history(call, "helper", limit=3, **({"cursor": cursor} if cursor else {}))
        assert_private(page)
        assert len(page["items"]) <= 3
        seen += page["items"]
        cursor = page["next_cursor"]
        if not cursor:
            break
    assert len(seen) == 7 and len({item["invitation_id"] for item in seen}) == 7
    stamps = [(item["invited_at"], item["invitation_id"]) for item in seen]
    assert stamps == sorted(stamps, reverse=True)
    state = {item["incident_id"]: (item["my_response"], item["incident_state"]) for item in seen}
    names = {kind: state[incident] for incident, kind in expected.items()}
    assert names == {
        "resolved": ("ACCEPTED", "RESOLVED"),
        "orphaned": ("ACCEPTED", "CLOSED_ORPHANED"),
        "left": ("LEFT", "ACTIVE"),
        "cant_help": ("CANT_HELP", "ACTIVE"),
        "declined": ("DECLINED", "ACTIVE"),
        "missed": ("EXPIRED", "ACTIVE"),
        "active": ("ACCEPTED", "ACTIVE"),
    }
    left = next(item for item in seen if expected[item["incident_id"]] == "left")
    assert left["timeline"][-1]["type"] == "LEFT" and left["duration_seconds"] is not None

    def kinds(page):
        return sorted(expected[item["incident_id"]] for item in page["items"])
    assert kinds(await history(call, "helper", filter="accepted")) == ["active", "cant_help", "left", "orphaned", "resolved"]
    assert kinds(await history(call, "helper", filter="resolved")) == ["resolved"]
    assert kinds(await history(call, "helper", filter="declined_missed")) == ["declined", "missed"]
    summary = (await history(call, "helper"))["summary"]
    assert summary == {"total": 7, "helped": 0, "resolved": 0, "practice": 5}
    # One request by id (the app asks for this when an SOS ends).
    one = next(i for i, kind in expected.items() if kind == "resolved")
    only = (await history(call, "helper", incident_id=one))["items"]
    assert [item["incident_id"] for item in only] == [one]
    # Nobody else sees them.
    assert (await history(call, "stranger"))["items"] == []


async def test_history_follows_retention_and_rejects_bad_input(world):
    call, sessions, settings = world
    await enable(call, "helper", 300)
    incident = await sos(call, "owner")
    await answer(call, "helper", incident, "ACCEPTED")
    await resolve(call, "owner", incident)
    assert settings.responder_history_days == 30
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incident_invitations SET invited_at = now() - interval '31 days'"))
    assert (await history(call, "helper"))["items"] == []
    async with sessions.begin() as db:
        await cleanup(db, settings)
        assert await db.scalar(text("SELECT count(*) FROM incident_invitations")) == 0

    assert (await call("helper", "GET", "/responders/history", params={"cursor": "not-a-cursor"})).status_code == 400
    assert (await call("helper", "GET", "/responders/history", params={"limit": 500})).status_code == 422
    assert (await call("helper", "GET", "/responders/history", params={"filter": "everything"})).status_code == 422


async def test_active_requests_are_kept_by_retention(world):
    call, sessions, settings = world
    await enable(call, "helper", 300)
    incident = await sos(call, "owner")
    await answer(call, "helper", incident, "ACCEPTED")
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incident_invitations SET invited_at = now() - interval '31 days'"))
        await cleanup(db, settings)
        # The SOS is still open: its requests stay (they prevent inviting the same volunteer twice).
        assert await db.scalar(text("SELECT count(*) FROM incident_invitations")) == 1


async def test_practice_sos_is_not_counted_as_help(world):
    call, sessions, _ = world
    await enable(call, "helper", 300)
    practice = await sos(call, "owner-practice")
    await answer(call, "helper", practice, "ACCEPTED")
    await resolve(call, "owner-practice", practice)
    real = await sos(call, "owner-real")
    async with sessions.begin() as db:  # the test server only accepts practice SOSes; make this one real
        await db.execute(text("UPDATE incidents SET is_test = false WHERE id = :id"), {"id": real})
    await answer(call, "helper", real, "ACCEPTED")
    await resolve(call, "owner-real", real)
    page = await history(call, "helper")
    assert page["summary"] == {"total": 2, "helped": 1, "resolved": 1, "practice": 1}
    assert {item["incident_id"]: item["is_test"] for item in page["items"]} == {practice: True, real: False}
    # The filters still list practice SOSes (with their Practice tag); only the summary leaves them out.
    assert len((await history(call, "helper", filter="resolved"))["items"]) == 2
