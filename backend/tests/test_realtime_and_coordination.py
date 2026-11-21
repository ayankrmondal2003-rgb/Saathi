"""Tests for Phase 7: Secure Real-Time Incident Tracking, Responder Coordination & Authorization."""
import json
import os
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import pytest
from fastapi import Request
from sqlalchemy.engine import make_url
from starlette.testclient import TestClient

from app.config import Settings
from app.core import Identity, identity
from app.main import create_app


@pytest.fixture
def client_env():
    url = os.getenv("TEST_DATABASE_URL")
    if not url:
        pytest.skip("Set TEST_DATABASE_URL to a migrated PostGIS database ending in _test")
    assert make_url(url).database.endswith("_test")

    settings = Settings(
        _env_file=None,
        database_url=url,
        firebase_project_id="test",
        environment="test",
        min_search_interval_seconds=0
    )
    app = create_app(settings, initialize_firebase=False)
    prefix = uuid4().hex

    def who(request: Request):
        actor = request.headers.get("X-Test-Actor", "victim")
        settings.test_account_uids.add(prefix + actor)
        return Identity(prefix + actor, f"{actor}@example.invalid", actor, True)

    app.dependency_overrides[identity] = who
    with TestClient(app) as client:
        yield client, prefix


def make_fix(lat=28.6139, lon=77.2090, accuracy=5.0, timestamp=None):
    now = timestamp or datetime.now(timezone.utc)
    return {
        "latitude": lat,
        "longitude": lon,
        "accuracy_meters": accuracy,
        "recorded_at": now.isoformat(),
        "is_mock": True
    }


def test_ticket_and_websocket_authorization_flow(client_env):
    test_client, prefix = client_env

    # 1. Setup victim and opt-in responder
    test_client.put("/api/users/me", headers={"X-Test-Actor": "victim"},
                    json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    test_client.put("/api/users/me", headers={"X-Test-Actor": "responder1"},
                    json={"display_name": "Responder 1", "responder_opt_in": True, "responder_available": True})

    # Responder posts initial location
    res = test_client.put("/api/responders/location", headers={"X-Test-Actor": "responder1"},
                          json=make_fix(28.6139, 77.2090))
    assert res.status_code == 204

    # 2. Victim creates test incident
    key = str(uuid4())
    inc_res = test_client.post("/api/incidents", headers={"X-Test-Actor": "victim", "Idempotency-Key": key},
                               json={"is_test": True, "location": make_fix(28.6139, 77.2090)})
    assert inc_res.status_code == 200
    incident_id = inc_res.json()["id"]

    # 3. Uninvited / unauthorized user cannot get a ticket
    unauth_ticket = test_client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "stranger"})
    assert unauth_ticket.status_code == 404

    # 4. Responder checks inbox and sees pending invitation
    inbox = test_client.get("/api/responders/invitations", headers={"X-Test-Actor": "responder1"}).json()
    assert len(inbox) >= 1
    invitation = inbox[0]
    invitation_id = invitation["id"]
    assert invitation["status"] == "PENDING"

    # Pending responder CANNOT get a ticket before accepting
    pending_ticket = test_client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "responder1"})
    assert pending_ticket.status_code == 404

    # 5. Responder accepts invitation
    accept_res = test_client.post(f"/api/responders/invitations/{invitation_id}/response",
                                  headers={"X-Test-Actor": "responder1"}, json={"decision": "ACCEPTED"})
    assert accept_res.status_code == 200
    assert accept_res.json()["status"] == "ACCEPTED"

    # 6. Accepted responder can now get a valid WS ticket
    ticket_res = test_client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "responder1"})
    assert ticket_res.status_code == 200
    ticket_data = ticket_res.json()
    assert "ticket" in ticket_data
    assert ticket_data["role"] == "RESPONDER"
    ticket = ticket_data["ticket"]

    # 7. Connecting without ticket or with invalid ticket is rejected
    with pytest.raises(Exception):
        with test_client.websocket_connect(f"/ws/incidents/{incident_id}") as ws:
            ws.receive_text()

    with pytest.raises(Exception):
        with test_client.websocket_connect(f"/ws/incidents/{incident_id}?ticket=invalid_ticket_12345") as ws:
            ws.receive_text()

    # 8. Accepted responder connects with valid ticket -> receives initial INCIDENT_SNAPSHOT
    with test_client.websocket_connect(f"/ws/incidents/{incident_id}?ticket={ticket}") as ws:
        snapshot_raw = ws.receive_text()
        snapshot_msg = json.loads(snapshot_raw)
        assert snapshot_msg["event_type"] == "INCIDENT_SNAPSHOT"
        assert snapshot_msg["incident_id"] == incident_id
        payload = snapshot_msg["payload"]
        assert payload["status"] == "ACTIVE"
        assert payload["latest_location"]["latitude"] == pytest.approx(28.6139)
        assert payload["latest_location"]["longitude"] == pytest.approx(77.2090)
        assert payload["requester_membership"]["role"] == "RESPONDER"

        # 9. Test ping/pong
        ws.send_text(json.dumps({"type": "PING"}))
        pong = json.loads(ws.receive_text())
        assert pong["type"] == "PONG"

        # 10. Test ticket single-use: cannot reuse the same ticket
        with pytest.raises(Exception):
            with test_client.websocket_connect(f"/ws/incidents/{incident_id}?ticket={ticket}") as ws2:
                ws2.receive_text()


def test_live_location_delivery_to_subscribers(client_env):
    test_client, prefix = client_env

    # Setup
    test_client.put("/api/users/me", headers={"X-Test-Actor": "victim"},
                    json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    test_client.put("/api/users/me", headers={"X-Test-Actor": "responder1"},
                    json={"display_name": "R1", "responder_opt_in": True, "responder_available": True})
    test_client.put("/api/responders/location", headers={"X-Test-Actor": "responder1"},
                    json=make_fix(28.6139, 77.2090))

    # Incident
    key = str(uuid4())
    inc = test_client.post("/api/incidents", headers={"X-Test-Actor": "victim", "Idempotency-Key": key},
                           json={"is_test": True, "location": make_fix(28.6139, 77.2090)}).json()
    incident_id = inc["id"]

    # Accept
    inv = test_client.get("/api/responders/invitations", headers={"X-Test-Actor": "responder1"}).json()[0]
    test_client.post(f"/api/responders/invitations/{inv['id']}/response",
                     headers={"X-Test-Actor": "responder1"}, json={"decision": "ACCEPTED"})

    # Get WS Ticket
    ticket = test_client.post(f"/api/incidents/{incident_id}/ws-ticket",
                              headers={"X-Test-Actor": "responder1"}).json()["ticket"]

    with test_client.websocket_connect(f"/ws/incidents/{incident_id}?ticket={ticket}") as ws:
        # Initial snapshot
        ws.receive_text()

        # Victim updates location along a route
        new_loc = make_fix(28.6145, 77.2095, accuracy=8.0, timestamp=datetime.now(timezone.utc) + timedelta(seconds=2))
        loc_res = test_client.post(f"/api/incidents/{incident_id}/location",
                                   headers={"X-Test-Actor": "victim"}, json=new_loc)
        assert loc_res.status_code == 204

        # Responder receives LOCATION_UPDATED event
        update_msg = json.loads(ws.receive_text())
        assert update_msg["event_type"] == "LOCATION_UPDATED"
        assert update_msg["payload"]["latitude"] == pytest.approx(28.6145)
        assert update_msg["payload"]["longitude"] == pytest.approx(77.2095)
        assert update_msg["payload"]["accuracy_meters"] == pytest.approx(8.0)


def test_responder_status_coordination(client_env):
    test_client, prefix = client_env

    # Setup
    test_client.put("/api/users/me", headers={"X-Test-Actor": "victim"},
                    json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    test_client.put("/api/users/me", headers={"X-Test-Actor": "responder1"},
                    json={"display_name": "R1", "responder_opt_in": True, "responder_available": True})
    test_client.put("/api/responders/location", headers={"X-Test-Actor": "responder1"},
                    json=make_fix(28.6139, 77.2090))

    key = str(uuid4())
    inc = test_client.post("/api/incidents", headers={"X-Test-Actor": "victim", "Idempotency-Key": key},
                           json={"is_test": True, "location": make_fix(28.6139, 77.2090)}).json()
    incident_id = inc["id"]

    inv = test_client.get("/api/responders/invitations", headers={"X-Test-Actor": "responder1"}).json()[0]
    test_client.post(f"/api/responders/invitations/{inv['id']}/response",
                     headers={"X-Test-Actor": "responder1"}, json={"decision": "ACCEPTED"})

    # Victim cannot set responder status
    victim_status = test_client.put(f"/api/incidents/{incident_id}/responders/status",
                                    headers={"X-Test-Actor": "victim"}, json={"status": "ON_THE_WAY"})
    assert victim_status.status_code == 400

    # Responder updates their own assistance status to ON_THE_WAY
    status_res = test_client.put(f"/api/incidents/{incident_id}/responders/status",
                                 headers={"X-Test-Actor": "responder1"}, json={"status": "ON_THE_WAY"})
    assert status_res.status_code == 200
    assert status_res.json()["status"] == "ON_THE_WAY"

    # Check snapshot reflects the updated status
    snap = test_client.get(f"/api/incidents/{incident_id}/snapshot", headers={"X-Test-Actor": "responder1"}).json()
    assert snap["requester_membership"]["assistance_status"] == "ON_THE_WAY"
    assert len(snap["active_responders"]) >= 1
    assert snap["active_responders"][0]["assistance_status"] == "ON_THE_WAY"

    # Responder leaves incident -> access revoked
    leave_res = test_client.put(f"/api/incidents/{incident_id}/responders/status",
                                headers={"X-Test-Actor": "responder1"}, json={"status": "LEFT_INCIDENT"})
    assert leave_res.status_code == 200
    assert leave_res.json()["status"] == "LEFT_INCIDENT"

    # Subsequent ticket request is denied
    denied_ticket = test_client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "responder1"})
    assert denied_ticket.status_code == 404


def test_access_revocation_and_incident_closure(client_env):
    test_client, prefix = client_env

    # Setup
    test_client.put("/api/users/me", headers={"X-Test-Actor": "victim"},
                    json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    test_client.put("/api/users/me", headers={"X-Test-Actor": "responder1"},
                    json={"display_name": "R1", "responder_opt_in": True, "responder_available": True})
    test_client.put("/api/responders/location", headers={"X-Test-Actor": "responder1"},
                    json=make_fix(28.6139, 77.2090))

    key = str(uuid4())
    inc = test_client.post("/api/incidents", headers={"X-Test-Actor": "victim", "Idempotency-Key": key},
                           json={"is_test": True, "location": make_fix(28.6139, 77.2090)}).json()
    incident_id = inc["id"]

    inv = test_client.get("/api/responders/invitations", headers={"X-Test-Actor": "responder1"}).json()[0]
    inv_id = inv["id"]
    test_client.post(f"/api/responders/invitations/{inv_id}/response",
                     headers={"X-Test-Actor": "responder1"}, json={"decision": "ACCEPTED"})

    # Victim revokes responder
    revoke_res = test_client.post(f"/api/incidents/{incident_id}/invitations/{inv_id}/revoke",
                                  headers={"X-Test-Actor": "victim"})
    assert revoke_res.status_code == 204

    # Responder can no longer access location API
    loc_res = test_client.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "responder1"})
    assert loc_res.status_code == 404

    # Responder cannot obtain WebSocket ticket
    revoked_ticket = test_client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "responder1"})
    assert revoked_ticket.status_code == 404

    # Close incident through secure test flow
    test_client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"}, json={"pin": "839162", "confirm_pin": "839162"})
    close_res = test_client.post(f"/api/incidents/{incident_id}/resolve", headers={"X-Test-Actor": "victim"}, json={"pin": "839162"})
    assert close_res.status_code == 200
    assert close_res.json()["status"] == "CLOSED"

    # Closed incident refuses tickets even to owner
    closed_ticket = test_client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "victim"})
    assert closed_ticket.status_code == 409


def test_responder_outside_1km_remains_authorized(client_env):
    test_client, prefix = client_env

    # 1. Setup victim and responder
    test_client.put("/api/users/me", headers={"X-Test-Actor": "victim"},
                    json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    test_client.put("/api/users/me", headers={"X-Test-Actor": "responder1"},
                    json={"display_name": "R1", "responder_opt_in": True, "responder_available": True})
    test_client.put("/api/responders/location", headers={"X-Test-Actor": "responder1"},
                    json=make_fix(0.0, 0.0))

    # Incident created at (0.0, 0.0)
    key = str(uuid4())
    inc = test_client.post("/api/incidents", headers={"X-Test-Actor": "victim", "Idempotency-Key": key},
                           json={"is_test": True, "location": make_fix(0.0, 0.0)}).json()
    incident_id = inc["id"]

    # Responder accepts
    inv = test_client.get("/api/responders/invitations", headers={"X-Test-Actor": "responder1"}).json()[0]
    test_client.post(f"/api/responders/invitations/{inv['id']}/response",
                     headers={"X-Test-Actor": "responder1"}, json={"decision": "ACCEPTED"})

    # Responder connects to WS
    ticket = test_client.post(f"/api/incidents/{incident_id}/ws-ticket",
                              headers={"X-Test-Actor": "responder1"}).json()["ticket"]

    with test_client.websocket_connect(f"/ws/incidents/{incident_id}?ticket={ticket}") as ws:
        ws.receive_text()  # snapshot

        # Victim moves 3 km away (e.g. 3000m / 111319.5 deg latitude)
        deg_3km = 3000.0 / 111319.49079327357
        moved_loc = make_fix(deg_3km, 0.0, accuracy=5.0, timestamp=datetime.now(timezone.utc) + timedelta(seconds=2))
        res = test_client.post(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "victim"}, json=moved_loc)
        assert res.status_code == 204

        # Responder continues receiving updates even though victim moved > 1 km away!
        msg = json.loads(ws.receive_text())
        assert msg["event_type"] == "LOCATION_UPDATED"
        assert msg["payload"]["latitude"] == pytest.approx(deg_3km)


def test_account_suspension_revokes_access(client_env):
    test_client, prefix = client_env

    test_client.put("/api/users/me", headers={"X-Test-Actor": "victim"},
                    json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    test_client.put("/api/users/me", headers={"X-Test-Actor": "responder1"},
                    json={"display_name": "R1", "responder_opt_in": True, "responder_available": True})
    test_client.put("/api/responders/location", headers={"X-Test-Actor": "responder1"},
                    json=make_fix(28.6139, 77.2090))

    key = str(uuid4())
    inc = test_client.post("/api/incidents", headers={"X-Test-Actor": "victim", "Idempotency-Key": key},
                           json={"is_test": True, "location": make_fix(28.6139, 77.2090)}).json()
    incident_id = inc["id"]

    inv = test_client.get("/api/responders/invitations", headers={"X-Test-Actor": "responder1"}).json()[0]
    test_client.post(f"/api/responders/invitations/{inv['id']}/response",
                     headers={"X-Test-Actor": "responder1"}, json={"decision": "ACCEPTED"})

    # Responder gets ticket
    ticket = test_client.post(f"/api/incidents/{incident_id}/ws-ticket",
                              headers={"X-Test-Actor": "responder1"}).json()["ticket"]

    # Suspend responder's account directly in database
    import asyncio
    import asyncpg
    url = os.getenv("TEST_DATABASE_URL")
    raw_url = url.replace("postgresql+asyncpg://", "postgresql://")
    async def do_suspend():
        conn = await asyncpg.connect(raw_url)
        try:
            await conn.execute("UPDATE users SET account_status = 'SUSPENDED' WHERE display_name = 'R1'")
        finally:
            await conn.close()
    asyncio.run(do_suspend())

    # Connecting with ticket fails authorization because account is SUSPENDED
    with pytest.raises(Exception):
        with test_client.websocket_connect(f"/ws/incidents/{incident_id}?ticket={ticket}") as ws:
            ws.receive_text()


def test_new_responder_joins_active_incident(client_env):
    test_client, prefix = client_env

    # 1. Setup victim and responder 1
    test_client.put("/api/users/me", headers={"X-Test-Actor": "victim"},
                    json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    test_client.put("/api/users/me", headers={"X-Test-Actor": "responder1"},
                    json={"display_name": "R1", "responder_opt_in": True, "responder_available": True})
    test_client.put("/api/responders/location", headers={"X-Test-Actor": "responder1"},
                    json=make_fix(28.6139, 77.2090))

    # Incident created
    key = str(uuid4())
    inc = test_client.post("/api/incidents", headers={"X-Test-Actor": "victim", "Idempotency-Key": key},
                           json={"is_test": True, "location": make_fix(28.6139, 77.2090)}).json()
    incident_id = inc["id"]

    # Responder 1 accepts
    inv1 = test_client.get("/api/responders/invitations", headers={"X-Test-Actor": "responder1"}).json()[0]
    test_client.post(f"/api/responders/invitations/{inv1['id']}/response",
                     headers={"X-Test-Actor": "responder1"}, json={"decision": "ACCEPTED"})

    # Responder 2 registers later, becomes available
    test_client.put("/api/users/me", headers={"X-Test-Actor": "responder2"},
                    json={"display_name": "R2", "responder_opt_in": True, "responder_available": True})
    test_client.put("/api/responders/location", headers={"X-Test-Actor": "responder2"},
                    json=make_fix(28.6140, 77.2091))

    # Victim moves significantly (>30m), triggering geofence evaluation which invites R2
    test_client.post(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "victim"},
                     json=make_fix(28.6145, 77.2095, timestamp=datetime.now(timezone.utc) + timedelta(seconds=2)))

    # Responder 2 sees invitation in inbox and accepts
    inbox2 = test_client.get("/api/responders/invitations", headers={"X-Test-Actor": "responder2"}).json()
    target_inv = next((inv for inv in inbox2 if str(inv["incident_id"]) == str(incident_id)), None)
    assert target_inv is not None, f"No invitation for incident {incident_id} in {inbox2}"
    resp_res = test_client.post(f"/api/responders/invitations/{target_inv['id']}/response",
                                headers={"X-Test-Actor": "responder2"}, json={"decision": "ACCEPTED"})
    assert resp_res.status_code == 200, f"Accept failed: {resp_res.text}"
    assert resp_res.json()["status"] == "ACCEPTED", f"Expected ACCEPTED but got {resp_res.json()}"

    # Responder 2 can now get WS ticket and receives the current snapshot of active incident
    t_res = test_client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "responder2"})
    assert t_res.status_code == 200, f"Ticket failed: {t_res.text}"
    ticket2 = t_res.json()["ticket"]

    with test_client.websocket_connect(f"/ws/incidents/{incident_id}?ticket={ticket2}") as ws2:
        snapshot = json.loads(ws2.receive_text())
        assert snapshot["event_type"] == "INCIDENT_SNAPSHOT"
        assert snapshot["payload"]["status"] == "ACTIVE"
        assert snapshot["payload"]["latest_location"]["latitude"] == pytest.approx(28.6145)
        assert snapshot["payload"]["latest_location"]["longitude"] == pytest.approx(77.2095)
        assert snapshot["payload"]["responder_summary"]["accepted_count"] >= 2


