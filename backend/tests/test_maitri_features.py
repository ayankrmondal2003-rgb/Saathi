"""Comprehensive Server-Based Maitri Feature Test Suite.
Verifies real FastAPI endpoints, isolated PostgreSQL/PostGIS database transactions,
Redis pub/sub & WebSockets, safety guard enforcement, and Android API contracts.
Never connects to live Render or production database.
"""
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import re
from uuid import UUID, uuid4

import pytest
from fastapi import Request
from sqlalchemy import text
from sqlalchemy.engine import make_url
from starlette.testclient import TestClient

from app.config import Settings
from app.core import Identity, identity
from app.main import create_app
from app.safety_guard import SafetyViolationError, assert_safe_test_environment
from app.services.geofence import tick
from app.services.outbox_worker import FcmSendResult, process_outbox_batch
from app.services.realtime import RealtimeHub

# --- Synthetic Test Identities ---
ACTORS = {
    "VICTIM_A": {
        "uid": "uid-victim-a-001",
        "email": "victim_a@example.invalid",
        "display_name": "Victim A",
    },
    "RESPONDER_B": {
        "uid": "uid-responder-b-002",
        "email": "responder_b@example.invalid",
        "display_name": "Responder B",
    },
    "TRUSTED_CONTACT_C": {
        "uid": "uid-contact-c-003",
        "email": "contact_c@example.invalid",
        "display_name": "Trusted Contact C",
    },
    "UNAUTHORIZED_USER_D": {
        "uid": "uid-attacker-d-004",
        "email": "attacker_d@example.invalid",
        "display_name": "Unauthorized User D",
    },
}


def make_fix(lat=28.6139, lon=77.2090, accuracy=5.0, seconds=0, is_mock=True):
    now = datetime.now(timezone.utc) + timedelta(seconds=seconds)
    return {
        "latitude": lat,
        "longitude": lon,
        "accuracy_meters": accuracy,
        "recorded_at": now.isoformat(),
        "is_mock": is_mock,
    }


@pytest.fixture
def maitri_env():
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.skip("Isolated PostGIS database required (set TEST_DATABASE_URL)")

    # Assert safe environment before any execution
    assert_safe_test_environment(url, env_name="testing")
    assert make_url(url).database.endswith("_test")

    # Use a unique prefix per test run so tests do not interfere with each other
    prefix = uuid4().hex[:8] + "-"

    settings = Settings(
        _env_file=None,
        database_url=url,
        firebase_project_id="maitri-test-project",
        environment="test",
        min_search_interval_seconds=0,
    )
    settings.test_account_uids = {prefix + meta["uid"] for meta in ACTORS.values()}
    app = create_app(settings, initialize_firebase=False)

    def test_identity(request: Request):
        actor_key = request.headers.get("X-Test-Actor", "VICTIM_A")
        meta = ACTORS.get(actor_key, ACTORS["VICTIM_A"])
        auth_age = int(request.headers.get("X-Test-Auth-Age", "0"))
        auth_time = int(datetime.now(timezone.utc).timestamp()) - auth_age
        return Identity(prefix + meta["uid"], prefix + meta["email"], meta["display_name"], True, auth_time)

    app.dependency_overrides[identity] = test_identity

    test_hub = RealtimeHub(worker_id="maitri-test-worker")
    app.state.hub = test_hub

    with TestClient(app) as client:
        yield client, client.app.state.sessions, settings, prefix


# ==============================================================================
# PHASE 2: ENVIRONMENT SAFETY GUARD TESTS
# ==============================================================================
def test_environment_safety_guard_enforcement():
    # 1. Non-testing environment rejected
    with pytest.raises(SafetyViolationError) as exc:
        assert_safe_test_environment("postgresql+asyncpg://localhost/test_db", env_name="production")
    assert "ENVIRONMENT must be 'test' or 'testing'" in str(exc.value)

    # 2. Production host in DB URL rejected
    with pytest.raises(SafetyViolationError) as exc:
        assert_safe_test_environment("postgresql+asyncpg://user:pass@dpg-xxx.oregon-postgres.render.com/surakshanet_db", env_name="testing")
    assert "matches production pattern" in str(exc.value)

    # 3. Non-test-scoped DB name rejected
    with pytest.raises(SafetyViolationError) as exc:
        assert_safe_test_environment("postgresql+asyncpg://localhost/production_db", env_name="testing")
    assert "not explicitly test-scoped" in str(exc.value)

    # 4. Production host in Redis URL rejected
    with pytest.raises(SafetyViolationError) as exc:
        assert_safe_test_environment("postgresql+asyncpg://localhost/my_test", redis_url="rediss://prod.redis.cache.windows.net:6380", env_name="testing")
    assert "matches production pattern" in str(exc.value)

    # 5. Valid local test environment accepted
    assert assert_safe_test_environment("postgresql+asyncpg://test_admin:pass@127.0.0.1:5432/surakshanet_test", env_name="testing") is True


# ==============================================================================
# PHASE 4: AUTHENTICATION AND ACCOUNT ISOLATION TESTS
# ==============================================================================
def test_authentication_and_profile_isolation(maitri_env):
    client, sessions, _, prefix = maitri_env

    # 1. Profile retrieval for VICTIM_A
    r_a = client.get("/api/users/me", headers={"X-Test-Actor": "VICTIM_A"})
    assert r_a.status_code == 200
    data_a = r_a.json()
    assert data_a["email"] == prefix + ACTORS["VICTIM_A"]["email"]
    assert data_a["display_name"] == ACTORS["VICTIM_A"]["display_name"]

    # 2. Profile update for VICTIM_A
    r_up = client.put("/api/users/me", headers={"X-Test-Actor": "VICTIM_A"},
                      json={"display_name": "Victim Updated", "responder_opt_in": False, "responder_available": False})
    assert r_up.status_code == 200
    assert r_up.json()["display_name"] == "Victim Updated"

    # 3. Profile retrieval for UNAUTHORIZED_USER_D shows separate identity
    r_d = client.get("/api/users/me", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"})
    assert r_d.status_code == 200
    assert r_d.json()["display_name"] == ACTORS["UNAUTHORIZED_USER_D"]["display_name"]

    # 4. Responder opt-in constraint: available without opt-in must fail with 422
    r_bad_opt = client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
                           json={"display_name": "Responder B", "responder_available": True, "responder_opt_in": False})
    assert r_bad_opt.status_code == 422


# ==============================================================================
# PHASE 5: TRUSTED CIRCLE CRUD & OWNERSHIP TESTS
# ==============================================================================
def test_trusted_circle_full_lifecycle(maitri_env):
    client, _, _, _ = maitri_env

    # 1. Initially empty
    assert client.get("/api/trusted-contacts", headers={"X-Test-Actor": "VICTIM_A"}).json() == []

    # 2. Add contact
    c_resp = client.post("/api/trusted-contacts", headers={"X-Test-Actor": "VICTIM_A"}, json={
        "display_name": "Sister C",
        "phone_number": "+91 98765 43210",
        "label": "Sister",
        "is_primary": False,
        "notify_on_sos": True,
        "allow_live_tracking": True
    })
    assert c_resp.status_code == 201
    contact = c_resp.json()
    contact_id = contact["id"]
    # First contact automatically primary
    assert contact["is_primary"] is True
    assert contact["display_name"] == "Sister C"

    # 3. Update contact
    up_resp = client.put(f"/api/trusted-contacts/{contact_id}", headers={"X-Test-Actor": "VICTIM_A"}, json={
        "display_name": "Sister C Updated",
        "phone_number": "+91 98765 43210",
        "label": "Guardian",
        "is_primary": True,
        "notify_on_sos": False,
        "allow_live_tracking": True
    })
    assert up_resp.status_code == 200
    assert up_resp.json()["display_name"] == "Sister C Updated"
    assert up_resp.json()["label"] == "Guardian"
    assert up_resp.json()["notify_on_sos"] is False

    # 4. Cross-account access denied: UNAUTHORIZED_USER_D cannot update or delete VICTIM_A's contact
    assert client.put(f"/api/trusted-contacts/{contact_id}", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"},
                      json={"display_name": "Hacked", "phone_number": "+91 00000 00000", "is_primary": False, "notify_on_sos": True, "allow_live_tracking": True}).status_code == 404
    assert client.delete(f"/api/trusted-contacts/{contact_id}", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}).status_code == 404

    # 5. Delete contact by owner
    assert client.delete(f"/api/trusted-contacts/{contact_id}", headers={"X-Test-Actor": "VICTIM_A"}).status_code == 204
    assert client.get("/api/trusted-contacts", headers={"X-Test-Actor": "VICTIM_A"}).json() == []


# ==============================================================================
# PHASE 6: PEER-TO-PEER PAIRING WORKFLOW TESTS
# ==============================================================================
def pair_accounts(client, prefix, owner="VICTIM_A", recipient="TRUSTED_CONTACT_C"):
    # Ensure both user rows exist
    client.get("/api/users/me", headers={"X-Test-Actor": owner})
    client.get("/api/users/me", headers={"X-Test-Actor": recipient})

    # Owner creates a trusted contact record for recipient
    contact = client.post("/api/trusted-contacts", headers={"X-Test-Actor": owner}, json={
        "display_name": ACTORS[recipient]["display_name"],
        "phone_number": "+91 98765 00001",
        "allow_live_tracking": True
    }).json()

    # Recipient consents and requests pairing code for owner's email
    code_resp = client.post("/api/circle-sharing/pairing-code", headers={"X-Test-Actor": recipient}, json={
        "owner_email": prefix + ACTORS[owner]["email"],
        "consent": True
    })
    assert code_resp.status_code == 200, code_resp.text
    pairing_code = code_resp.json()["code"]

    # Owner binds the link using the pairing code and contact ID
    bind_resp = client.post("/api/circle-sharing/links", headers={"X-Test-Actor": owner}, json={
        "contact_id": contact["id"],
        "code": pairing_code
    })
    assert bind_resp.status_code == 200, bind_resp.text
    assert bind_resp.json()["status"] == "PAIRED"

    # Second consumption of the same pairing code must fail with 404
    assert client.post("/api/circle-sharing/links", headers={"X-Test-Actor": owner}, json={
        "contact_id": contact["id"],
        "code": pairing_code
    }).status_code == 404

    return contact["id"], bind_resp.json()["id"]


def test_peer_to_peer_pairing_workflow(maitri_env):
    client, _, _, prefix = maitri_env
    contact_id, link_id = pair_accounts(client, prefix, "VICTIM_A", "TRUSTED_CONTACT_C")

    # Verify link visible to owner
    owner_links = client.get("/api/circle-sharing/links", headers={"X-Test-Actor": "VICTIM_A"}).json()
    assert any(l["id"] == link_id and l["is_owner"] is True for l in owner_links)

    # Verify link visible to recipient
    recipient_links = client.get("/api/circle-sharing/links", headers={"X-Test-Actor": "TRUSTED_CONTACT_C"}).json()
    assert any(l["id"] == link_id and l["is_owner"] is False for l in recipient_links)

    # UNAUTHORIZED_USER_D sees no links
    d_links = client.get("/api/circle-sharing/links", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}).json()
    assert not any(l["id"] == link_id for l in d_links)

    # Revocation by owner
    rev_resp = client.delete(f"/api/circle-sharing/links/{link_id}", headers={"X-Test-Actor": "VICTIM_A"})
    assert rev_resp.status_code == 200
    assert rev_resp.json()["status"] == "REVOKED"


# ==============================================================================
# PHASE 7: SHARE MY TRIP COMPLETE FLOW (HIGH PRIORITY)
# ==============================================================================
def test_share_my_trip_complete_flow(maitri_env):
    client, sessions, _, prefix = maitri_env

    # 1. Pair VICTIM_A and TRUSTED_CONTACT_C
    contact_id, link_id = pair_accounts(client, prefix, "VICTIM_A", "TRUSTED_CONTACT_C")

    # 2. VICTIM_A creates trip session with consent and initial GPS fix
    initial_fix = make_fix(lat=22.5726, lon=88.3639, accuracy=6.0)
    idempotency_key = str(uuid4())
    create_resp = client.post(
        "/api/circle-sharing/sessions",
        headers={"X-Test-Actor": "VICTIM_A", "Idempotency-Key": idempotency_key},
        json={
            "kind": "TRIP",
            "contact_ids": [contact_id],
            "consent": True,
            "location": initial_fix,
            "duration_minutes": 30
        }
    )
    assert create_resp.status_code == 200, create_resp.text
    session = create_resp.json()
    session_id = session["id"]
    assert session["kind"] == "TRIP"
    assert session["status"] == "ACTIVE"
    assert session["latest_fix"]["latitude"] == 22.5726
    assert session["recipients"][0]["notification_status"] == "AVAILABLE_IN_APP"

    # 3. Authorized recipient (TRUSTED_CONTACT_C) retrieves session and location
    r_view = client.get(f"/api/circle-sharing/sessions/{session_id}", headers={"X-Test-Actor": "TRUSTED_CONTACT_C"})
    assert r_view.status_code == 200
    assert r_view.json()["latest_fix"]["latitude"] == 22.5726

    # 4. UNAUTHORIZED_USER_D denied access (HTTP 404)
    r_unauth = client.get(f"/api/circle-sharing/sessions/{session_id}", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"})
    assert r_unauth.status_code == 404

    # 5. VICTIM_A updates location; recipient sees new location
    updated_fix = make_fix(lat=22.5750, lon=88.3660, accuracy=5.0, seconds=0)
    put_fix = client.put(f"/api/circle-sharing/sessions/{session_id}/location", headers={"X-Test-Actor": "VICTIM_A"}, json=updated_fix)
    assert put_fix.status_code == 200

    r_view2 = client.get(f"/api/circle-sharing/sessions/{session_id}", headers={"X-Test-Actor": "TRUSTED_CONTACT_C"})
    assert r_view2.status_code == 200
    assert r_view2.json()["latest_fix"]["latitude"] == 22.5750

    # 6. Recipient cannot update location (HTTP 404)
    assert client.put(f"/api/circle-sharing/sessions/{session_id}/location", headers={"X-Test-Actor": "TRUSTED_CONTACT_C"}, json=updated_fix).status_code == 404

    # 7. VICTIM_A stops trip and revokes access
    stop_resp = client.post(f"/api/circle-sharing/sessions/{session_id}/stop", headers={"X-Test-Actor": "VICTIM_A"})
    assert stop_resp.status_code == 200

    # 8. Recipient access revoked immediately (HTTP 404)
    assert client.get(f"/api/circle-sharing/sessions/{session_id}", headers={"X-Test-Actor": "TRUSTED_CONTACT_C"}).status_code == 404

    # 9. Idempotency test: Replay with same key and body returns snapshot
    replay_resp = client.post(
        "/api/circle-sharing/sessions",
        headers={"X-Test-Actor": "VICTIM_A", "Idempotency-Key": idempotency_key},
        json={
            "kind": "TRIP",
            "contact_ids": [contact_id],
            "consent": True,
            "location": initial_fix,
            "duration_minutes": 30
        }
    )
    assert replay_resp.status_code == 200
    assert replay_resp.json()["id"] == session_id

    # 10. Database verification: Confirm NO emergency incident was created
    async def verify_db():
        async with sessions() as db:
            # Scoped to this test's owner: other tests share the database and leave their own incidents.
            count = await db.scalar(text("SELECT count(*) FROM incidents i JOIN users u ON u.id = i.victim_user_id "
                                         "WHERE u.firebase_uid LIKE :prefix"), {"prefix": prefix + "%"})
            return count
    assert client.portal.call(verify_db) == 0, "No emergency incident should be created during circle trip sharing!"


# ==============================================================================
# PHASE 8: CHECK-IN TESTS & IN-APP INBOX
# ==============================================================================
def test_check_in_lifecycle_and_inbox(maitri_env):
    client, _, _, prefix = maitri_env
    contact_id, _ = pair_accounts(client, prefix, "VICTIM_A", "TRUSTED_CONTACT_C")

    # 1. VICTIM_A creates check-in (no location included)
    idempotency_key = str(uuid4())
    resp = client.post(
        "/api/circle-sharing/sessions",
        headers={"X-Test-Actor": "VICTIM_A", "Idempotency-Key": idempotency_key},
        json={
            "kind": "CHECK_IN",
            "contact_ids": [contact_id],
            "consent": True
        }
    )
    assert resp.status_code == 200
    checkin = resp.json()
    assert checkin["kind"] == "CHECK_IN"
    assert checkin["latest_fix"] is None

    session_id = checkin["id"]

    # 2. Recipient views inbox
    inbox = client.get("/api/circle-sharing/sessions", headers={"X-Test-Actor": "TRUSTED_CONTACT_C"}).json()
    assert any(s["id"] == session_id and s["kind"] == "CHECK_IN" for s in inbox)

    # 3. Recipient opens check-in -> triggers read receipt
    opened_resp = client.post(f"/api/circle-sharing/sessions/{session_id}/opened", headers={"X-Test-Actor": "TRUSTED_CONTACT_C"})
    assert opened_resp.status_code == 200

    # 4. Owner observes read receipt update to OPENED
    owner_view = client.get(f"/api/circle-sharing/sessions/{session_id}", headers={"X-Test-Actor": "VICTIM_A"}).json()
    assert owner_view["recipients"][0]["notification_status"] == "OPENED"
    assert owner_view["recipients"][0]["opened_at"] is not None

    # 5. UNAUTHORIZED_USER_D cannot view or open check-in
    assert client.get(f"/api/circle-sharing/sessions/{session_id}", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}).status_code == 404
    assert client.post(f"/api/circle-sharing/sessions/{session_id}/opened", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}).status_code == 404


# ==============================================================================
# PHASE 9: VOLUNTEER APPLICATION TESTS
# ==============================================================================
def test_volunteer_application_lifecycle(maitri_env):
    client, _, _, _ = maitri_env

    # 1. Status default: NOT_STARTED
    res = client.get("/api/volunteer/status", headers={"X-Test-Actor": "RESPONDER_B"})
    assert res.status_code == 200
    assert res.json()["status"] == "NOT_STARTED"

    # 2. Intent: HELP -> transitions to DRAFT
    res_help = client.post("/api/volunteer/intent", headers={"X-Test-Actor": "RESPONDER_B"}, json={"intent": "HELP"})
    assert res_help.status_code == 200
    assert client.get("/api/volunteer/status", headers={"X-Test-Actor": "RESPONDER_B"}).json()["status"] == "DRAFT"

    # 3. Intent: NOT_NOW -> transitions back to NOT_STARTED without responder enrollment
    res_skip = client.post("/api/volunteer/intent", headers={"X-Test-Actor": "RESPONDER_B"}, json={"intent": "NOT_NOW"})
    assert res_skip.status_code == 200
    assert client.get("/api/volunteer/status", headers={"X-Test-Actor": "RESPONDER_B"}).json()["status"] == "NOT_STARTED"

    # Re-engage HELP
    client.post("/api/volunteer/intent", headers={"X-Test-Actor": "RESPONDER_B"}, json={"intent": "HELP"})

    # 4. Consent update
    res_consent = client.post("/api/volunteer/consent", headers={"X-Test-Actor": "RESPONDER_B"}, json={"consent_declared": True})
    assert res_consent.status_code == 200
    assert client.get("/api/volunteer/status", headers={"X-Test-Actor": "RESPONDER_B"}).json()["consent_declared"] is True

    # 5. Document upload stays blocked (503) while private object storage is not configured
    upload_res = client.post("/api/volunteer/upload-document", headers={"X-Test-Actor": "RESPONDER_B"}, json={
        "doc_type": "GOVT_ID",
        "file_name": "id_card.pdf",
        "file_size_bytes": 10240,
        "mime_type": "application/pdf"
    })
    assert upload_res.status_code == 503
    assert upload_res.json()["detail"]["code"] == "STORAGE_NOT_CONFIGURED"

    # 6. Submit attempt without required 5 documents fails with 422
    submit_res = client.post("/api/volunteer/submit", headers={"X-Test-Actor": "RESPONDER_B"})
    assert submit_res.status_code == 422
    assert "Missing required documents" in submit_res.json()["detail"]


# ==============================================================================
# PHASE 10: SOS INCIDENT LIFECYCLE TESTS
# ==============================================================================
def create_test_sos_incident(client, actor="VICTIM_A"):
    key = str(uuid4())
    resp = client.post(
        "/api/incidents",
        headers={"X-Test-Actor": actor, "Idempotency-Key": key},
        json={
            "is_test": True,
            "location": make_fix(lat=28.6139, lon=77.2090, accuracy=5.0)
        }
    )
    assert resp.status_code == 200, resp.text
    return resp.json()["id"]


def test_sos_incident_lifecycle(maitri_env):
    client, _, _, _ = maitri_env
    incident_id = create_test_sos_incident(client, "VICTIM_A")

    # 1. Owner can view location
    loc_resp = client.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "VICTIM_A"})
    assert loc_resp.status_code == 200
    assert loc_resp.json()["latitude"] == 28.6139

    # 2. UNAUTHORIZED_USER_D denied location (HTTP 404)
    assert client.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}).status_code == 404

    # 3. Location update with fresh fix
    new_fix = make_fix(lat=28.6145, lon=77.2095, accuracy=4.0, seconds=1)
    update_res = client.post(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "VICTIM_A"}, json=new_fix)
    assert update_res.status_code == 204

    # 4. Stale fix (older recorded_at) is rejected with 409
    stale_fix = make_fix(lat=28.6140, lon=77.2090, accuracy=4.0, seconds=-50)
    assert client.post(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "VICTIM_A"}, json=stale_fix).status_code == 409


# ==============================================================================
# PHASE 11: MOVING 1 KM GEOFENCE & RESPONDER INVITATION TESTS
# ==============================================================================
def test_moving_geofence_and_responder_invitation(maitri_env):
    client, sessions, settings, _ = maitri_env

    # 1. Enable responder B (within 500m of incident)
    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})
    # Set Responder B location at (28.6150, 77.2090) ~120m away from Victim A
    resp_fix = client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"},
                          json=make_fix(lat=28.6150, lon=77.2090, accuracy=5.0))
    assert resp_fix.status_code == 204

    # 2. Victim A creates incident at (28.6139, 77.2090)
    incident_id = create_test_sos_incident(client, "VICTIM_A")

    # 3. Run geofence evaluation tick via client.portal
    async def run_tick():
        async with sessions.begin() as db:
            await tick(db, settings)
    client.portal.call(run_tick)

    # 4. Responder B checks invitations inbox
    invitations = client.get("/api/responders/invitations", headers={"X-Test-Actor": "RESPONDER_B"}).json()
    assert len(invitations) >= 1
    invitation = next((i for i in invitations if i["incident_id"] == incident_id), None)
    assert invitation is not None
    invitation_id = invitation["id"]

    help_url = f"/api/incidents/{incident_id}/request-help"
    responder = {"X-Test-Actor": "RESPONDER_B"}
    assert client.post(help_url, headers=responder).status_code == 404
    assert client.post(help_url, headers={"X-Test-Actor": "VICTIM_A"}).status_code == 403

    # 5. Responder B accepts invitation
    accept_res = client.post(f"/api/responders/invitations/{invitation_id}/response",
                             headers={"X-Test-Actor": "RESPONDER_B"}, json={"decision": "ACCEPTED"})
    assert accept_res.status_code == 200
    assert accept_res.json()["status"] == "ACCEPTED"

    help_response = client.post(help_url, headers=responder)
    assert help_response.status_code == 200
    assert help_response.json() == {"queued": 0}
    assert client.post(help_url, headers=responder).json() == {"queued": 0}

    # 6. Accepted responder B now has authorized access to incident location
    r_loc = client.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "RESPONDER_B"})
    assert r_loc.status_code == 200

    # 7. UNAUTHORIZED_USER_D still denied
    assert client.get(f"/api/incidents/{incident_id}/location", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}).status_code == 404


# ==============================================================================
# PHASE 13: LIVE TRACKING & WEBSOCKET TICKET TESTS
# ==============================================================================
def test_realtime_websocket_ticket_and_snapshot(maitri_env):
    client, _, _, _ = maitri_env
    incident_id = create_test_sos_incident(client, "VICTIM_A")

    # 1. Owner requests ws-ticket
    ticket_owner = client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "VICTIM_A"})
    assert ticket_owner.status_code == 200
    assert ticket_owner.json()["role"] == "OWNER"
    assert "ticket" in ticket_owner.json()

    # 2. Unauthorized user denied ticket (HTTP 404)
    assert client.post(f"/api/incidents/{incident_id}/ws-ticket", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}).status_code == 404

    # 3. Snapshot retrieval by owner
    snap = client.get(f"/api/incidents/{incident_id}/snapshot", headers={"X-Test-Actor": "VICTIM_A"})
    assert snap.status_code == 200
    assert snap.json()["incident_id"] == incident_id


# ==============================================================================
# PHASE 14: SAFETY PIN TESTS & INCIDENT RESOLUTION
# ==============================================================================
def test_safety_pin_and_incident_resolution(maitri_env):
    client, sessions, _, _ = maitri_env

    # 1. Setup Safety PIN for VICTIM_A (must be strong 6 digits, non-sequential)
    setup_res = client.post("/api/security/safety-pin/setup", headers={"X-Test-Actor": "VICTIM_A"}, json={
        "pin": "839162",
        "confirm_pin": "839162",
        "credential_type": "PRIMARY_PIN"
    })
    assert setup_res.status_code == 200
    assert setup_res.json()["status"] == "CONFIGURED"

    # Status check
    st = client.get("/api/security/safety-pin/status", headers={"X-Test-Actor": "VICTIM_A"}).json()
    assert st["has_primary_pin"] is True

    # 2. Create incident
    incident_id = create_test_sos_incident(client, "VICTIM_A")

    # 3. Attempt resolution with incorrect PIN -> must fail with 403 Forbidden
    bad_res = client.post(f"/api/incidents/{incident_id}/resolve", headers={"X-Test-Actor": "VICTIM_A"}, json={
        "pin": "774921",
        "reason": "USER_RESOLVED"
    })
    assert bad_res.status_code == 403
    assert "Incorrect Safety PIN" in bad_res.json()["detail"]

    # Verify incident remains ACTIVE in DB
    async def check_active():
        async with sessions() as db:
            row = (await db.execute(text("SELECT status FROM incidents WHERE id = :id"), {"id": incident_id})).mappings().first()
            return row["status"]
    assert client.portal.call(check_active) == "ACTIVE"

    # 4. Resolve incident with correct PIN -> succeeds and marks CLOSED / RESOLVED
    good_res = client.post(f"/api/incidents/{incident_id}/resolve", headers={"X-Test-Actor": "VICTIM_A"}, json={
        "pin": "839162",
        "reason": "USER_RESOLVED"
    })
    assert good_res.status_code == 200
    assert good_res.json()["status"] == "CLOSED"
    assert good_res.json()["resolution_status"] == "CONFIRMED"

    # Verify incident is CLOSED in DB
    async def check_resolved():
        async with sessions() as db:
            row = (await db.execute(text("SELECT status FROM incidents WHERE id = :id"), {"id": incident_id})).mappings().first()
            return row["status"]
    assert client.portal.call(check_resolved) == "CLOSED"


# ==============================================================================
# PHASE 15: FCM LOCAL ISOLATED OUTBOX VERIFICATION (MODE A)
# ==============================================================================
def test_fcm_outbox_processing_local_mode(maitri_env):
    client, sessions, settings, _ = maitri_env

    # 1. Register device for RESPONDER_B
    device_token = "test-fcm-token-" + uuid4().hex
    reg = client.post("/api/devices/register", headers={"X-Test-Actor": "RESPONDER_B"}, json={
        "fcm_token": device_token,
        "platform": "android",
        "device_identifier": "samsung-galaxy-test"
    })
    assert reg.status_code == 200

    # 2. Enable responder B and set location nearby
    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})
    client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"},
               json=make_fix(lat=28.6150, lon=77.2090, accuracy=5.0))

    # 3. Victim A creates test incident
    incident_id = create_test_sos_incident(client, "VICTIM_A")

    # 4. Geofence tick discovers responder B and queues outbox invitation
    async def run_tick():
        async with sessions.begin() as db:
            await tick(db, settings)
    client.portal.call(run_tick)

    # 5. Capture FCM dispatch via fake sender
    captured_payloads = []

    def mock_fcm_sender(tokens, title, body, data, firebase_app):
        captured_payloads.append({"tokens": tokens, "title": title, "body": body, "data": data})
        return [FcmSendResult(token=t, message_id="fcm-msg-id-123") for t in tokens]

    async def run_worker():
        return await process_outbox_batch(sessions, firebase_app=None, fcm_sender=mock_fcm_sender, allowed_uids=settings.test_account_uids)

    processed_count = client.portal.call(run_worker)
    assert processed_count >= 1, f"Expected at least 1 notification claimed, got {processed_count}"
    assert len(captured_payloads) >= 1

    # Privacy assertion: payload must NOT leak coordinates
    sent_data = captured_payloads[0]["data"]
    assert "latitude" not in sent_data
    assert "longitude" not in sent_data


# ==============================================================================
# PHASE 16: ANDROID API CONTRACT CONSISTENCY VERIFICATION
# ==============================================================================
def get_all_fastapi_routes(router, prefix=""):
    routes = []
    for r in router.routes:
        if hasattr(r, "original_router"):
            sub_prefix = prefix + (r.include_context.prefix if hasattr(r, "include_context") else "")
            routes.extend(get_all_fastapi_routes(r.original_router, sub_prefix))
        elif hasattr(r, "path"):
            methods = getattr(r, "methods", set())
            routes.append((methods, prefix + r.path))
    return routes


def test_android_api_contract_consistency(maitri_env):
    """Parses Android BackendApi.kt and verifies every declared endpoint matches a FastAPI route."""
    client, _, _, _ = maitri_env
    app = client.app

    registered_routes = []
    for methods, path in get_all_fastapi_routes(app):
        for m in methods:
            registered_routes.append((m, path))

    # Read Android BackendApi.kt
    backend_api_path = os.path.join(
        os.path.dirname(__file__), "..", "..", "app", "src", "main", "java", "in", "maitri", "app", "network", "BackendApi.kt"
    )
    assert os.path.exists(backend_api_path), f"BackendApi.kt not found at {backend_api_path}"

    with open(backend_api_path, "r", encoding="utf-8") as f:
        content = f.read()

    pattern = re.compile(r'@(GET|POST|PUT|DELETE)\("([^"]+)"\)')
    matches = pattern.findall(content)

    assert len(matches) > 10, "Should have discovered Android Retrofit annotations"

    mismatches = []
    for method, path in matches:
        normalized_path = "/" + path.lstrip("/")
        matched = False
        android_norm = re.sub(r'\{[^\}]+\}', '{var}', normalized_path)
        for reg_method, reg_path in registered_routes:
            if method == reg_method:
                reg_norm = re.sub(r'\{[^\}]+\}', '{var}', reg_path)
                if reg_norm == android_norm:
                    matched = True
                    break
        if not matched:
            mismatches.append(f"{method} {normalized_path}")

    assert not mismatches, f"Android Retrofit endpoints missing on FastAPI server: {mismatches}"


# ==============================================================================
# PHASE 17: HEALTH AND DIAGNOSTIC ENDPOINTS
# ==============================================================================
def test_health_and_version_diagnostics(maitri_env):
    client, sessions, _, _ = maitri_env

    # 1. /health returns 200 with service title
    r_health = client.get("/health")
    assert r_health.status_code == 200
    assert r_health.json() == {"status": "ok", "service": "Maitri API"}

    # 2. Database connectivity & PostGIS version check via client.portal
    async def check_postgis():
        async with sessions() as db:
            version = await db.scalar(text("SELECT PostGIS_Version()"))
            return version
    version = client.portal.call(check_postgis)
    assert version is not None
    assert len(version) > 0


# ==============================================================================
# CHECKPOINT C: SCREEN 14 PROFILE DETAILS AND SCREEN 13 PIN MANAGEMENT
# ==============================================================================
def test_profile_details_partial_update_keeps_responder_state(maitri_env):
    client, _, _, _ = maitri_env
    b = {"X-Test-Actor": "RESPONDER_B"}
    assert client.put("/api/users/me", headers=b, json={
        "display_name": "Responder B", "responder_opt_in": True, "responder_available": True}).status_code == 200

    saved = client.patch("/api/users/me", headers=b, json={
        "display_name": "  Responder Bee ", "phone_number": "+91 98765 43210", "city": " Pune "})
    assert saved.status_code == 200
    body = saved.json()
    assert (body["display_name"], body["phone_number"], body["city"]) == ("Responder Bee", "+91 98765 43210", "Pune")
    assert body["responder_opt_in"] is True and body["responder_available"] is True

    # Omitted fields are untouched; blank optional fields clear the stored value.
    cleared = client.patch("/api/users/me", headers=b, json={"phone_number": "", "city": "   "}).json()
    assert (cleared["display_name"], cleared["phone_number"], cleared["city"]) == ("Responder Bee", None, None)
    assert client.get("/api/users/me", headers=b).json()["phone_number"] is None

    for bad in ({"display_name": "   "}, {"display_name": None}, {"phone_number": "12"},
                {"phone_number": "call me"}, {"city": "x" * 129}, {"responder_available": False},
                {"email": "other@example.invalid"}):
        assert client.patch("/api/users/me", headers=b, json=bad).status_code == 422, bad

    # One account cannot see another account's contact details.
    assert client.get("/api/users/me", headers={"X-Test-Actor": "VICTIM_A"}).json()["phone_number"] is None


def test_safety_pin_change_and_recovery_contract(maitri_env):
    client, _, _, _ = maitri_env
    a = {"X-Test-Actor": "VICTIM_A"}
    assert client.get("/api/security/safety-pin/status", headers=a).json()["has_primary_pin"] is False
    for weak in ("111111", "123456", "654321"):
        assert client.post("/api/security/safety-pin/setup", headers=a,
                           json={"pin": weak, "confirm_pin": weak}).status_code == 422
    assert client.post("/api/security/safety-pin/setup", headers=a,
                       json={"pin": "839162", "confirm_pin": "839162"}).json()["status"] == "CONFIGURED"
    again = client.post("/api/security/safety-pin/setup", headers=a, json={"pin": "839162", "confirm_pin": "839162"})
    assert again.status_code == 409 and again.json()["detail"] == "PIN already enrolled. Use PIN change or recovery."

    wrong = client.post("/api/security/safety-pin/change", headers=a,
                        json={"current_pin": "774921", "new_pin": "520847", "confirm_pin": "520847"})
    assert wrong.status_code == 403 and wrong.json()["detail"].startswith("Current PIN is incorrect.")
    assert client.post("/api/security/safety-pin/change", headers=a,
                       json={"current_pin": "839162", "new_pin": "520847", "confirm_pin": "520847"}).json()["status"] == "UPDATED"

    stale = client.post("/api/security/safety-pin/recover", headers={**a, "X-Test-Auth-Age": "900"},
                        json={"new_pin": "406183", "confirm_pin": "406183"})
    assert stale.status_code == 403 and stale.json()["detail"] == "Sign in again to recover your Safety PIN"
    # A just-changed PIN cannot be immediately recovered (prevents takeover by a stolen session).
    assert client.post("/api/security/safety-pin/recover", headers=a,
                       json={"new_pin": "406183", "confirm_pin": "406183"}).status_code == 429

    incident_id = create_test_sos_incident(client, "VICTIM_A")
    closed = client.post(f"/api/incidents/{incident_id}/resolve", headers=a, json={"pin": "520847"})
    assert closed.status_code == 200 and closed.json()["status"] == "CLOSED"


# ==============================================================================
# CHECKPOINT D: TRUSTED CONTACT DELIVERY IS NEVER OVERSTATED
# ==============================================================================
def test_trusted_contact_status_is_truthful_and_owner_scoped(maitri_env):
    client, _, _, _ = maitri_env
    a = {"X-Test-Actor": "VICTIM_A"}
    assert client.post("/api/trusted-contacts", headers=a, json={
        "display_name": "Sister C", "phone_number": "+91 98765 43210", "label": "Sister",
        "is_primary": True, "notify_on_sos": True, "allow_live_tracking": False}).status_code == 201
    incident_id = create_test_sos_incident(client, "VICTIM_A")

    # Before dispatch nothing has been sent, and nothing claims otherwise.
    before = client.get(f"/api/incidents/{incident_id}/trusted-contacts", headers=a).json()
    assert [s["delivery_status"] for s in before] == ["NOT_REACHED"]

    sent = client.post(f"/api/incidents/{incident_id}/trusted-contacts/alert", headers=a).json()
    assert [s["delivery_status"] for s in sent["statuses"]] == ["NOT_REACHED"]  # not linked, no SMS prepared yet
    after = client.get(f"/api/incidents/{incident_id}/trusted-contacts", headers=a).json()
    assert [s["delivery_status"] for s in after] == ["NOT_REACHED"]
    assert all(s["delivery_status"] != "PROVIDER_ACCEPTED" for s in before + after)

    # Another account cannot probe this incident's alert state.
    assert client.get(f"/api/incidents/{incident_id}/trusted-contacts",
                      headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}).status_code == 404


# ==============================================================================
# RELEASE PHASE 1: ONLY APPROVED VOLUNTEERS RECEIVE OR ACCEPT SOS REQUESTS
# ==============================================================================
def test_only_approved_volunteers_are_invited_or_can_accept(maitri_env):
    client, sessions, settings, prefix = maitri_env
    b = {"X-Test-Actor": "RESPONDER_B"}
    client.put("/api/users/me", headers=b,
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})
    assert client.put("/api/responders/location", headers=b, json=make_fix(lat=28.6150)).status_code == 204
    uid = prefix + ACTORS["RESPONDER_B"]["uid"]

    async def set_status(status):
        async with sessions.begin() as db:
            await db.execute(text("""UPDATE volunteer_applications SET status=:s
                WHERE user_id=(SELECT id FROM users WHERE firebase_uid=:uid)"""), {"s": status, "uid": uid})

    async def run_tick():
        async with sessions.begin() as db:
            await tick(db, settings)

    # Under review: in range, opted in and available, but not invited.
    client.portal.call(set_status, "UNDER_REVIEW")
    first = create_test_sos_incident(client, "VICTIM_A")
    client.portal.call(run_tick)
    assert [i for i in client.get("/api/responders/invitations", headers=b).json() if i["incident_id"] == first] == []

    # Approved: invited on the next evaluation (clear the geofence's re-evaluation throttle for this incident).
    client.portal.call(set_status, "APPROVED")

    async def allow_reevaluation():
        async with sessions.begin() as db:
            await db.execute(text("UPDATE incidents SET evaluated_at=NULL, evaluated_center=NULL WHERE id=:id"), {"id": first})
    client.portal.call(allow_reevaluation)
    client.portal.call(run_tick)
    invitation = next(i for i in client.get("/api/responders/invitations", headers=b).json() if i["incident_id"] == first)

    # Approval revoked before answering: acceptance is refused.
    client.portal.call(set_status, "REJECTED")
    refused = client.post(f"/api/responders/invitations/{invitation['id']}/response", headers=b, json={"decision": "ACCEPTED"})
    assert refused.status_code == 403 and refused.json()["detail"] == "Volunteer approval required"


# ==============================================================================
# RELEASE PHASE 1: APP-SHAPED CIRCLE SYNC FEEDS SOS ALERT DISPATCH
# ==============================================================================
def test_app_circle_sync_contract_feeds_alert_dispatch(maitri_env):
    client, _, _, _ = maitri_env
    a = {"X-Test-Actor": "VICTIM_A"}

    def body(name, phone, primary, sos=True):
        # Exactly the keys TrustedContactWriteDto sends (the schema forbids extras).
        return {"display_name": name, "phone_number": phone, "label": "Emergency Contact", "is_primary": primary,
                "notify_on_sos": sos, "include_location_link": True, "notify_on_closure": True,
                "allow_live_tracking": False, "preferred_channel": "SMS"}

    asha = client.post("/api/trusted-contacts", headers=a, json=body("Asha", "+91 90000 00001", True)).json()
    ravi = client.post("/api/trusted-contacts", headers=a, json=body("Ravi", "+91 90000 00002", False)).json()
    # Full-body update (app sends every field) turns Ravi's SOS alerts off.
    updated = client.put(f"/api/trusted-contacts/{ravi['id']}", headers=a, json=body("Ravi K", "+91 90000 00002", False, sos=False))
    assert updated.status_code == 200 and updated.json()["notify_on_sos"] is False
    assert client.post(f"/api/trusted-contacts/{ravi['id']}/primary", headers=a).json()["is_primary"] is True
    listed = {c["display_name"]: c for c in client.get("/api/trusted-contacts", headers=a).json()}
    assert listed["Ravi K"]["is_primary"] and not listed["Asha"]["is_primary"]

    incident_id = create_test_sos_incident(client, "VICTIM_A")
    statuses = {s["display_name"]: s["delivery_status"]
                for s in client.post(f"/api/incidents/{incident_id}/trusted-contacts/alert", headers=a).json()["statuses"]}
    assert statuses == {"Asha": "NOT_REACHED"}   # only contacts with SOS alerts on, honestly not reached yet
    # Dispatch is idempotent: the app may call it again after a reconnect.
    again = client.post(f"/api/incidents/{incident_id}/trusted-contacts/alert", headers=a)
    assert again.status_code == 200
    assert client.delete(f"/api/trusted-contacts/{asha['id']}", headers=a).status_code == 204
