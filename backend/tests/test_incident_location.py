"""Victim SOS location rules: coarse and last-known fixes are kept; precise rules stay where precision matters."""
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from tests.test_maitri_features import make_fix, maitri_env  # noqa: F401  (fixture)


def sos(client, **body):
    response = client.post("/api/incidents", headers={"Idempotency-Key": str(uuid4())}, json={"is_test": True, **body})
    assert response.status_code == 200, response.text
    return response.json()["id"]


def post_fix(client, incident, fix):
    return client.post(f"/api/incidents/{incident}/location", json=fix)


def stored(client, incident):
    return client.get(f"/api/incidents/{incident}/location").json()


def aged(seconds, **changes):
    fix = make_fix(**changes)
    fix["recorded_at"] = (datetime.now(timezone.utc) - timedelta(seconds=seconds)).isoformat()
    return fix


def test_approximate_fix_is_kept_with_its_accuracy(maitri_env):
    client, _, _, _ = maitri_env
    incident = sos(client)
    assert post_fix(client, incident, make_fix(accuracy=1500.0)).status_code == 204
    location = stored(client, incident)
    assert location["latitude"] is not None and location["accuracy_meters"] == 1500.0


def test_fix_beyond_the_sos_limit_gets_a_reason_code(maitri_env):
    client, _, _, _ = maitri_env
    incident = sos(client)
    rejected = post_fix(client, incident, make_fix(accuracy=5001.0))
    assert rejected.status_code == 422
    assert rejected.json()["detail"]["code"] == "LOCATION_TOO_INACCURATE"
    assert rejected.headers["X-Error-Code"] == "LOCATION_TOO_INACCURATE"


def test_last_known_fix_is_allowed_as_the_first_fix_only(maitri_env):
    client, _, settings, _ = maitri_env
    incident = sos(client)
    # Nine minutes old: accepted because the incident has no location yet.
    assert post_fix(client, incident, aged(540, accuracy=40.0)).status_code == 204
    # Five minutes old but no longer the first fix: the normal 120 s rule applies.
    second = post_fix(client, incident, aged(300, lat=28.62))
    assert second.status_code == 422 and second.json()["detail"]["code"] == "LOCATION_TOO_OLD"
    # A fresh fix replaces the last-known one.
    assert post_fix(client, incident, make_fix(lat=28.63)).status_code == 204
    assert abs(stored(client, incident)["latitude"] - 28.63) < 1e-6


def test_last_known_fix_older_than_ten_minutes_is_refused(maitri_env):
    client, _, settings, _ = maitri_env
    incident = sos(client)
    rejected = post_fix(client, incident, aged(settings.max_last_known_age_seconds + 30))
    assert rejected.status_code == 422 and rejected.json()["detail"]["code"] == "LOCATION_TOO_OLD"


def test_sos_created_with_a_last_known_fix_keeps_it(maitri_env):
    client, _, _, _ = maitri_env
    incident = sos(client, location=aged(480, accuracy=900.0))
    location = stored(client, incident)
    assert location["latitude"] is not None and location["accuracy_meters"] == 900.0


def test_future_dated_fix_has_its_own_code(maitri_env):
    client, _, _, _ = maitri_env
    incident = sos(client)
    rejected = post_fix(client, incident, aged(-30))
    assert rejected.status_code == 422 and rejected.json()["detail"]["code"] == "LOCATION_FUTURE_DATED"


def test_coarse_victim_fix_is_not_used_for_geofence_matching(maitri_env):
    """Responders are only matched against precise, fresh victim fixes (moving_geofence.FRESH)."""
    client, sessions, settings, _ = maitri_env
    from sqlalchemy import text
    from app.services.moving_geofence import FRESH, parameters
    incident = sos(client, location=make_fix(accuracy=1500.0))

    async def fresh():
        async with sessions.begin() as db:
            params = parameters(settings)
            return await db.scalar(text(f"SELECT count(*) FROM incidents i WHERE i.id=:id AND {FRESH}"),
                                   {"id": incident, "accuracy": params["accuracy"], "age": params["age"],
                                    "production": params["production"]})
    assert client.portal.call(fresh) == 0
    assert post_fix(client, incident, make_fix(accuracy=8.0, seconds=1)).status_code == 204
    assert client.portal.call(fresh) == 1
