"""P5: responder names/distances for the owner, consent-gated victim phone, distance bands, responder reports."""
from uuid import uuid4

import pytest
from sqlalchemy import text

from tests.test_moving_geofence import create, enable, fix, world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio


async def accepted_incident(call):
    await enable(call, "helper", 400)
    incident = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    invitation = (await call("helper", "GET", "/responders/invitations")).json()[0]
    assert invitation["distance_band"] == "Under 500 m" and "latitude" not in invitation
    assert (await call("helper", "POST", f"/responders/invitations/{invitation['id']}/response",
                       json={"decision": "ACCEPTED"})).is_success
    return incident


async def test_owner_sees_responder_name_distance_and_position(world):
    call, _, _ = world
    incident = await accepted_incident(call)
    owner_view = (await call("owner", "GET", f"/incidents/{incident}/snapshot")).json()
    responder = owner_view["active_responders"][0]
    assert responder["display_name"] == "helper"
    assert responder["distance_meters"] == pytest.approx(400, rel=0.05)
    assert responder["latitude"] is not None and owner_view["victim_phone"] is None
    # A responder sees other responders' first names only, never their positions.
    helper_view = (await call("helper", "GET", f"/incidents/{incident}/snapshot")).json()
    assert helper_view["active_responders"][0]["latitude"] is None
    assert helper_view["active_responders"][0]["distance_meters"] is None


async def test_victim_phone_reaches_responders_only_with_consent(world):
    call, _, _ = world
    await call("owner", "GET", "/users/me")
    assert (await call("owner", "PATCH", "/users/me", json={"display_name": "owner", "phone_number": "+91 98000 11111"})).is_success
    incident = await accepted_incident(call)
    assert (await call("helper", "GET", f"/incidents/{incident}/snapshot")).json()["victim_phone"] is None
    profile = (await call("owner", "PATCH", "/users/me", json={"display_name": "owner", "share_phone_in_sos": True})).json()
    assert profile["share_phone_in_sos"] is True
    assert (await call("helper", "GET", f"/incidents/{incident}/snapshot")).json()["victim_phone"] == "+91 98000 11111"
    assert (await call("owner", "GET", f"/incidents/{incident}/snapshot")).json()["victim_phone"] is None


async def test_responders_can_report_a_false_alarm(world):
    call, sessions, _ = world
    incident = await accepted_incident(call)
    assert (await call("owner", "POST", f"/incidents/{incident}/report", json={"reason": "FALSE_ALARM"})).status_code == 403
    assert (await call("stranger", "POST", f"/incidents/{incident}/report", json={"reason": "FALSE_ALARM"})).status_code in (403, 404)
    assert (await call("helper", "POST", f"/incidents/{incident}/report",
                       json={"reason": "FALSE_ALARM", "note": "Nobody there, owner said it was a mistake"})).status_code == 204
    async with sessions.begin() as db:
        meta = await db.scalar(text("SELECT metadata FROM incident_events WHERE event_type='RESPONDER_REPORT'"))
    assert meta["reason"] == "FALSE_ALARM"
