"""P6: encrypted medical notes, data export, account deletion, retention."""
import base64
import os
import time
from dataclasses import replace
from uuid import uuid4

import pytest
from pydantic import SecretStr
from sqlalchemy import text

import tests.test_moving_geofence as geo
from app.core import Identity
from app.maintenance import cleanup
from app.privacy import purge_sos_media
from tests.test_moving_geofence import create, enable, fix, world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio
KEY = SecretStr(base64.urlsafe_b64encode(os.urandom(32)).decode())


@pytest.fixture(autouse=True)
def recently_signed_in(monkeypatch):
    """Actors named stale* signed in an hour ago; everyone else just now."""
    def make(uid, email, name, verified, *rest, **kw):
        age = 3600 if uid.startswith("stale") else 0
        return replace(Identity(uid, email, name, verified, *rest, **kw), auth_time=int(time.time()) - age)
    monkeypatch.setattr(geo, "Identity", make)


async def test_medical_notes_are_encrypted_and_reach_only_an_active_responder(world):
    call, sessions, settings = world
    await call("owner", "GET", "/users/me")
    unavailable = await call("owner", "PUT", "/users/me/medical-notes", json={"notes": "Asthma"})
    assert unavailable.status_code == 503 and unavailable.json()["detail"]["code"] == "MEDICAL_NOTES_UNAVAILABLE"
    settings.medical_notes_key = KEY
    assert (await call("owner", "PUT", "/users/me/medical-notes", json={"notes": "Asthma, inhaler in left pocket"})).is_success
    assert (await call("owner", "GET", "/users/me/medical-notes")).json()["notes"] == "Asthma, inhaler in left pocket"
    async with sessions.begin() as db:
        stored = await db.scalar(text("SELECT medical_notes FROM users WHERE display_name='owner'"))
    assert b"Asthma" not in bytes(stored)
    await enable(call, "helper", 300)
    incident = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    invitation = (await call("helper", "GET", "/responders/invitations")).json()[0]["id"]
    before = (await call("helper", "GET", f"/incidents/{incident}/snapshot"))
    assert before.status_code in (403, 404)
    await call("helper", "POST", f"/responders/invitations/{invitation}/response", json={"decision": "ACCEPTED"})
    assert (await call("helper", "GET", f"/incidents/{incident}/snapshot")).json()["medical_notes"].startswith("Asthma")
    assert (await call("owner", "GET", f"/incidents/{incident}/snapshot")).json()["medical_notes"] is None
    # Bound to the account: copied into another row it doesn't decrypt.
    await call("other", "GET", "/users/me")
    async with sessions.begin() as db:
        await db.execute(text("UPDATE users SET medical_notes=:b WHERE display_name='other'"), {"b": stored})
    with pytest.raises(Exception):
        await call("other", "GET", "/users/me/medical-notes")


async def test_export_lists_the_accounts_data(world):
    call, _, settings = world
    settings.medical_notes_key = KEY
    await call("owner", "POST", "/trusted-contacts", json={"display_name": "Sis", "phone_number": "+919811100000"})
    await call("owner", "PUT", "/users/me/medical-notes", json={"notes": "Diabetic"})
    response = await call("owner", "GET", "/users/me/export")
    assert response.headers["Content-Disposition"].startswith("attachment")
    data = response.json()
    assert data["profile"]["display_name"] == "owner" and data["profile"]["medical_notes"] == "Diabetic"
    assert data["trusted_contacts"][0]["display_name"] == "Sis"
    assert {"sos_incidents", "notifications", "stored_files", "volunteer_application"} <= set(data)


async def test_delete_account_needs_a_fresh_sign_in_and_no_open_sos(world):
    call, _, _ = world
    stale = await call("stale-user", "DELETE", "/users/me")
    assert stale.status_code == 401 and stale.json()["detail"]["code"] == "REAUTH_REQUIRED"
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    open_sos = await call("owner", "DELETE", "/users/me")
    assert open_sos.status_code == 409 and open_sos.json()["detail"]["code"] == "SOS_OPEN"


async def test_delete_account_removes_everything(world):
    call, sessions, _ = world
    await enable(call, "owner", 200)  # also a volunteer with a location
    await call("owner", "POST", "/trusted-contacts", json={"display_name": "Sis", "phone_number": "+919811100001"})
    # A closed SOS of their own, and help they gave on someone else's.
    mine = (await call("owner", "POST", "/incidents", headers={"Idempotency-Key": str(uuid4())},
                       json={"is_test": True, "location": fix(0)})).json()["id"]
    await enable(call, "helper", 300)
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incidents SET status='CLOSED', ended_at=now() WHERE id=:id"), {"id": mine})
    theirs = (await call("victim2", "POST", "/incidents", headers={"Idempotency-Key": str(uuid4())},
                         json={"is_test": True, "location": fix(0)})).json()["id"]
    for invitation in (await call("owner", "GET", "/responders/invitations")).json():
        await call("owner", "POST", f"/responders/invitations/{invitation['id']}/response", json={"decision": "ACCEPTED"})
    assert (await call("owner", "DELETE", "/users/me")).status_code == 204
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT count(*) FROM users WHERE display_name='owner'")) == 0
        assert await db.scalar(text("SELECT count(*) FROM incidents WHERE id=:id"), {"id": mine}) == 0
        assert await db.scalar(text("SELECT count(*) FROM trusted_contacts WHERE display_name='Sis'")) == 0
        assert await db.scalar(text("SELECT count(*) FROM incidents WHERE id=:id"), {"id": theirs}) == 1


async def test_delete_with_files_but_no_storage_is_refused_and_rolled_back(world):
    call, sessions, _ = world
    await call("owner", "GET", "/users/me")
    async with sessions.begin() as db:
        uid = await db.scalar(text("SELECT id FROM users WHERE display_name='owner'"))
        await db.execute(text("""INSERT INTO stored_files(owner_user_id, kind, object_key, mime_type, size_bytes)
            VALUES (:u, 'PROFILE_PHOTO', :k, 'image/jpeg', 10)"""), {"u": uid, "k": f"profile_photo/{uid}/x"})
    refused = await call("owner", "DELETE", "/users/me")
    assert refused.status_code == 503
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT count(*) FROM users WHERE id=:id"), {"id": uid}) == 1


async def test_retention_erases_closed_sos_locations_after_seven_days(world):
    call, sessions, settings = world
    old = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incidents SET status='CLOSED', ended_at=now()-interval '8 days' WHERE id=:id"), {"id": old})
    recent = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incidents SET status='CLOSED', ended_at=now()-interval '2 days' WHERE id=:id"), {"id": recent})
        await cleanup(db, settings)
        kept = dict((await db.execute(text("SELECT id::text, latest_location IS NOT NULL FROM incidents"))).all())
    assert kept[old] is False and kept[recent] is True


async def test_sos_media_purge_keeps_rows_when_storage_is_unreachable(world):
    call, sessions, settings = world
    settings.r2_access_key_id, settings.r2_secret_access_key = "k", SecretStr("s")
    settings.r2_bucket, settings.r2_endpoint = "b", "http://127.0.0.1:9"
    incident = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    async with sessions.begin() as db:
        uid = await db.scalar(text("SELECT victim_user_id FROM incidents WHERE id=:id"), {"id": incident})
        await db.execute(text("""INSERT INTO stored_files(owner_user_id, kind, incident_id, object_key, mime_type, size_bytes, created_at)
            VALUES (:u, 'SOS_PHOTO', :i, 'sos_photo/x', 'image/jpeg', 10, now() - interval '31 days')"""), {"u": uid, "i": incident})
    assert await purge_sos_media(sessions, settings) == 0
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT count(*) FROM stored_files")) == 1
