"""Phase 15: Reliability-first tests for Distributed Live Tracking, Multi-Worker Coordination,
Responder Authorization Lifecycle, and Deterministic A -> B -> C -> D Moving Geofence.
"""
import asyncio
import concurrent.futures
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
from app.services.realtime import RealtimeHub


class InMemoryPubSubBroker:
    """In-memory event bus simulating Redis Pub/Sub across worker threads."""

    def __init__(self):
        self._subscribers = {}

    def subscribe(self, worker_id: str, callback):
        self._subscribers[worker_id] = callback

    def unsubscribe(self, worker_id: str):
        self._subscribers.pop(worker_id, None)

    async def publish(self, sender_worker_id: str, message_data: str):
        for w_id, callback in list(self._subscribers.items()):
            if w_id != sender_worker_id:
                # Dispatches to target worker callback
                await callback(message_data)


@pytest.fixture
def multi_worker_env():
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.skip("Isolated PostGIS database required (set TEST_DATABASE_URL)")
    assert make_url(url).database.endswith("_test")

    redis_url = os.environ.get("TEST_REDIS_URL")
    broker = None if redis_url else InMemoryPubSubBroker()
    shared_test_uids = set()

    # Worker 1
    settings_1 = Settings(_env_file=None, database_url=url, firebase_project_id="test",
                          redis_url=redis_url, environment="test", min_search_interval_seconds=0, worker_id="worker-alpha")
    settings_1.test_account_uids = shared_test_uids
    app_1 = create_app(settings_1, initialize_firebase=False)
    hub_1 = RealtimeHub(worker_id="worker-alpha")
    if broker is not None:
        hub_1.attach_bus(broker)
    app_1.state.hub = hub_1

    # Worker 2
    settings_2 = Settings(_env_file=None, database_url=url, firebase_project_id="test",
                          redis_url=redis_url, environment="test", min_search_interval_seconds=0, worker_id="worker-beta")
    settings_2.test_account_uids = shared_test_uids
    app_2 = create_app(settings_2, initialize_firebase=False)
    hub_2 = RealtimeHub(worker_id="worker-beta")
    if broker is not None:
        hub_2.attach_bus(broker)
    app_2.state.hub = hub_2

    # Actor identity setup with isolated test UIDs per actor
    def make_who():
        def who(request: Request):
            actor = request.headers.get("X-Test-Actor", "user")
            shared_test_uids.add(actor)
            return Identity(actor, f"{actor}@example.invalid", actor, True)
        return who

    app_1.dependency_overrides[identity] = make_who()
    app_2.dependency_overrides[identity] = make_who()

    with TestClient(app_1) as client_1, TestClient(app_2) as client_2:
        hub_1._sessions = app_1.state.sessions
        hub_2._sessions = app_2.state.sessions
        yield client_1, client_2


def make_fix(metres: float, offset: float = 0.0, mock: bool = True):
    deg = metres / 111319.49079327357
    return {
        "latitude": 28.6139 + deg,
        "longitude": 77.2090,
        "accuracy_meters": 5.0,
        "is_mock": mock,
        "recorded_at": (datetime.now(timezone.utc) + timedelta(seconds=offset)).isoformat()
    }


def test_multi_worker_distributed_location_and_revocation(multi_worker_env):
    """PRIORITY 1 & 2: Validates distributed location broadcast, ticket consumption across workers,
    and cross-worker access revocation.
    """
    client_1, client_2 = multi_worker_env
    uid_sfx = uuid4().hex[:6]
    victim = f"v_dist_{uid_sfx}"
    responder = f"r_dist_{uid_sfx}"

    # Register victim on Worker 1
    client_1.put("/api/users/me", headers={"X-Test-Actor": victim},
                 json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})

    # Register responder on Worker 2
    client_2.put("/api/users/me", headers={"X-Test-Actor": responder},
                 json={"display_name": "Responder 1", "responder_opt_in": True, "responder_available": True})
    client_2.put("/api/responders/location", headers={"X-Test-Actor": responder},
                 json=make_fix(0.0))

    # Victim creates test incident on Worker 1
    key = str(uuid4())
    inc_res = client_1.post("/api/incidents", headers={"X-Test-Actor": victim, "Idempotency-Key": key},
                            json={"is_test": True, "location": make_fix(0.0)})
    assert inc_res.status_code == 200, f"Create incident failed: {inc_res.status_code} - {inc_res.text}"
    incident_id = inc_res.json()["id"]

    # Responder queries inbox on Worker 2 -> invitation present
    inbox = client_2.get("/api/responders/invitations", headers={"X-Test-Actor": responder}).json()
    assert len(inbox) >= 1, f"Expected invitation for {responder}, got {inbox}"
    invitation_id = inbox[0]["id"]

    # Responder accepts invitation on Worker 2
    accept_res = client_2.post(f"/api/responders/invitations/{invitation_id}/response",
                               headers={"X-Test-Actor": responder}, json={"decision": "ACCEPTED"})
    assert accept_res.status_code == 200

    # With shared Redis, the issuer and socket acceptor can be different workers.
    issuer = client_1 if os.environ.get("TEST_REDIS_URL") else client_2
    assert issuer.get("/ready").status_code == 200
    ticket_res = issuer.post(f"/api/incidents/{incident_id}/ws-ticket",
                               headers={"X-Test-Actor": responder})
    assert ticket_res.status_code == 200
    ticket = ticket_res.json()["ticket"]

    # Connect WebSocket to Worker 2 using the ticket
    with client_2.websocket_connect(f"/ws/incidents/{incident_id}", headers={"Authorization": f"Ticket {ticket}"}) as ws_worker2:
        # 1. Authoritative initial snapshot from PostgreSQL
        snapshot_raw = ws_worker2.receive_text()
        snapshot = json.loads(snapshot_raw)
        assert snapshot["event_type"] == "INCIDENT_SNAPSHOT"
        assert snapshot["payload"]["status"] == "ACTIVE"

        # 2. Victim posts location update to WORKER 1
        new_fix = make_fix(150.0, offset=2.0)
        fix_res = client_1.post(f"/api/incidents/{incident_id}/location",
                                headers={"X-Test-Actor": victim}, json=new_fix)
        assert fix_res.status_code == 204

        # 3. WORKER 2 receives the event via distributed bus and delivers to the WebSocket!
        worker2_event_raw = ws_worker2.receive_text()
        worker2_event = json.loads(worker2_event_raw)
        assert worker2_event["event_type"] == "LOCATION_UPDATED"
        assert worker2_event["payload"]["latitude"] == pytest.approx(new_fix["latitude"])

        # 4. Cross-Worker Revocation: Victim on WORKER 1 revokes the responder
        revoke_res = client_1.post(f"/api/incidents/{incident_id}/invitations/{invitation_id}/revoke",
                                   headers={"X-Test-Actor": victim})
        assert revoke_res.status_code == 204

        # 5. WORKER 2 receives revocation and immediately terminates WebSocket connection with ACCESS_REVOKED!
        revoked_msg_raw = ws_worker2.receive_text()
        revoked_msg = json.loads(revoked_msg_raw)
        assert revoked_msg["event_type"] == "ACCESS_REVOKED"

    # 6. Revoked responder CANNOT reconnect to Worker 1 OR Worker 2
    t1 = client_1.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": responder})
    t2 = client_2.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": responder})
    assert t1.status_code == 404
    assert t2.status_code == 404

    # Clean closure
    client_1.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": victim},
                  json={"pin": "839162", "confirm_pin": "839162"})
    client_1.post(f"/api/incidents/{incident_id}/resolve", headers={"X-Test-Actor": victim},
                  json={"pin": "839162"})


def test_multi_worker_incident_closure_cross_disconnect(multi_worker_env):
    """PRIORITY 1 & 2: Validates that closing an incident on Worker 1 broadcasts INCIDENT_CLOSED
    and terminates active WebSocket sessions on Worker 2.
    """
    client_1, client_2 = multi_worker_env
    uid_sfx = uuid4().hex[:6]
    victim = f"v_close_{uid_sfx}"
    responder = f"r_close_{uid_sfx}"

    client_1.put("/api/users/me", headers={"X-Test-Actor": victim},
                 json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    client_2.put("/api/users/me", headers={"X-Test-Actor": responder},
                 json={"display_name": "R1", "responder_opt_in": True, "responder_available": True})
    client_2.put("/api/responders/location", headers={"X-Test-Actor": responder},
                 json=make_fix(0.0))

    key = str(uuid4())
    inc_res = client_1.post("/api/incidents", headers={"X-Test-Actor": victim, "Idempotency-Key": key},
                            json={"is_test": True, "location": make_fix(0.0)})
    assert inc_res.status_code == 200, f"Incident create failed: {inc_res.status_code} - {inc_res.text}"
    incident_id = inc_res.json()["id"]

    inbox = client_2.get("/api/responders/invitations", headers={"X-Test-Actor": responder}).json()
    assert len(inbox) >= 1
    inv = inbox[0]
    client_2.post(f"/api/responders/invitations/{inv['id']}/response",
                  headers={"X-Test-Actor": responder}, json={"decision": "ACCEPTED"})

    ticket = client_2.post(f"/api/incidents/{incident_id}/ws-ticket",
                           headers={"X-Test-Actor": responder}).json()["ticket"]

    with client_2.websocket_connect(f"/ws/incidents/{incident_id}?ticket={ticket}") as ws_worker2:
        ws_worker2.receive_text()  # snapshot

        # Victim sets Safety PIN and resolves incident on WORKER 1
        client_1.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": victim},
                      json={"pin": "839162", "confirm_pin": "839162"})
        resolve_res = client_1.post(f"/api/incidents/{incident_id}/resolve",
                                    headers={"X-Test-Actor": victim},
                                    json={"pin": "839162", "reason": "USER_SAFE"})
        assert resolve_res.status_code == 200, f"Resolve failed: {resolve_res.text}"
        assert resolve_res.json()["status"] == "CLOSED"

        # WORKER 2 receives INCIDENT_CLOSED event and connection closes
        close_event_raw = ws_worker2.receive_text()
        close_event = json.loads(close_event_raw)
        assert close_event["event_type"] == "INCIDENT_CLOSED"


def test_deterministic_a_b_c_d_geofence_movement(multi_worker_env):
    """PRIORITY 3: Deterministic PostGIS A -> B -> C -> D movement test.
    - Victim starts at Point A (0m).
      - R1 (400m from A) is within 1km -> invited. R1 accepts.
      - R2 (1800m north, at Point B) is >1km -> NOT invited.
      - R3 (3500m north, at Point C) is >1km -> NOT invited.
      - R4 (5200m north, at Point D) is >1km -> NOT invited.
    - Victim moves to Point B (1800m):
      - R2 is now in range -> invited.
      - R1 was previously accepted -> R1's grant is PRESERVED (moving >1km does NOT revoke grant).
      - R3, R4 still out of range.
      - No duplicate invitation sent to R1.
    - Victim moves to Point C (3500m):
      - R3 is now in range -> invited.
    - Victim moves to Point D (5200m):
      - R4 is now in range -> invited.
    - Incident closed:
      - Geofence evaluations stop. All grants invalidated for location access.
    """
    client_1, client_2 = multi_worker_env
    uid_sfx = uuid4().hex[:6]
    victim = f"v_geo_{uid_sfx}"
    r1 = f"r1_geo_{uid_sfx}"
    r2 = f"r2_geo_{uid_sfx}"
    r3 = f"r3_geo_{uid_sfx}"
    r4 = f"r4_geo_{uid_sfx}"

    # 1. Setup opted-in responders at known deterministic metre offsets along north meridian
    client_1.put("/api/users/me", headers={"X-Test-Actor": victim},
                 json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})

    # Responders positioned deterministically
    responders = [
        (r1, 400.0),   # Point A range (<1000m from 0m)
        (r2, 1800.0),  # Point B (1800m)
        (r3, 3500.0),  # Point C (3500m)
        (r4, 5200.0),  # Point D (5200m)
    ]
    for name, metres in responders:
        client_1.put("/api/users/me", headers={"X-Test-Actor": name},
                     json={"display_name": name.upper(), "responder_opt_in": True, "responder_available": True})
        client_1.put("/api/responders/location", headers={"X-Test-Actor": name},
                     json=make_fix(metres))

    # Helper to get invitation count for responder
    def get_invitations(actor):
        return client_1.get("/api/responders/invitations", headers={"X-Test-Actor": actor}).json()

    # STEP A: Victim at Point A (0m)
    key = str(uuid4())
    inc_res = client_1.post("/api/incidents", headers={"X-Test-Actor": victim, "Idempotency-Key": key},
                            json={"is_test": True, "location": make_fix(0.0)})
    assert inc_res.status_code == 200, f"Incident create failed: {inc_res.status_code} - {inc_res.text}"
    incident_id = inc_res.json()["id"]

    # Verify only R1 is invited
    assert len(get_invitations(r1)) == 1
    assert len(get_invitations(r2)) == 0
    assert len(get_invitations(r3)) == 0
    assert len(get_invitations(r4)) == 0

    # R1 accepts invitation
    r1_inv = get_invitations(r1)[0]
    client_1.post(f"/api/responders/invitations/{r1_inv['id']}/response",
                  headers={"X-Test-Actor": r1}, json={"decision": "ACCEPTED"})

    # R1 can read location
    assert client_1.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": r1}).status_code == 200

    # STEP B: Victim moves to Point B (1800m north)
    loc_b = make_fix(1800.0, offset=1.0)
    assert client_1.post(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": victim},
                         json=loc_b).status_code == 204

    # Verify R2 is now invited
    assert len(get_invitations(r2)) == 1
    assert len(get_invitations(r3)) == 0
    assert len(get_invitations(r4)) == 0
    # R1 does NOT receive duplicate invitation
    assert len(get_invitations(r1)) == 1
    # R1 grant is PRESERVED even though victim is now 1400m away (>1km)
    assert client_1.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": r1}).status_code == 200

    # R2 accepts
    r2_inv = get_invitations(r2)[0]
    client_1.post(f"/api/responders/invitations/{r2_inv['id']}/response",
                  headers={"X-Test-Actor": r2}, json={"decision": "ACCEPTED"})

    # STEP C: Victim moves to Point C (3500m north)
    loc_c = make_fix(3500.0, offset=2.0)
    assert client_1.post(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": victim},
                         json=loc_c).status_code == 204

    # Verify R3 is now invited
    assert len(get_invitations(r3)) == 1
    assert len(get_invitations(r4)) == 0
    # R1 and R2 grants remain valid
    assert client_1.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": r1}).status_code == 200
    assert client_1.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": r2}).status_code == 200

    # STEP D: Victim moves to Point D (5200m north)
    loc_d = make_fix(5200.0, offset=3.0)
    assert client_1.post(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": victim},
                         json=loc_d).status_code == 204

    # Verify R4 is now invited
    assert len(get_invitations(r4)) == 1

    # Incident Closure: Invalidate all grants
    client_1.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": victim},
                  json={"pin": "839162", "confirm_pin": "839162"})
    res = client_1.post(f"/api/incidents/{incident_id}/resolve", headers={"X-Test-Actor": victim},
                        json={"pin": "839162"})
    assert res.status_code == 200, f"Resolve failed: {res.text}"
    assert res.json()["status"] == "CLOSED"

    # After closure, no responder can access location
    assert client_1.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": r1}).status_code == 404
    assert client_1.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": r2}).status_code == 404


def test_forged_incident_and_unauthorized_access(multi_worker_env):
    """PRIORITY 2: Validates that forged incident IDs and uninvited responders are strictly rejected."""
    client_1, client_2 = multi_worker_env
    uid_sfx = uuid4().hex[:6]
    victim = f"v_forge_{uid_sfx}"
    stranger = f"stranger_{uid_sfx}"
    attacker = f"attacker_{uid_sfx}"
    fake_id = str(uuid4())

    # Forged incident location access
    assert client_1.get(f"/api/incidents/{fake_id}/location", headers={"X-Test-Actor": attacker}).status_code == 404

    # Forged incident WS ticket request
    assert client_1.post(f"/api/incidents/{fake_id}/ws-ticket", headers={"X-Test-Actor": attacker}).status_code == 404

    # Real incident with uninvited user
    client_1.put("/api/users/me", headers={"X-Test-Actor": victim},
                 json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})
    key = str(uuid4())
    inc_res = client_1.post("/api/incidents", headers={"X-Test-Actor": victim, "Idempotency-Key": key},
                            json={"is_test": True, "location": make_fix(0.0)})
    assert inc_res.status_code == 200, f"Incident create failed: {inc_res.status_code} - {inc_res.text}"
    real_id = inc_res.json()["id"]

    # Stranger attempts to get ticket
    client_1.put("/api/users/me", headers={"X-Test-Actor": stranger},
                 json={"display_name": "Stranger", "responder_opt_in": True, "responder_available": True})
    assert client_1.post(f"/api/incidents/{real_id}/ws-ticket", headers={"X-Test-Actor": stranger}).status_code == 404

    # Clean closure
    client_1.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": victim},
                  json={"pin": "839162", "confirm_pin": "839162"})
    client_1.post(f"/api/incidents/{real_id}/resolve", headers={"X-Test-Actor": victim},
                  json={"pin": "839162"})


def test_concurrent_invitation_acceptance(multi_worker_env):
    """PRIORITY 2: Validates that concurrent invitation responses from multiple responders
    execute without deadlocks or race conditions.
    """
    client_1, client_2 = multi_worker_env
    uid_sfx = uuid4().hex[:6]
    victim = f"v_conc_{uid_sfx}"
    resp_a = f"ra_conc_{uid_sfx}"
    resp_b = f"rb_conc_{uid_sfx}"

    client_1.put("/api/users/me", headers={"X-Test-Actor": victim},
                 json={"display_name": "Victim", "responder_opt_in": False, "responder_available": False})

    for r_name in [resp_a, resp_b]:
        client_1.put("/api/users/me", headers={"X-Test-Actor": r_name},
                     json={"display_name": r_name, "responder_opt_in": True, "responder_available": True})
        client_1.put("/api/responders/location", headers={"X-Test-Actor": r_name},
                     json=make_fix(100.0))

    key = str(uuid4())
    inc_res = client_1.post("/api/incidents", headers={"X-Test-Actor": victim, "Idempotency-Key": key},
                            json={"is_test": True, "location": make_fix(0.0)})
    assert inc_res.status_code == 200, f"Incident create failed: {inc_res.status_code} - {inc_res.text}"
    incident_id = inc_res.json()["id"]

    inbox_a = client_1.get("/api/responders/invitations", headers={"X-Test-Actor": resp_a}).json()
    inbox_b = client_1.get("/api/responders/invitations", headers={"X-Test-Actor": resp_b}).json()
    assert len(inbox_a) >= 1
    assert len(inbox_b) >= 1

    inv_a = inbox_a[0]
    inv_b = inbox_b[0]

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        f_a = executor.submit(lambda: client_1.post(f"/api/responders/invitations/{inv_a['id']}/response",
                                                    headers={"X-Test-Actor": resp_a}, json={"decision": "ACCEPTED"}))
        f_b = executor.submit(lambda: client_1.post(f"/api/responders/invitations/{inv_b['id']}/response",
                                                    headers={"X-Test-Actor": resp_b}, json={"decision": "ACCEPTED"}))
        res_a = f_a.result()
        res_b = f_b.result()

    assert res_a.status_code == 200
    assert res_b.status_code == 200
    assert res_a.json()["status"] == "ACCEPTED"
    assert res_b.json()["status"] == "ACCEPTED"

    # Clean closure
    client_1.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": victim},
                  json={"pin": "839162", "confirm_pin": "839162"})
    client_1.post(f"/api/incidents/{incident_id}/resolve", headers={"X-Test-Actor": victim},
                  json={"pin": "839162"})
