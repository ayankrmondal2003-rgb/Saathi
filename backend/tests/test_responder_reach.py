"""Volunteers are reached while their app is closed: their last location counts for 30 minutes when choosing whom
to invite, while accepting still needs a fresh fix (the app sends one right before accepting)."""
from uuid import uuid4

import pytest
from sqlalchemy import text

from tests.test_moving_geofence import create, enable, fix, world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio


async def age_location(sessions, name, minutes):
    async with sessions.begin() as db:
        await db.execute(text("""UPDATE responder_locations SET recorded_at = now() - make_interval(mins => :m),
            searched_at = NULL WHERE user_id = (SELECT id FROM users WHERE display_name = :n)"""), {"m": minutes, "n": name})


async def invitations(call, name):
    return (await call(name, "GET", "/responders/invitations")).json()


async def test_volunteer_last_seen_20_minutes_ago_is_invited_40_minutes_is_not(world):
    call, sessions, _ = world
    await enable(call, "recent", 300)
    await enable(call, "long-gone", 300)
    await age_location(sessions, "recent", 20)
    await age_location(sessions, "long-gone", 40)
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    assert len(await invitations(call, "recent")) == 1
    assert await invitations(call, "long-gone") == []


async def test_accepting_needs_a_fresh_fix_which_the_app_sends_first(world):
    call, sessions, _ = world
    await enable(call, "helper", 300)
    await age_location(sessions, "helper", 20)
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    invitation = (await invitations(call, "helper"))[0]["id"]
    reply = f"/responders/invitations/{invitation}/response"
    stale = await call("helper", "POST", reply, json={"decision": "ACCEPTED"})
    assert stale.status_code == 403 and "Fresh matching responder location" in stale.json()["detail"]
    # What the app now does on Accept: report the current position, then accept.
    assert (await call("helper", "PUT", "/responders/location", json=fix(300, offset=1))).is_success
    assert (await call("helper", "POST", reply, json={"decision": "ACCEPTED"})).is_success


async def test_invitations_say_whether_the_sos_is_a_practice_one(world):
    call, _, _ = world
    await enable(call, "volunteer", 300)
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    item = (await invitations(call, "volunteer"))[0]
    assert item["is_test"] is True
    assert item["distance_band"] == "Under 500 m"
    assert "latitude" not in item and "longitude" not in item
