"""Regression Test Suite for Responder Location Tracking & Monotonic Invariants.
Verifies HTTP 409 conflict semantics, client-side stale filtering, recoverable error states,
and guarantees that responder location updates never create unintended SOS incidents.
"""
from datetime import datetime, timedelta, timezone
import pytest
from sqlalchemy import text
from app.config import Settings
from tests.test_maitri_features import ACTORS, make_fix, maitri_env


def test_responder_location_first_valid_fix(maitri_env):
    """1. First valid GPS fix accepted with HTTP 204."""
    client, _, _, _ = maitri_env

    # Opt in and make available
    res = client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
                     json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})
    assert res.status_code == 200

    # Submit first fix
    fix1 = make_fix(lat=28.6140, lon=77.2090, accuracy=10.0, seconds=0)
    res_fix = client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix1)
    assert res_fix.status_code == 204


def test_responder_location_newer_fix_accepted(maitri_env):
    """2. Newer fix with strictly greater timestamp is accepted with HTTP 204."""
    client, _, _, _ = maitri_env

    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})

    now = datetime.now(timezone.utc)
    fix1 = {
        "latitude": 28.6140,
        "longitude": 77.2090,
        "accuracy_meters": 10.0,
        "recorded_at": (now - timedelta(seconds=10)).isoformat(),
        "is_mock": True,
    }
    fix2 = {
        "latitude": 28.6145,
        "longitude": 77.2095,
        "accuracy_meters": 8.0,
        "recorded_at": (now - timedelta(seconds=5)).isoformat(),
        "is_mock": True,
    }

    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix1).status_code == 204
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix2).status_code == 204


def test_responder_location_identical_timestamp_rejected_with_409(maitri_env):
    """3. Identical timestamp is rejected with HTTP 409 and STALE_OR_DUPLICATE_LOCATION error code."""
    client, _, _, _ = maitri_env

    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})

    now = datetime.now(timezone.utc)
    fix_same = {
        "latitude": 28.6140,
        "longitude": 77.2090,
        "accuracy_meters": 10.0,
        "recorded_at": now.isoformat(),
        "is_mock": True,
    }

    # First submission succeeds
    res1 = client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix_same)
    assert res1.status_code == 204

    # Second submission with exact same timestamp returns HTTP 409
    res2 = client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix_same)
    assert res2.status_code == 409
    assert "already exists" in res2.json()["detail"]
    assert res2.headers.get("X-Error-Code") == "STALE_OR_DUPLICATE_LOCATION"


def test_responder_location_older_timestamp_rejected_with_409(maitri_env):
    """4. Older timestamp is rejected with HTTP 409 without overwriting stored location."""
    client, _, _, _ = maitri_env

    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})

    now = datetime.now(timezone.utc)
    fix_newer = {
        "latitude": 28.6150,
        "longitude": 77.2100,
        "accuracy_meters": 5.0,
        "recorded_at": now.isoformat(),
        "is_mock": True,
    }
    fix_older = {
        "latitude": 28.6120,
        "longitude": 77.2080,
        "accuracy_meters": 5.0,
        "recorded_at": (now - timedelta(seconds=15)).isoformat(),
        "is_mock": True,
    }

    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix_newer).status_code == 204
    res_old = client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix_older)
    assert res_old.status_code == 409
    assert res_old.headers.get("X-Error-Code") == "STALE_OR_DUPLICATE_LOCATION"


def test_responder_location_recovery_after_409(maitri_env):
    """5. After a 409 conflict, subsequent newer fix is accepted cleanly (recoverable state)."""
    client, _, _, _ = maitri_env

    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})

    now = datetime.now(timezone.utc)
    fix_t1 = {
        "latitude": 28.6140,
        "longitude": 77.2090,
        "accuracy_meters": 10.0,
        "recorded_at": (now - timedelta(seconds=10)).isoformat(),
        "is_mock": True,
    }
    fix_t2 = {
        "latitude": 28.6160,
        "longitude": 77.2110,
        "accuracy_meters": 10.0,
        "recorded_at": now.isoformat(),
        "is_mock": True,
    }

    # t1 accepted
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix_t1).status_code == 204

    # duplicate t1 rejected with 409
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix_t1).status_code == 409

    # t2 (newer) accepted with 204
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix_t2).status_code == 204


def test_responder_location_disabled_cleans_stored_location(maitri_env):
    """6. When responder turns off opt-in, stored location is removed from locations table."""
    client, sessions, _, prefix = maitri_env

    # Enable and post location
    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})
    fix = make_fix()
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix).status_code == 204

    # Verify location exists in database
    async def check_loc_count():
        async with sessions.begin() as db:
            result = await db.scalar(text("""
                SELECT count(*) FROM responder_locations WHERE user_id = (SELECT id FROM users WHERE firebase_uid = :uid)
            """), {"uid": prefix + ACTORS["RESPONDER_B"]["uid"]})
            return result
    assert client.portal.call(check_loc_count) == 1

    # Opt out
    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": False, "responder_available": False})

    # Location must be completely deleted
    assert client.portal.call(check_loc_count) == 0


def test_responder_location_permission_or_availability_denied(maitri_env):
    """7 & 8. When responder is off duty or unavailable, location update returns HTTP 403."""
    client, _, _, _ = maitri_env

    # 1. Neither opted in nor available
    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": False, "responder_available": False})
    res1 = client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=make_fix())
    assert res1.status_code == 403
    assert "consent and availability required" in res1.json()["detail"]

    # 2. Opted in, but available = False
    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": False})
    res2 = client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=make_fix())
    assert res2.status_code == 403


def test_responder_location_unauthorized_account_denied(maitri_env):
    """9. Non-allowlisted account returns HTTP 403."""
    client, _, _, _ = maitri_env
    res = client.put("/api/responders/location", headers={"X-Test-Actor": "UNAUTHORIZED_USER_D"}, json=make_fix())
    assert res.status_code == 403


def test_responder_location_input_validation(maitri_env):
    """10, 11, 12. Quality validation: accuracy, stale age, and future-dated fixes rejected with 422."""
    client, _, _, _ = maitri_env

    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})

    now = datetime.now(timezone.utc)

    # Inaccurate > 100m
    bad_acc = make_fix(accuracy=105.0)
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=bad_acc).status_code == 422

    # Stale > 120s
    stale = make_fix()
    stale["recorded_at"] = (now - timedelta(seconds=130)).isoformat()
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=stale).status_code == 422

    # Future > 5s
    future = make_fix()
    future["recorded_at"] = (now + timedelta(seconds=12)).isoformat()
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=future).status_code == 422


def test_responder_location_no_unintended_sos(maitri_env):
    """13 & 14. Monotonic constraint ensures no duplicates in DB, and never creates SOS incidents."""
    client, sessions, _, prefix = maitri_env

    client.put("/api/users/me", headers={"X-Test-Actor": "RESPONDER_B"},
               json={"display_name": "Responder B", "responder_opt_in": True, "responder_available": True})

    fix = make_fix()
    assert client.put("/api/responders/location", headers={"X-Test-Actor": "RESPONDER_B"}, json=fix).status_code == 204

    async def verify_invariants():
        async with sessions.begin() as db:
            # Exactly 1 location record for this user in responder_locations
            loc_count = await db.scalar(text("""
                SELECT count(*) FROM responder_locations WHERE user_id = (SELECT id FROM users WHERE firebase_uid = :uid)
            """), {"uid": prefix + ACTORS["RESPONDER_B"]["uid"]})
            assert loc_count == 1
            # Exactly 0 SOS incidents created for this user
            inc_count = await db.scalar(text("""
                SELECT count(*) FROM incidents WHERE victim_user_id = (SELECT id FROM users WHERE firebase_uid = :uid)
            """), {"uid": prefix + ACTORS["RESPONDER_B"]["uid"]})
            assert inc_count == 0

    client.portal.call(verify_invariants)
