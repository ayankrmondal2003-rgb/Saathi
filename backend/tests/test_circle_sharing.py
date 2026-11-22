"""Isolated transactions and authenticated identity fixtures; never uses live Firebase or Render."""
from datetime import datetime, timedelta, timezone
from uuid import uuid4
import pytest
from sqlalchemy import text
from test_trusted_contacts import client_env


def headers(actor="victim", key=None):
    return {"X-Test-Actor": actor, **({"Idempotency-Key": key} if key else {})}


def fix(seconds=0):
    return {"latitude": 22.57, "longitude": 88.36, "accuracy_meters": 5,
        "recorded_at": (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat(), "is_mock": True}


def pair(client):
    client.get("/api/users/me", headers=headers())
    client.get("/api/users/me", headers=headers("friend"))
    contact = client.post("/api/trusted-contacts", headers=headers(),
        json={"display_name": "Consenting test friend", "phone_number": "+919876543210"}).json()
    code = client.post("/api/circle-sharing/pairing-code", headers=headers("friend"),
        json={"owner_email": "victim@example.invalid", "consent": True})
    assert code.status_code == 200, code.text
    binding = {"contact_id": contact["id"], "code": code.json()["code"]}
    linked = client.post("/api/circle-sharing/links", headers=headers(), json=binding)
    assert linked.status_code == 200, linked.text
    assert client.post("/api/circle-sharing/links", headers=headers(), json=binding).status_code == 404
    return contact["id"], linked.json()["id"]


def start(client, contact, kind="TRIP", key=None):
    body = {"kind": kind, "consent": True, "contact_ids": [contact]}
    if kind == "TRIP":
        body["location"] = fix()
    response = client.post("/api/circle-sharing/sessions", headers=headers(key=key or str(uuid4())), json=body)
    assert response.status_code == 200, response.text
    return response.json()


def test_pairing_owner_binding_and_unpaired_denial(client_env):
    client, _ = client_env
    client.get("/api/users/me", headers=headers())
    code = client.post("/api/circle-sharing/pairing-code", headers=headers("friend"),
        json={"owner_email": "victim@example.invalid", "consent": True}).json()["code"]
    c = client.post("/api/trusted-contacts", headers=headers("attacker"),
        json={"display_name": "Other", "phone_number": "+919876543211"}).json()
    assert client.post("/api/circle-sharing/links", headers=headers("attacker"),
        json={"contact_id": c["id"], "code": code}).status_code == 404
    assert client.post("/api/circle-sharing/sessions", headers=headers("attacker", str(uuid4())),
        json={"kind": "CHECK_IN", "consent": True, "contact_ids": [c["id"]]}).status_code == 403


def test_trip_authorization_freshness_and_stop(client_env):
    client, _ = client_env
    contact, link = pair(client)
    trip = start(client, contact)
    path = "/api/circle-sharing/sessions/" + trip["id"]
    assert trip["recipients"][0]["notification_status"] == "AVAILABLE_IN_APP"
    assert client.get(path, headers=headers("friend")).json()["latest_fix"]["latitude"] == 22.57
    assert client.get(path, headers=headers("attacker")).status_code == 404
    assert client.put(path+"/location", headers=headers("friend"), json=fix()).status_code == 404
    assert client.put(path+"/location", headers=headers(), json=trip["latest_fix"]).status_code == 409
    assert client.put(path+"/location", headers=headers(), json=fix(-1000)).status_code == 422
    assert client.put(path+"/location", headers=headers(), json=fix()).status_code == 200
    assert client.post(path+"/stop", headers=headers("attacker")).status_code == 404
    assert client.post(path+"/stop", headers=headers()).status_code == 200
    assert client.post(path+"/stop", headers=headers()).status_code == 200
    assert client.get(path, headers=headers("friend")).status_code == 404
    assert client.get(path, headers=headers()).json()["latest_fix"] is None
    assert client.put(path+"/location", headers=headers(), json=fix()).status_code == 409


def test_checkin_idempotency_timestamp_and_read_receipt(client_env):
    client, _ = client_env
    contact, _ = pair(client)
    key = str(uuid4())
    a = start(client, contact, "CHECK_IN", key)
    b = start(client, contact, "CHECK_IN", key)
    assert a["id"] == b["id"]
    assert a["created_at"] == b["created_at"]
    assert a["latest_fix"] is None
    path = "/api/circle-sharing/sessions/" + a["id"]
    # Listing does not falsely mark a notification as read or delivered.
    assert any(x["id"] == a["id"] for x in client.get("/api/circle-sharing/sessions", headers=headers("friend")).json())
    assert client.get(path, headers=headers()).json()["recipients"][0]["notification_status"] == "AVAILABLE_IN_APP"
    assert client.post(path+"/opened", headers=headers("attacker")).status_code == 404
    assert client.post(path+"/opened", headers=headers("friend")).status_code == 200
    assert client.get(path, headers=headers()).json()["recipients"][0]["notification_status"] == "OPENED"
    assert client.post("/api/circle-sharing/sessions", headers=headers(key=key),
        json={"kind": "CHECK_IN", "consent": True, "contact_ids": [contact], "duration_minutes": 45}).status_code == 409


def test_recipient_revocation_and_contact_deletion(client_env):
    client, _ = client_env
    contact, link = pair(client)
    trip = start(client, contact)
    path = "/api/circle-sharing/sessions/" + trip["id"]
    assert client.delete("/api/circle-sharing/links/"+link, headers=headers("attacker")).status_code == 404
    assert client.delete("/api/circle-sharing/links/"+link, headers=headers("friend")).status_code == 200
    assert client.get(path, headers=headers("friend")).status_code == 404
    assert client.get("/api/circle-sharing/sessions", headers=headers("friend")).json() == []


def test_expiry_and_retention(client_env):
    client, _ = client_env
    contact, _ = pair(client)
    trip = start(client, contact)
    async def expire():
        async with client.app.state.sessions.begin() as db:
            await db.execute(text("UPDATE circle_sessions SET expires_at=now()-interval '1 second' WHERE id=:id"),
                {"id": trip["id"]})
    client.portal.call(expire)
    path = "/api/circle-sharing/sessions/" + trip["id"]
    assert client.get(path, headers=headers("friend")).status_code == 404
    assert client.get(path, headers=headers()).json()["status"] == "EXPIRED"
    assert client.get(path, headers=headers()).json()["latest_fix"] is None


def test_strict_consent_and_no_sos_side_effect(client_env):
    client, _ = client_env
    contact, _ = pair(client)
    assert client.post("/api/circle-sharing/sessions", headers=headers(key=str(uuid4())),
        json={"kind": "CHECK_IN", "consent": False, "contact_ids": [contact]}).status_code == 422
    assert client.post("/api/circle-sharing/sessions", headers=headers(key=str(uuid4())),
        json={"kind": "TRIP", "consent": True, "contact_ids": [contact]}).status_code == 422
    start(client, contact, "CHECK_IN")
    async def count():
        async with client.app.state.sessions() as db:
            return await db.scalar(text("""SELECT count(*) FROM incidents i JOIN users u ON i.victim_user_id=u.id
                WHERE u.firebase_uid=:uid"""), {"uid": client_env[1]+"victim"})
    assert client.portal.call(count) == 0


def test_concurrent_retry_and_request_reconciliation(client_env):
    from concurrent.futures import ThreadPoolExecutor
    client, _ = client_env
    contact, _ = pair(client)
    key = str(uuid4())
    with ThreadPoolExecutor(max_workers=2) as workers:
        results = list(workers.map(lambda _: start(client, contact, "CHECK_IN", key), range(2)))
    assert results[0]["id"] == results[1]["id"]
    assert client.get("/api/circle-sharing/requests/"+key, headers=headers()).json()["id"] == results[0]["id"]
    assert client.get("/api/circle-sharing/requests/"+key, headers=headers("friend")).status_code == 404


def test_contact_removal_revokes_existing_trip(client_env):
    client, _ = client_env
    contact, _ = pair(client)
    trip = start(client, contact)
    assert client.delete("/api/trusted-contacts/"+contact, headers=headers()).status_code == 204
    assert client.get("/api/circle-sharing/sessions/"+trip["id"], headers=headers("friend")).status_code == 404


def test_unauthenticated_sharing_requests_rejected(client_env):
    from app.core import identity
    client, _ = client_env
    client.app.dependency_overrides.pop(identity)
    assert client.get("/api/circle-sharing/sessions").status_code == 401
    assert client.post("/api/circle-sharing/pairing-code",
        json={"owner_email": "victim@example.invalid", "consent": True}).status_code == 401


def test_retention_erases_expired_coordinates(client_env):
    from app.circle_sharing import cleanup
    client, _ = client_env
    contact, _ = pair(client)
    trip = start(client, contact)
    async def purge():
        async with client.app.state.sessions.begin() as db:
            await db.execute(text("UPDATE circle_sessions SET expires_at=now()-interval '2 seconds' WHERE id=:id"),
                {"id": trip["id"]})
            await cleanup(db)
            return await db.scalar(text("SELECT latest_fix FROM circle_sessions WHERE id=:id"), {"id": trip["id"]})
    assert client.portal.call(purge) is None


def test_trip_requires_fresh_location_and_rejects_empty_location(client_env):
    client, _ = client_env
    contact, _ = pair(client)
    resp = client.post("/api/circle-sharing/sessions", headers=headers(key=str(uuid4())),
        json={"kind": "TRIP", "consent": True, "contact_ids": [contact]})
    assert resp.status_code == 422
    checkin = client.post("/api/circle-sharing/sessions", headers=headers(key=str(uuid4())),
        json={"kind": "CHECK_IN", "consent": True, "contact_ids": [contact]})
    assert checkin.status_code == 200
    assert checkin.json()["latest_fix"] is None


def test_expired_pairing_code_rejected(client_env):
    client, _ = client_env
    client.get("/api/users/me", headers=headers())
    client.get("/api/users/me", headers=headers("friend"))
    contact = client.post("/api/trusted-contacts", headers=headers(),
        json={"display_name": "Friend", "phone_number": "+919876543210"}).json()
    code_resp = client.post("/api/circle-sharing/pairing-code", headers=headers("friend"),
        json={"owner_email": "victim@example.invalid", "consent": True})
    assert code_resp.status_code == 200
    code = code_resp.json()["code"]

    async def expire():
        async with client.app.state.sessions.begin() as db:
            await db.execute(text("UPDATE circle_pairing_codes SET expires_at=now()-interval '1 second'"))
    client.portal.call(expire)

    binding = {"contact_id": contact["id"], "code": code}
    assert client.post("/api/circle-sharing/links", headers=headers(), json=binding).status_code == 404


