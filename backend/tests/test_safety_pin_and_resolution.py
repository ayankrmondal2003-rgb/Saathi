"""Tests for Phase 8: Secure Safety PIN, Protected Incident Resolution, Duress Safeguards & Access Revocation."""
import os
from datetime import datetime, timedelta, timezone
from uuid import uuid4

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
        return Identity(prefix + actor, f"{actor}@example.invalid", actor, True, int(datetime.now(timezone.utc).timestamp()) - int(request.headers.get("X-Test-Auth-Age", "0")))

    app.dependency_overrides[identity] = who
    with TestClient(app) as client:
        yield client, prefix


def create_test_incident(client, actor="victim"):
    key = str(uuid4())
    resp = client.post(
        "/api/incidents",
        headers={"X-Test-Actor": actor, "Idempotency-Key": key},
        json={
            "is_test": True,
            "location": {
                "latitude": 28.6139,
                "longitude": 77.2090,
                "accuracy_meters": 5.0,
                "is_mock": True,
                "recorded_at": datetime.now(timezone.utc).isoformat()
            }
        }
    )
    assert resp.status_code == 200, resp.text
    return resp.json()["id"]


def test_safety_pin_enrollment_validation(client_env):
    client, _ = client_env

    # 1. Check initial status (no PIN enrolled)
    status_resp = client.get("/api/security/safety-pin/status", headers={"X-Test-Actor": "victim"})
    assert status_resp.status_code == 200
    assert status_resp.json()["has_primary_pin"] is False
    assert status_resp.json()["has_duress_pin"] is False

    # 2. Rejects non-6-digit PIN
    r = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                    json={"pin": "12345", "confirm_pin": "12345"})
    assert r.status_code == 422

    # 3. Rejects confirmation mismatch
    r = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                    json={"pin": "839162", "confirm_pin": "839163"})
    assert r.status_code == 400

    # 4. Rejects obvious repeated digits (e.g., 111111)
    r = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                    json={"pin": "111111", "confirm_pin": "111111"})
    assert r.status_code == 422
    assert "repeated digit" in r.text.lower()

    # 5. Rejects sequential ascending (e.g., 123456)
    r = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                    json={"pin": "123456", "confirm_pin": "123456"})
    assert r.status_code == 422
    assert "sequential" in r.text.lower()

    # 6. Rejects sequential descending (e.g., 654321)
    r = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                    json={"pin": "654321", "confirm_pin": "654321"})
    assert r.status_code == 422
    assert "reverse" in r.text.lower()

    # 7. Accepts strong, distinct PIN
    r = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                    json={"pin": "839162", "confirm_pin": "839162"})
    assert r.status_code == 200
    assert r.json()["status"] == "CONFIGURED"

    # Verify status updated
    status_resp = client.get("/api/security/safety-pin/status", headers={"X-Test-Actor": "victim"})
    assert status_resp.json()["has_primary_pin"] is True
    assert status_resp.json()["has_duress_pin"] is False


def test_duress_pin_setup_and_distinction(client_env):
    client, _ = client_env

    # Setup primary PIN
    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": "839162", "confirm_pin": "839162", "credential_type": "PRIMARY_PIN"})

    # Attempt to setup duress PIN identical to primary PIN -> Must be rejected!
    r = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                    json={"pin": "839162", "confirm_pin": "839162", "credential_type": "DURESS_PIN"})
    assert r.status_code == 422
    assert "distinct" in r.text.lower()

    # Setup strong distinct duress PIN
    r = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                    json={"pin": "941837", "confirm_pin": "941837", "credential_type": "DURESS_PIN"})
    assert r.status_code == 200

    status = client.get("/api/security/safety-pin/status", headers={"X-Test-Actor": "victim"}).json()
    assert status["has_primary_pin"] is True
    assert status["has_duress_pin"] is True


def test_pin_change_workflow(client_env):
    client, _ = client_env

    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": "839162", "confirm_pin": "839162"})

    # Change with wrong current PIN -> rejected (403)
    r = client.post("/api/security/safety-pin/change", headers={"X-Test-Actor": "victim"},
                    json={"current_pin": "000000", "new_pin": "941837", "confirm_pin": "941837"})
    assert r.status_code == 403

    # Change with identical new PIN -> rejected (422)
    r = client.post("/api/security/safety-pin/change", headers={"X-Test-Actor": "victim"},
                    json={"current_pin": "839162", "new_pin": "839162", "confirm_pin": "839162"})
    assert r.status_code == 422

    # Change with correct current PIN and new valid PIN -> success (200)
    r = client.post("/api/security/safety-pin/change", headers={"X-Test-Actor": "victim"},
                    json={"current_pin": "839162", "new_pin": "941837", "confirm_pin": "941837"})
    assert r.status_code == 200
    assert r.json()["status"] == "UPDATED"


def test_unenrolled_user_cannot_resolve(client_env):
    client, _ = client_env
    inc_id = create_test_incident(client, "victim_no_pin")

    # Attempt to resolve without setting up PIN
    r = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim_no_pin"},
                    json={"pin": "839162"})
    assert r.status_code == 400
    assert r.json()["detail"] == "Safety PIN not enrolled. Configure your Safety PIN in settings first."
    headers = {"X-Test-Actor": "victim_no_pin"}
    assert not client.get("/api/security/safety-pin/status", headers=headers).json()["has_primary_pin"]
    assert client.post("/api/security/safety-pin/setup", headers=headers,
                       json={"pin": "839162", "confirm_pin": "839162"}).status_code == 200
    assert client.get("/api/security/safety-pin/status", headers=headers).json()["has_primary_pin"]
    assert client.get(f"/api/incidents/{inc_id}/snapshot", headers=headers).json()["status"] == "ACTIVE"
    resolved = client.post(f"/api/incidents/{inc_id}/resolve", headers=headers, json={"pin": "839162"})
    assert resolved.json()["resolution_status"] == "CONFIRMED"


def test_non_owner_and_responder_cannot_resolve(client_env):
    client, _ = client_env
    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": "839162", "confirm_pin": "839162"})
    inc_id = create_test_incident(client, "victim")

    # Another user attempts resolution
    r = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "stranger"},
                    json={"pin": "839162"})
    assert r.status_code == 403
    assert "owner" in r.text.lower()


def test_incorrect_pin_rate_limiting_and_lockout(client_env):
    client, _ = client_env
    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": "839162", "confirm_pin": "839162"})
    inc_id = create_test_incident(client, "victim")

    # 4 incorrect attempts
    for i in range(4):
        r = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                        json={"pin": "999990"})
        assert r.status_code == 403
        assert "attempt(s) remaining" in r.text

    # 5th incorrect attempt -> triggers lockout (429)
    r = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                    json={"pin": "999990"})
    assert r.status_code == 429
    assert "locked" in r.text.lower()

    # Subsequent attempt even with correct PIN while locked -> rejected (429)
    r = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                    json={"pin": "839162"})
    assert r.status_code == 429

    # Incident MUST remain ACTIVE
    snapshot = client.get(f"/api/incidents/{inc_id}/snapshot", headers={"X-Test-Actor": "victim"})
    assert snapshot.json()["status"] == "ACTIVE"


def test_correct_pin_resolution_and_access_revocation(client_env):
    client, _ = client_env
    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": "839162", "confirm_pin": "839162"})
    inc_id = create_test_incident(client, "victim")

    # Responder opts in and accepts
    client.put("/api/users/me", headers={"X-Test-Actor": "resp1"},
               json={"display_name": "Resp 1", "responder_opt_in": True, "responder_available": True})
    now = (datetime.now(timezone.utc) + timedelta(seconds=1)).isoformat()
    client.put("/api/responders/location", headers={"X-Test-Actor": "resp1"},
               json={"latitude": 28.6140, "longitude": 77.2091, "accuracy_meters": 5.0, "is_mock": True, "recorded_at": now})

    # Trigger fix to create invitation
    fix_time = (datetime.now(timezone.utc) + timedelta(seconds=2)).isoformat()
    client.post(f"/api/incidents/{inc_id}/location", headers={"X-Test-Actor": "victim"},
                json={"latitude": 28.6145, "longitude": 77.2095, "accuracy_meters": 5.0, "is_mock": True, "recorded_at": fix_time})

    invs = client.get("/api/responders/invitations", headers={"X-Test-Actor": "resp1"}).json()
    target_inv = next((inv for inv in invs if str(inv["incident_id"]) == str(inc_id)), None)
    assert target_inv is not None, f"Expected invitation for {inc_id} in {invs}"
    inv_id = target_inv["id"]
    accept_resp = client.post(f"/api/responders/invitations/{inv_id}/response", headers={"X-Test-Actor": "resp1"},
                              json={"decision": "ACCEPTED"})
    assert accept_resp.status_code == 200
    assert accept_resp.json()["status"] == "ACCEPTED"

    # Responder can access location before closure
    loc_before = client.get(f"/api/incidents/{inc_id}/location", headers={"X-Test-Actor": "resp1"})
    assert loc_before.status_code == 200

    # Victim resolves incident with correct Safety PIN
    resolve_resp = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                               json={"pin": "839162", "reason": "FALSE_ALARM"})
    assert resolve_resp.status_code == 200
    data = resolve_resp.json()
    assert data["status"] == "CLOSED"
    assert data["resolution_status"] == "CONFIRMED"
    assert data["ended_at"] is not None

    # Idempotent re-resolution returns closed status
    resolve_repeat = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                                 json={"pin": "839162"})
    assert resolve_repeat.status_code == 200
    assert resolve_repeat.json()["status"] == "CLOSED"

    # Closed incident rejects new location fixes
    stale_fix = client.post(f"/api/incidents/{inc_id}/location", headers={"X-Test-Actor": "victim"},
                            json={"latitude": 28.6142, "longitude": 77.2093, "accuracy_meters": 5.0, "is_mock": True,
                                  "recorded_at": datetime.now(timezone.utc).isoformat()})
    assert stale_fix.status_code == 409

    # Responder loses tracking authorization (404 Incident unavailable)
    loc_after = client.get(f"/api/incidents/{inc_id}/location", headers={"X-Test-Actor": "resp1"})
    assert loc_after.status_code == 404

    # WebSocket ticket issuance on closed incident is rejected (404/409)
    ws_ticket = client.post(f"/api/incidents/{inc_id}/ws-ticket", headers={"X-Test-Actor": "resp1"})
    assert ws_ticket.status_code in (404, 409)


def test_duress_pin_simulation(client_env):
    client, _ = client_env
    # Setup primary PIN and duress PIN
    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": "839162", "confirm_pin": "839162", "credential_type": "PRIMARY_PIN"})
    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": "941837", "confirm_pin": "941837", "credential_type": "DURESS_PIN"})

    inc_id = create_test_incident(client, "victim")

    # Enter Duress PIN
    r = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                    json={"pin": "941837"})
    assert r.status_code == 200
    data = r.json()
    assert data["resolution_status"] == "DURESS_SIMULATION"
    assert data["status"] in ("ACTIVE", "ESCALATION_REQUIRED")

    # Incident MUST NOT be closed! Live tracking continues
    snapshot = client.get(f"/api/incidents/{inc_id}/snapshot", headers={"X-Test-Actor": "victim"}).json()
    assert snapshot["status"] in ("ACTIVE", "ESCALATION_REQUIRED")


def test_pin_recovery_does_not_close_incident(client_env):
    client, _ = client_env
    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": "839162", "confirm_pin": "839162"})
    inc_id = create_test_incident(client, "victim")

    repeated = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                           json={"pin": "716253", "confirm_pin": "716253"})
    assert repeated.status_code == 409

    stale = client.post("/api/security/safety-pin/recover",
                        headers={"X-Test-Actor": "victim", "X-Test-Auth-Age": "600"},
                        json={"new_pin": "716253", "confirm_pin": "716253"})
    assert stale.status_code == 403
    # Perform PIN recovery
    r = client.post("/api/security/safety-pin/recover", headers={"X-Test-Actor": "victim"},
                    json={"new_pin": "716253", "confirm_pin": "716253"})
    assert r.status_code == 200

    throttled = client.post("/api/security/safety-pin/recover", headers={"X-Test-Actor": "victim"},
                            json={"new_pin": "836052", "confirm_pin": "836052"})
    assert throttled.status_code == 429

    # Incident remains active
    snapshot = client.get(f"/api/incidents/{inc_id}/snapshot", headers={"X-Test-Actor": "victim"}).json()
    assert snapshot["status"] == "ACTIVE"

    # Old PIN no longer works
    r_old = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                        json={"pin": "839162"})
    assert r_old.status_code == 403

    # New recovered PIN closes incident
    r_new = client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                        json={"pin": "716253"})
    assert r_new.status_code == 200
    assert r_new.json()["status"] == "CLOSED"


def test_no_plaintext_pin_in_database(client_env):
    import asyncio
    import asyncpg

    client, prefix = client_env
    secret_pin = "839162"
    client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "victim"},
                json={"pin": secret_pin, "confirm_pin": secret_pin})
    inc_id = create_test_incident(client, "victim")

    # Test an attempt
    client.post(f"/api/incidents/{inc_id}/resolve", headers={"X-Test-Actor": "victim"},
                json={"pin": secret_pin})

    # Direct database query to verify no plaintext PIN exists
    url = os.getenv("TEST_DATABASE_URL").replace("postgresql+asyncpg://", "postgresql://")

    async def verify_db():
        conn = await asyncpg.connect(url)
        try:
            # Query safety_credentials
            rows = await conn.fetch("SELECT pin_hash FROM safety_credentials")
            for r in rows:
                assert secret_pin not in r["pin_hash"]
                assert r["pin_hash"].startswith("$argon2id$")

            # Query incident_resolution_attempts metadata
            attempt_rows = await conn.fetch("SELECT attempt_result, metadata FROM incident_resolution_attempts")
            for r in attempt_rows:
                meta_str = str(r["metadata"])
                assert secret_pin not in meta_str
        finally:
            await conn.close()

    asyncio.run(verify_db())
