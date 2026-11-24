"""P4: admin-only volunteer review, 5-minute document links, decisions with audit log, profile photos."""
from dataclasses import replace
from uuid import uuid4

import pytest
from pydantic import SecretStr
from sqlalchemy import text

import tests.test_moving_geofence as geo
from app.core import Identity
from tests.test_moving_geofence import world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio
DOCS = ["GOVT_ID", "ADDRESS_PROOF", "POLICE_CERT", "PHOTO", "DECLARATION"]


@pytest.fixture(autouse=True)
def admins_by_name(monkeypatch):
    """Actors named admin* carry the Firebase admin claim; everyone else doesn't."""
    def make(uid, email, name, verified, *rest, **kw):
        return replace(Identity(uid, email, name, verified, *rest, **kw), admin=uid.startswith("admin"))
    monkeypatch.setattr(geo, "Identity", make)


def configure_storage(settings):
    settings.r2_access_key_id = "test-key"
    settings.r2_secret_access_key = SecretStr("test-secret")
    settings.r2_bucket = "maitri-test"
    settings.r2_endpoint = "http://127.0.0.1:9"  # refuses connections at once; signing is local


async def stored(sessions, actor, kind, doc_type=None):
    async with sessions.begin() as db:
        uid = await db.scalar(text("SELECT id FROM users WHERE display_name=:n"), {"n": actor})
        file_id = uuid4()
        await db.execute(text("""INSERT INTO stored_files(id, owner_user_id, kind, doc_type, object_key, mime_type,
            size_bytes, status, uploaded_at) VALUES (:id, :uid, :kind, :doc, :key, 'image/jpeg', 1000, 'UPLOADED', now())"""),
            {"id": file_id, "uid": uid, "kind": kind, "doc": doc_type, "key": f"{kind.lower()}/{uid}/{file_id}"})
        if doc_type:
            column = {"GOVT_ID": "has_govt_id", "ADDRESS_PROOF": "has_address_proof", "POLICE_CERT": "has_police_cert",
                      "PHOTO": "has_photo", "DECLARATION": "has_declaration"}[doc_type]
            await db.execute(text(f"UPDATE volunteer_applications SET {column}=true WHERE user_id=:uid"), {"uid": uid})
        return str(file_id)


async def submitted_application(call, sessions):
    assert (await call("applicant", "POST", "/volunteer/intent", json={"intent": "HELP"})).is_success
    assert (await call("applicant", "POST", "/volunteer/consent", json={"consent_declared": True})).is_success
    for doc in DOCS:
        await stored(sessions, "applicant", "VOLUNTEER_DOC", doc)
    status = (await call("applicant", "GET", "/volunteer/status")).json()
    assert {d["doc_type"] for d in status["documents"]} == set(DOCS)
    assert (await call("applicant", "POST", "/volunteer/submit")).is_success


async def test_only_admins_reach_the_review_api(world):
    call, sessions, settings = world
    await submitted_application(call, sessions)
    refused = await call("applicant", "GET", "/admin/applications")
    assert refused.status_code == 403 and refused.json()["detail"]["code"] == "ADMIN_REQUIRED"
    assert (await call("admin-1", "GET", "/admin/applications")).status_code == 200


async def test_review_approve_and_audit(world):
    call, sessions, settings = world
    configure_storage(settings)
    await submitted_application(call, sessions)
    pending = (await call("admin-1", "GET", "/admin/applications")).json()
    assert [p["display_name"] for p in pending] == ["applicant"]
    app_id = pending[0]["id"]
    detail = (await call("admin-1", "GET", f"/admin/applications/{app_id}")).json()
    assert detail["status"] == "UNDER_REVIEW" and len(detail["documents"]) == 5
    link = (await call("admin-1", "POST", f"/admin/applications/{app_id}/documents/{detail['documents'][0]['id']}/url")).json()
    assert "X-Amz-Expires=300" in link["url"] and link["expires_in"] == 300
    # Someone else's file id can't be opened through this application.
    assert (await call("admin-1", "POST", f"/admin/applications/{app_id}/documents/{uuid4()}/url")).status_code == 404
    no_note = await call("admin-1", "POST", f"/admin/applications/{app_id}/decision", json={"decision": "REJECTED"})
    assert no_note.status_code == 422
    approved = await call("admin-1", "POST", f"/admin/applications/{app_id}/decision", json={"decision": "APPROVED"})
    assert approved.json()["status"] == "APPROVED"
    status = (await call("applicant", "GET", "/volunteer/status")).json()
    assert status["status"] == "APPROVED" and status["reviewed_at"]
    inbox = (await call("applicant", "GET", "/inbox")).json()["items"]
    assert inbox[0]["kind"] == "VOLUNTEER_DECISION" and inbox[0]["data"]["decision"] == "APPROVED"
    async with sessions.begin() as db:
        actions = (await db.execute(text("SELECT action FROM admin_audit_log ORDER BY created_at"))).scalars().all()
    assert actions == ["APPLICATION_OPENED", "DOCUMENT_VIEWED", "APPLICATION_DECIDED"]


async def test_rejecting_an_approved_volunteer_makes_them_unavailable(world):
    call, sessions, _ = world
    await submitted_application(call, sessions)
    app_id = (await call("admin-1", "GET", "/admin/applications")).json()[0]["id"]
    await call("admin-1", "POST", f"/admin/applications/{app_id}/decision", json={"decision": "APPROVED"})
    assert (await call("applicant", "PUT", "/users/me", json={"display_name": "applicant", "responder_opt_in": True,
                                                              "responder_available": True})).is_success
    await call("admin-1", "POST", f"/admin/applications/{app_id}/decision",
               json={"decision": "REJECTED", "note": "Police certificate is unreadable"})
    me = (await call("applicant", "GET", "/users/me")).json()
    assert me["responder_available"] is False
    status = (await call("applicant", "GET", "/volunteer/status")).json()
    assert status["status"] == "REJECTED" and status["reviewer_notes"] == "Police certificate is unreadable"


async def test_admin_page_is_a_locked_down_shell(world):
    call, _, settings = world
    page = await call("anyone", "GET", "/../admin")
    assert page.status_code == 200 and "FIREBASE_WEB_API_KEY" in page.text
    assert "frame-ancestors" in page.headers["Content-Security-Policy"] or page.headers["X-Frame-Options"] == "DENY"
    settings.firebase_web_api_key = "public-web-key"
    page = await call("anyone", "GET", "/../admin")
    assert '"apiKey": "public-web-key"' in page.text and "integrity=" in page.text


async def test_profile_photo_replaces_and_deletes_the_old_one(world):
    call, sessions, settings = world
    configure_storage(settings)
    await call("someone", "GET", "/users/me")
    assert (await call("someone", "GET", "/files/profile-photo")).json() == {"file_id": None, "download_url": None}
    first = await stored(sessions, "someone", "PROFILE_PHOTO")
    assert (await call("someone", "POST", "/files/profile-photo", json={"file_id": first})).is_success
    shown = (await call("someone", "GET", "/files/profile-photo")).json()
    assert shown["file_id"] == first and "X-Amz-Signature" in shown["download_url"]
    second = await stored(sessions, "someone", "PROFILE_PHOTO")
    await call("someone", "POST", "/files/profile-photo", json={"file_id": second})
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT count(*) FROM stored_files WHERE id=:id"), {"id": first}) == 0
    assert (await call("intruder", "POST", "/files/profile-photo", json={"file_id": second})).status_code == 404
