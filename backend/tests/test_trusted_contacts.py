"""Tests for Phase 13: Trusted Emergency Contact Alerts, SMS Fallback, Secure Location Sharing & Emergency Escalation."""
import os
from datetime import datetime, timezone
from uuid import uuid4

import pytest
from fastapi import Request
from sqlalchemy import select
from sqlalchemy.engine import make_url
from starlette.testclient import TestClient

from app.config import Settings
from app.core import Identity, identity
from app.database.v1 import (
    incidents,
    notification_outbox,
    trusted_contacts,
    trusted_contact_grants,
)
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
        return Identity(prefix + actor, f"{actor}@example.invalid", actor, True, int(datetime.now(timezone.utc).timestamp()))

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


def test_trusted_contact_crud_and_primary_invariant(client_env):
    client, _ = client_env

    # 1. Initially no contacts
    r = client.get("/api/trusted-contacts", headers={"X-Test-Actor": "victim"})
    assert r.status_code == 200
    assert r.json() == []

    # 2. Add first contact (should automatically become primary)
    c1_resp = client.post(
        "/api/trusted-contacts",
        headers={"X-Test-Actor": "victim"},
        json={
            "display_name": "Mom",
            "phone_number": "+91 98765 43210",
            "is_primary": False,
            "notify_on_sos": True,
            "include_location_link": True,
            "notify_on_closure": True,
            "allow_live_tracking": True,
            "preferred_channel": "APP"
        }
    )
    assert c1_resp.status_code == 201, c1_resp.text
    c1 = c1_resp.json()
    assert c1["display_name"] == "Mom"
    assert c1["normalized_phone_number"] == "+919876543210"
    assert c1["is_primary"] is True  # Enforced primary when first

    # 3. Add second contact with is_primary=True (should demote Mom)
    c2_resp = client.post(
        "/api/trusted-contacts",
        headers={"X-Test-Actor": "victim"},
        json={
            "display_name": "Sister",
            "phone_number": "+91 91234 56789",
            "is_primary": True,
            "notify_on_sos": True,
            "include_location_link": True,
            "notify_on_closure": False,
            "allow_live_tracking": False,
            "preferred_channel": "SMS"
        }
    )
    assert c2_resp.status_code == 201, c2_resp.text
    c2 = c2_resp.json()
    assert c2["is_primary"] is True

    # Check listing: Sister should be first (primary), Mom second (non-primary)
    listing = client.get("/api/trusted-contacts", headers={"X-Test-Actor": "victim"}).json()
    assert len(listing) == 2
    assert listing[0]["id"] == c2["id"]
    assert listing[0]["is_primary"] is True
    assert listing[1]["id"] == c1["id"]
    assert listing[1]["is_primary"] is False

    # 4. Reject duplicate phone number
    dup_resp = client.post(
        "/api/trusted-contacts",
        headers={"X-Test-Actor": "victim"},
        json={
            "display_name": "Duplicate Sister",
            "phone_number": "+91 91234 56789"
        }
    )
    assert dup_resp.status_code == 409

    # 5. Reject invalid short phone number
    short_resp = client.post(
        "/api/trusted-contacts",
        headers={"X-Test-Actor": "victim"},
        json={
            "display_name": "Invalid",
            "phone_number": "1234"
        }
    )
    assert short_resp.status_code == 422

    # 6. Update contact preferences
    update_resp = client.put(
        f"/api/trusted-contacts/{c1['id']}",
        headers={"X-Test-Actor": "victim"},
        json={
            "display_name": "Mother (Home)",
            "allow_live_tracking": False
        }
    )
    assert update_resp.status_code == 200
    assert update_resp.json()["display_name"] == "Mother (Home)"
    assert update_resp.json()["allow_live_tracking"] is False

    # 7. Explicitly set Mom back to primary
    pri_resp = client.post(
        f"/api/trusted-contacts/{c1['id']}/primary",
        headers={"X-Test-Actor": "victim"}
    )
    assert pri_resp.status_code == 200
    assert pri_resp.json()["is_primary"] is True

    # 8. Delete primary contact (Mom) -> Sister should be auto-promoted to primary
    del_resp = client.delete(
        f"/api/trusted-contacts/{c1['id']}",
        headers={"X-Test-Actor": "victim"}
    )
    assert del_resp.status_code == 204

    listing_after = client.get("/api/trusted-contacts", headers={"X-Test-Actor": "victim"}).json()
    assert len(listing_after) == 1
    assert listing_after[0]["id"] == c2["id"]
    assert listing_after[0]["is_primary"] is True


def test_sos_incident_trusted_contact_alerts(client_env):
    client, _ = client_env

    # 1. Setup contacts: Contact A (notify_on_sos=True, live tracking=True), Contact B (notify_on_sos=False)
    cA = client.post(
        "/api/trusted-contacts",
        headers={"X-Test-Actor": "victim"},
        json={
            "display_name": "Brother",
            "phone_number": "+91 98888 77777",
            "notify_on_sos": True,
            "include_location_link": True,
            "notify_on_closure": True,
            "allow_live_tracking": True
        }
    ).json()

    cB = client.post(
        "/api/trusted-contacts",
        headers={"X-Test-Actor": "victim"},
        json={
            "display_name": "Colleague",
            "phone_number": "+91 96666 55555",
            "notify_on_sos": False,
            "include_location_link": False,
            "notify_on_closure": False,
            "allow_live_tracking": False
        }
    ).json()

    # 2. Trigger SOS incident
    incident_id = create_test_incident(client, "victim")

    # 3. Dispatch trusted contact alert
    alert_resp = client.post(
        f"/api/incidents/{incident_id}/trusted-contacts/alert",
        headers={"X-Test-Actor": "victim"}
    )
    assert alert_resp.status_code == 200, alert_resp.text
    batch = alert_resp.json()
    assert batch["alerts_queued"] == 1
    assert batch["statuses"][0]["contact_id"] == cA["id"]
    # Not linked to a Maitri account: returned for the owner's phone to text, and not claimed as reached.
    assert batch["statuses"][0]["delivery_status"] == "NOT_REACHED"
    assert [c["contact_id"] for c in batch["sms_contacts"]] == [cA["id"]]
    assert "/l/" in batch["live_link"]
    assert batch["statuses"][0]["tracking_granted"] is False

    # 4. Check status endpoint for incident contacts
    statuses_resp = client.get(
        f"/api/incidents/{incident_id}/trusted-contacts",
        headers={"X-Test-Actor": "victim"}
    )
    assert statuses_resp.status_code == 200
    statuses = statuses_resp.json()
    assert len(statuses) == 2
    by_id = {s["contact_id"]: s for s in statuses}
    assert by_id[cA["id"]]["delivery_status"] == "NOT_REACHED"
    assert by_id[cB["id"]]["delivery_status"] == "NOT_REQUESTED"


def test_incident_closure_revokes_tracking_and_notifies_closure(client_env):
    client, _ = client_env

    # 1. Setup Safety PIN
    client.post(
        "/api/security/safety-pin/setup",
        headers={"X-Test-Actor": "victim"},
        json={"pin": "246813", "confirm_pin": "246813"}
    )

    # 2. Setup contact with notify_on_closure=True and allow_live_tracking=True
    c = client.post(
        "/api/trusted-contacts",
        headers={"X-Test-Actor": "victim"},
        json={
            "display_name": "Father",
            "phone_number": "+91 97777 66666",
            "notify_on_sos": True,
            "include_location_link": True,
            "notify_on_closure": True,
            "allow_live_tracking": True
        }
    ).json()

    # 3. Create incident and dispatch alerts
    incident_id = create_test_incident(client, "victim")
    client.post(f"/api/incidents/{incident_id}/trusted-contacts/alert", headers={"X-Test-Actor": "victim"})

    # 4. Resolve incident with Safety PIN
    resolve_resp = client.post(
        f"/api/incidents/{incident_id}/resolve",
        headers={"X-Test-Actor": "victim"},
        json={"pin": "246813", "reason": "Safe with family"}
    )
    assert resolve_resp.status_code == 200
    assert resolve_resp.json()["status"] == "CLOSED"
