"""P3: circle invites, SOS pushes to linked accounts, SMS prepared by the owner, live links, inbox, check-ins."""
from datetime import datetime, timezone
from uuid import uuid4

import pytest
from sqlalchemy import text

from app.checkins import CheckInScheduler, next_due, process_due
from app.inbox import send_pending_pushes
from app.services.incidents import revoke_incident_access
from app.services.outbox_worker import FcmSendResult
from tests.test_moving_geofence import create, fix, world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio


async def contact(call, actor, name, phone):
    response = await call(actor, "POST", "/trusted-contacts", json={"display_name": name, "phone_number": phone})
    assert response.status_code == 201, response.text
    return response.json()["id"]


async def pair(call, owner, contact_id, friend):
    code = (await call(owner, "POST", "/circle/invites", json={"contact_id": contact_id})).json()["code"]
    accepted = await call(friend, "POST", "/circle/invites/accept", json={"code": code.lower().replace("-", " ")})
    assert accepted.status_code == 200, accepted.text
    return code


async def inbox(call, actor):
    return (await call(actor, "GET", "/inbox")).json()


def sender_ok(tokens, *_):
    return [FcmSendResult(token=t, message_id="m-" + t) for t in tokens]


async def register_device(call, actor):
    response = await call(actor, "POST", "/devices/register",
                          json={"fcm_token": "tok-" + actor + "-" + "x" * 40, "device_identifier": "dev-" + actor, "platform": "android"})
    assert response.is_success, response.text


async def test_pairing_code_links_a_contact_and_is_single_use(world):
    call, _, _ = world
    friend_contact = await contact(call, "owner", "Friend", "+919800000001")
    code = await pair(call, "owner", friend_contact, "friend")
    links = (await call("owner", "GET", "/circle-sharing/links")).json()
    assert [l["contact_id"] for l in links] == [friend_contact]
    assert (await call("friend", "GET", "/circle/memberships")).json()[0]["owner_name"] == "owner"
    assert (await inbox(call, "owner"))["items"][0]["kind"] == "CIRCLE_LINKED"
    again = await call("stranger", "POST", "/circle/invites/accept", json={"code": code})
    assert again.status_code == 404 and again.json()["detail"]["code"] == "INVITE_NOT_FOUND"
    own = (await call("owner", "POST", "/circle/invites", json={"contact_id": friend_contact})).json()["code"]
    assert (await call("owner", "POST", "/circle/invites/accept", json={"code": own})).json()["detail"]["code"] == "OWN_INVITE"


async def test_invite_guessing_is_rate_limited(world):
    call, _, _ = world
    for _ in range(10):
        assert (await call("guesser", "POST", "/circle/invites/accept", json={"code": "AAAA-AAAA"})).status_code == 404
    limited = await call("guesser", "POST", "/circle/invites/accept", json={"code": "AAAA-AAAA"})
    assert limited.status_code == 429


async def test_sos_pushes_linked_contacts_and_returns_the_rest_for_sms(world):
    call, sessions, settings = world
    linked = await contact(call, "owner", "Linked", "+919800000002")
    unlinked = await contact(call, "owner", "Unlinked", "+919800000003")
    await pair(call, "owner", linked, "friend")
    await register_device(call, "friend")
    incident = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    alert = (await call("owner", "POST", f"/incidents/{incident}/trusted-contacts/alert")).json()
    assert alert["live_link"].startswith("http://test/l/")
    assert [c["contact_id"] for c in alert["sms_contacts"]] == [unlinked]
    status = {s["contact_id"]: s["delivery_status"] for s in alert["statuses"]}
    assert status == {linked: "SENDING", unlinked: "NOT_REACHED"}
    # Idempotent: a second call neither re-pushes nor duplicates.
    await call("owner", "POST", f"/incidents/{incident}/trusted-contacts/alert")
    items = [i for i in (await inbox(call, "friend"))["items"] if i["kind"] == "CIRCLE_SOS"]
    assert len(items) == 1 and items[0]["data"]["url"] == alert["live_link"] and items[0]["title"] == "owner needs help"

    sent = []
    def capture(tokens, title, body, data, app):
        sent.append(data)
        return sender_ok(tokens)
    assert await send_pending_pushes(sessions, None, capture) >= 1
    assert sent[0]["event_type"] == "CIRCLE_SOS" and "latitude" not in sent[0]

    async def statuses():
        rows = (await call("owner", "GET", f"/incidents/{incident}/trusted-contacts")).json()
        return {r["contact_id"]: r["delivery_status"] for r in rows}
    assert (await statuses())[linked] == "PUSH_SENT"
    assert (await call("friend", "POST", f"/inbox/{items[0]['id']}/delivered")).status_code == 204
    assert (await statuses())[linked] == "DELIVERED"
    assert (await call("friend", "POST", f"/inbox/{items[0]['id']}/read")).status_code == 204
    assert (await statuses())[linked] == "OPENED"
    assert (await call("owner", "POST", f"/incidents/{incident}/trusted-contacts/sms-prepared",
                       json={"contact_ids": [unlinked]})).status_code == 204
    assert (await statuses())[unlinked] == "SMS_PREPARED"
    # Nobody else can read the owner's statuses or mark their SMS.
    assert (await call("friend", "GET", f"/incidents/{incident}/trusted-contacts")).status_code == 404
    assert (await call("friend", "POST", f"/inbox/{uuid4()}/read")).status_code == 404


async def test_live_link_works_only_while_the_sos_is_active(world):
    call, sessions, _ = world
    linked = await contact(call, "owner", "Linked", "+919800000004")
    await pair(call, "owner", linked, "friend")
    incident = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    link = (await call("owner", "POST", f"/incidents/{incident}/trusted-contacts/alert")).json()["live_link"]
    path = link.removeprefix("http://test")
    client_call = lambda p: call("anyone", "GET", "/../" + p.lstrip("/"))  # noqa: E731
    page = await client_call(path)
    assert page.status_code == 200 and "Call 112" in page.text and "owner" not in page.text
    assert "Content-Security-Policy" in page.headers and page.headers["Cache-Control"] == "no-store"
    location = (await client_call(path + "/location")).json()
    assert location["status"] == "ACTIVE" and location["latitude"] == pytest.approx(0, abs=1e-6)
    tampered = path[:-2] + ("AA" if not path.endswith("AA") else "BB")
    assert (await client_call(tampered + "/location")).status_code == 404
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incidents SET status='CLOSED' WHERE id=:id"), {"id": incident})
        await revoke_incident_access(db, incident, datetime.now(timezone.utc))
    assert (await client_call(path + "/location")).json() == {"status": "ENDED"}
    kinds = [i["kind"] for i in (await inbox(call, "friend"))["items"]]
    assert "CIRCLE_SOS_ENDED" in kinds


async def test_missed_check_in_alerts_the_circle_once(world):
    call, sessions, _ = world
    linked = await contact(call, "owner", "Linked", "+919800000005")
    await pair(call, "owner", linked, "friend")
    created = await call("owner", "POST", "/circle-sharing/sessions", headers={"Idempotency-Key": str(uuid4())},
                         json={"kind": "CHECK_IN", "contact_ids": [linked], "consent": True, "due_minutes": 30})
    assert created.status_code == 200, created.text
    session = created.json()["id"]
    assert created.json()["due_at"] and created.json()["missed"] is False
    assert "CHECKIN_STARTED" in [i["kind"] for i in (await inbox(call, "friend"))["items"]]
    async with sessions.begin() as db:
        due = await next_due(db)
        assert due is not None and (due - datetime.now(timezone.utc)).total_seconds() > 60 * 20
        await db.execute(text("UPDATE circle_sessions SET due_at = now() - interval '1 minute' WHERE id=:id"), {"id": session})
    woken = []
    scheduler = CheckInScheduler(sessions, on_items=lambda: woken.append(1))
    assert await scheduler.run_once() is None  # nothing else due: it would now wait without polling
    assert woken
    friend_kinds = [i["kind"] for i in (await inbox(call, "friend"))["items"]]
    owner_items = [i for i in (await inbox(call, "owner"))["items"] if i["kind"] == "CHECKIN_MISSED"]
    assert friend_kinds.count("CHECKIN_MISSED") == 1 and len(owner_items) == 1
    async with sessions.begin() as db:
        assert await process_due(db) == 0
    snapshot = (await call("owner", "GET", f"/circle-sharing/sessions/{session}")).json()
    assert snapshot["missed"] is True


async def test_check_in_reminder_comes_five_minutes_before(world):
    call, sessions, _ = world
    linked = await contact(call, "owner", "Linked", "+919800000006")
    await pair(call, "owner", linked, "friend")
    await call("owner", "POST", "/circle-sharing/sessions", headers={"Idempotency-Key": str(uuid4())},
               json={"kind": "CHECK_IN", "contact_ids": [linked], "consent": True, "due_minutes": 5})
    async with sessions.begin() as db:
        assert await process_due(db) == 1
    assert [i["kind"] for i in (await inbox(call, "owner"))["items"]][0] == "CHECKIN_REMINDER"


async def test_phone_verified_accounts_get_a_circle_request(world):
    call, sessions, _ = world
    await call("verified", "GET", "/users/me")
    await call("typed", "GET", "/users/me")
    async with sessions.begin() as db:
        await db.execute(text("UPDATE users SET phone_verified=true, phone_number='+919811111111' WHERE display_name='verified'"))
        await db.execute(text("UPDATE users SET phone_number='+919822222222' WHERE display_name='typed'"))
    match = await contact(call, "owner", "Verified friend", "98111 11111")
    await contact(call, "owner", "Typed friend", "+919822222222")
    assert (await call("typed", "GET", "/circle/requests")).json() == []
    requests = (await call("verified", "GET", "/circle/requests")).json()
    assert len(requests) == 1 and requests[0]["owner_name"] == "owner"
    assert (await inbox(call, "verified"))["items"][0]["kind"] == "CIRCLE_REQUEST"
    assert (await call("verified", "POST", f"/circle/requests/{requests[0]['id']}/accept")).json()["status"] == "LINKED"
    assert [l["contact_id"] for l in (await call("owner", "GET", "/circle-sharing/links")).json()] == [match]


async def test_inbox_lists_pending_sos_requests_and_counts_unread(world):
    call, _, _ = world
    from tests.test_moving_geofence import enable
    await enable(call, "responder", 300)
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    box = await inbox(call, "responder")
    assert box["items"][0]["kind"] == "SOS_REQUEST" and box["unread_count"] >= 1
    linked = await contact(call, "owner", "Linked", "+919800000007")
    await pair(call, "owner", linked, "responder")
    assert (await call("owner", "POST", "/inbox/read-all")).status_code == 204
    assert (await inbox(call, "owner"))["unread_count"] == 0


async def test_recipient_can_add_the_owner_back_and_both_must_choose(world):
    call, sessions, _ = world
    friend_contact = await contact(call, "owner", "Friend", "+919800000001")
    await pair(call, "owner", friend_contact, "friend")
    membership = (await call("friend", "GET", "/circle/memberships")).json()[0]
    assert membership["owner_name"] == "owner" and membership["added_back"] is None
    # The owner's typed profile number is never used; the friend is asked for it.
    async with sessions.begin() as db:
        await db.execute(text("UPDATE users SET phone_number='+919833333333' WHERE display_name='owner'"))
    needed = await call("friend", "POST", f"/circle/memberships/{membership['link_id']}/add-back", json={})
    assert needed.status_code == 422 and needed.json()["detail"]["code"] == "PHONE_NEEDED"
    # A verified number is used directly: one tap.
    async with sessions.begin() as db:
        await db.execute(text("UPDATE users SET phone_verified=true WHERE display_name='owner'"))
    added = await call("friend", "POST", f"/circle/memberships/{membership['link_id']}/add-back", json={})
    assert added.status_code == 200 and added.json()["status"] == "REQUESTED", added.text
    saved = (await call("friend", "GET", "/trusted-contacts")).json()
    assert [(c["display_name"], c["phone_number"]) for c in saved] == [("owner", "+919833333333")]
    assert (await call("friend", "GET", "/circle/memberships")).json()[0]["added_back"] == "REQUESTED"
    # Not mutual until the owner accepts.
    assert (await call("owner", "GET", "/circle/memberships")).json() == []
    requests = (await call("owner", "GET", "/circle/requests")).json()
    assert len(requests) == 1 and requests[0]["owner_name"] == "friend"
    assert (await call("owner", "POST", f"/circle/requests/{requests[0]['id']}/accept")).json()["status"] == "LINKED"
    assert (await call("owner", "GET", "/circle/memberships")).json()[0]["owner_name"] == "friend"
    assert (await call("friend", "GET", "/circle/memberships")).json()[0]["added_back"] == "LINKED"
    # Repeating is harmless; strangers can't use someone else's membership.
    again = await call("friend", "POST", f"/circle/memberships/{membership['link_id']}/add-back", json={})
    assert again.json()["status"] == "LINKED"
    other = await call("stranger", "POST", f"/circle/memberships/{membership['link_id']}/add-back", json={})
    assert other.status_code == 404


async def test_add_back_with_a_typed_number_reuses_an_existing_contact(world):
    call, _, _ = world
    friend_contact = await contact(call, "owner", "Friend", "+919800000001")
    await pair(call, "owner", friend_contact, "friend")
    existing = await contact(call, "friend", "Owner (saved earlier)", "+919844444444")
    link_id = (await call("friend", "GET", "/circle/memberships")).json()[0]["link_id"]
    added = await call("friend", "POST", f"/circle/memberships/{link_id}/add-back", json={"phone_number": "98444 44444"})
    assert added.json() == {"status": "REQUESTED", "contact_id": existing}
    assert len((await call("friend", "GET", "/trusted-contacts")).json()) == 1
