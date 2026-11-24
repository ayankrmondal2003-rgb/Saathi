"""SOS photos and voice notes reach the circle members alerted in the app, and nobody else."""
from uuid import uuid4

import pytest
from pydantic import SecretStr
from sqlalchemy import text

from tests.test_circle_alerts import contact, pair
from tests.test_moving_geofence import create, enable, fix, world  # noqa: F401  (fixture)

pytestmark = pytest.mark.asyncio


async def sos_with_media(call, sessions, settings):
    """owner has friend (linked, alerted) and stranger; an SOS with an uploaded photo, a voice note and a pending one."""
    settings.r2_access_key_id, settings.r2_secret_access_key = "k", SecretStr("s")
    settings.r2_bucket, settings.r2_endpoint = "b", "https://r2.example"
    friend_contact = await contact(call, "owner", "Friend", "+919800000011")
    await pair(call, "owner", friend_contact, "friend")
    incident = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    assert (await call("owner", "POST", f"/incidents/{incident}/trusted-contacts/alert")).is_success
    async with sessions.begin() as db:
        owner = await db.scalar(text("SELECT victim_user_id FROM incidents WHERE id=:id"), {"id": incident})
        ids = {}
        for order, (name, kind, mime, status) in enumerate([("photo", "SOS_PHOTO", "image/jpeg", "UPLOADED"),
                                                            ("voice", "SOS_VOICE", "audio/mp4", "UPLOADED"),
                                                            ("pending", "SOS_VOICE", "audio/mp4", "PENDING")]):
            ids[name] = await db.scalar(text("""INSERT INTO stored_files(owner_user_id, kind, incident_id, object_key,
                mime_type, size_bytes, status, uploaded_at) VALUES (:u, :k, :i, :key, :m, 10, :s,
                CASE WHEN :s = 'UPLOADED' THEN now() + make_interval(secs => :order) END) RETURNING id::text"""),
                {"u": owner, "k": kind, "i": incident, "key": f"{kind.lower()}/{owner}/{name}", "m": mime, "s": status,
                 "order": order})
    return incident, ids, friend_contact


async def test_alerted_circle_member_lists_and_downloads_sos_media(world):
    call, sessions, settings = world
    incident, ids, _ = await sos_with_media(call, sessions, settings)
    listed = await call("friend", "GET", f"/files/incidents/{incident}/media")
    assert listed.status_code == 200, listed.text
    items = listed.json()["items"]
    assert [i["file_id"] for i in items] == [ids["photo"], ids["voice"]]  # the unfinished upload is left out
    assert [i["kind"] for i in items] == ["SOS_PHOTO", "SOS_VOICE"]
    link = await call("friend", "GET", f"/files/{ids['voice']}/download-url")
    assert link.status_code == 200, link.text
    assert link.json()["download_url"].startswith("https://r2.example/") and link.json()["mime_type"] == "audio/mp4"
    # The owner still sees their own media.
    assert len((await call("owner", "GET", f"/files/incidents/{incident}/media")).json()["items"]) == 2


async def test_media_stays_available_after_the_sos_ends(world):
    call, sessions, settings = world
    incident, ids, _ = await sos_with_media(call, sessions, settings)
    async with sessions.begin() as db:
        await db.execute(text("UPDATE incidents SET status='CLOSED', ended_at=now() WHERE id=:id"), {"id": incident})
    assert (await call("friend", "GET", f"/files/{ids['photo']}/download-url")).status_code == 200


async def test_strangers_responders_and_removed_members_get_nothing(world):
    call, sessions, settings = world
    incident, ids, friend_contact = await sos_with_media(call, sessions, settings)
    await enable(call, "responder", 50)
    for outsider in ("stranger", "responder"):
        assert (await call(outsider, "GET", f"/files/incidents/{incident}/media")).status_code == 404
        assert (await call(outsider, "GET", f"/files/{ids['photo']}/download-url")).status_code == 404
    async with sessions.begin() as db:
        await db.execute(text("UPDATE circle_links SET revoked_at=now() WHERE contact_id=:c"), {"c": friend_contact})
    assert (await call("friend", "GET", f"/files/incidents/{incident}/media")).status_code == 404
    assert (await call("friend", "GET", f"/files/{ids['voice']}/download-url")).status_code == 404


async def test_circle_members_never_get_other_files(world):
    """Being in someone's circle doesn't open their profile photo or volunteer documents through this route."""
    call, sessions, settings = world
    incident, _, _ = await sos_with_media(call, sessions, settings)
    async with sessions.begin() as db:
        owner = await db.scalar(text("SELECT victim_user_id FROM incidents WHERE id=:id"), {"id": incident})
        doc = await db.scalar(text("""INSERT INTO stored_files(owner_user_id, kind, doc_type, object_key, mime_type,
            size_bytes, status) VALUES (:u, 'VOLUNTEER_DOC', 'GOVT_ID', 'volunteer_doc/x', 'image/jpeg', 10, 'UPLOADED')
            RETURNING id::text"""), {"u": owner})
    assert (await call("friend", "GET", f"/files/{doc}/download-url")).status_code == 404
