"""Zero-cost hosting: Neon URLs, R2 presigned storage, a database that may sleep, and optional Redis."""
from datetime import datetime, timezone
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

import pytest
from pydantic import SecretStr
from sqlalchemy.engine import make_url

from app.activity import DbActivity
from app.config import Settings
from app.database.engine import is_pooled, normalize_url
from app.storage import Bucket, presign
from tests.test_maitri_features import maitri_env  # noqa: F401  (fixture)


# --- S3 Signature V4 -----------------------------------------------------------------------------------------

def test_presign_matches_the_aws_published_example():
    # docs.aws.amazon.com "Authenticating Requests: Using Query Parameters (AWS Signature Version 4)".
    url = presign(method="GET", host="examplebucket.s3.amazonaws.com", path="/test.txt",
                  access_key="AKIAIOSFODNN7EXAMPLE", secret_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                  region="us-east-1", expires=86400, now=datetime(2013, 5, 24, tzinfo=timezone.utc))
    assert url.endswith("X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404")


def test_upload_url_signs_type_and_size_and_expires():
    bucket = Bucket("https://acct.r2.cloudflarestorage.com", "maitri-private", "key", "secret")
    url = bucket.url("PUT", "sos_photo/u/f", 300, {"Content-Type": "image/jpeg", "Content-Length": "1234"},
                     now=datetime(2026, 11, 24, tzinfo=timezone.utc))
    parts = urlsplit(url)
    query = parse_qs(parts.query)
    assert parts.netloc == "acct.r2.cloudflarestorage.com" and parts.path == "/maitri-private/sos_photo/u/f"
    assert query["X-Amz-SignedHeaders"] == ["content-length;content-type;host"]
    assert query["X-Amz-Expires"] == ["300"]
    assert query["X-Amz-Credential"][0].endswith("/20261124/auto/s3/aws4_request")


# --- Neon connection strings -----------------------------------------------------------------------------------

def test_neon_url_is_translated_for_asyncpg():
    url = normalize_url(make_url("postgresql+asyncpg://u:p@ep-cool-1.ap-southeast-1.aws.neon.tech/neondb"
                                 "?sslmode=require&channel_binding=require"))
    assert dict(url.query) == {"ssl": "require"}
    assert not is_pooled(url)
    assert is_pooled(make_url("postgresql+asyncpg://u:p@ep-cool-1-pooler.ap-southeast-1.aws.neon.tech/neondb"))


def test_plain_postgres_url_is_accepted_from_neon_console():
    settings = Settings(_env_file=None, firebase_project_id="p",
                        database_url="postgresql://u:p@ep-x.neon.tech/neondb?sslmode=require")
    assert settings.database_url.get_secret_value().startswith("postgresql+asyncpg://")


# --- Letting the database sleep -------------------------------------------------------------------------------

def test_background_loops_poll_only_while_busy_plus_an_hourly_sweep():
    now = [0.0]
    activity = DbActivity(idle_seconds=900, sweep_seconds=3600, clock=lambda: now[0])
    assert activity.should_poll("outbox") and activity.should_poll("geofence")
    now[0] = 1000  # 16+ minutes without a request
    assert not activity.busy() and not activity.should_poll("outbox")
    now[0] = 3600  # an hour after start: each loop gets one sweep
    assert activity.should_poll("outbox") and activity.should_poll("geofence")
    assert not activity.should_poll("outbox")
    activity.touch()  # an API request wakes everything again
    assert activity.should_poll("outbox") and activity.should_poll("geofence")


def test_ready_never_touches_the_database(maitri_env):
    client, _, _, _ = maitri_env

    class Asleep:
        def __call__(self, *args, **kwargs):
            raise AssertionError("/ready opened a database session")
        begin = __call__

    real = client.app.state.sessions
    client.app.state.sessions = Asleep()
    try:
        response = client.get("/ready")
    finally:
        client.app.state.sessions = real
    assert response.status_code == 200 and response.json()["status"] == "ready"
    assert client.get("/health/db").status_code == 200


def test_api_requests_mark_the_database_busy(maitri_env):
    client, _, _, _ = maitri_env
    activity = client.app.state.activity
    activity._last_request = -10_000  # pretend it has been idle
    assert not activity.busy()
    client.get("/ready")
    assert not activity.busy(), "keep-alive pings must not keep the database awake"
    client.get("/api/incidents/active")
    assert activity.busy()


# --- Private files through presigned URLs -----------------------------------------------------------------------

@pytest.fixture
def storage(maitri_env, monkeypatch):
    client, _, settings, _ = maitri_env
    settings.r2_account_id, settings.r2_bucket = "acct", "maitri-private"
    settings.r2_access_key_id, settings.r2_secret_access_key = "key", SecretStr("secret")
    stored: dict[str, int] = {}

    async def head(self, key):
        return stored.get(key)

    async def delete(self, key):
        stored.pop(key, None)

    monkeypatch.setattr(Bucket, "head", head)
    monkeypatch.setattr(Bucket, "delete", delete)
    return client, stored


def key_of(upload):
    return urlsplit(upload["upload_url"]).path.split("/", 2)[2]


def test_storage_off_means_uploads_stay_blocked(maitri_env):
    client, _, _, _ = maitri_env
    blocked = client.post("/api/files/upload-url", json={"kind": "PROFILE_PHOTO", "mime_type": "image/jpeg", "size_bytes": 10})
    assert blocked.status_code == 503 and blocked.json()["detail"]["code"] == "STORAGE_NOT_CONFIGURED"
    assert client.get("/api/volunteer/status").json()["secure_storage_ready"] is False


def test_profile_photo_round_trip(storage):
    client, stored = storage
    upload = client.post("/api/files/upload-url",
                         json={"kind": "PROFILE_PHOTO", "mime_type": "image/jpeg", "size_bytes": 2048}).json()
    assert upload["method"] == "PUT" and upload["headers"] == {"Content-Type": "image/jpeg", "Content-Length": "2048"}
    key = key_of(upload)
    assert key.startswith("profile_photo/") and "jpeg" not in key  # no names or file names in keys
    # Not uploaded yet.
    assert client.post(f"/api/files/{upload['file_id']}/complete").json()["detail"]["code"] == "UPLOAD_NOT_FOUND"
    assert client.get(f"/api/files/{upload['file_id']}/download-url").status_code == 409
    stored[key] = 2048
    assert client.post(f"/api/files/{upload['file_id']}/complete").json()["status"] == "UPLOADED"
    download = client.get(f"/api/files/{upload['file_id']}/download-url").json()
    assert "X-Amz-Signature=" in download["download_url"] and download["expires_in"] == 300
    # Nobody else can see it.
    assert client.get(f"/api/files/{upload['file_id']}/download-url",
                      headers={"X-Test-Actor": "RESPONDER_B"}).status_code == 404
    assert client.delete(f"/api/files/{upload['file_id']}").status_code == 204
    assert key not in stored


def test_wrong_size_is_refused(storage):
    client, stored = storage
    upload = client.post("/api/files/upload-url", json={"kind": "PROFILE_PHOTO", "mime_type": "image/png", "size_bytes": 500}).json()
    stored[key_of(upload)] = 999
    assert client.post(f"/api/files/{upload['file_id']}/complete").json()["detail"]["code"] == "UPLOAD_SIZE_MISMATCH"


def test_types_sizes_and_incident_ownership_are_checked(storage):
    client, _ = storage
    def ask(**body):
        return client.post("/api/files/upload-url", json=body)
    assert ask(kind="PROFILE_PHOTO", mime_type="application/pdf", size_bytes=10).json()["detail"]["code"] == "FILE_TYPE_NOT_ALLOWED"
    assert ask(kind="PROFILE_PHOTO", mime_type="image/png", size_bytes=11 * 1024 * 1024).json()["detail"]["code"] == "FILE_TOO_LARGE"
    assert ask(kind="SOS_PHOTO", mime_type="image/jpeg", size_bytes=10).status_code == 422  # needs an incident
    incident = client.post("/api/incidents", headers={"Idempotency-Key": str(uuid4())}, json={"is_test": True}).json()["id"]
    assert ask(kind="SOS_VOICE", mime_type="audio/mp4", size_bytes=10, incident_id=incident).status_code == 200
    other = client.post("/api/files/upload-url", headers={"X-Test-Actor": "RESPONDER_B"},
                        json={"kind": "SOS_PHOTO", "mime_type": "image/jpeg", "size_bytes": 10, "incident_id": incident})
    assert other.status_code == 404


def test_volunteer_document_marks_the_application(storage):
    client, stored = storage
    assert client.get("/api/volunteer/status").json()["secure_storage_ready"] is True
    upload = client.post("/api/volunteer/upload-document", json={"doc_type": "GOVT_ID", "file_name": "aadhaar.pdf",
                         "file_size_bytes": 4096, "mime_type": "application/pdf"}).json()
    assert key_of(upload).startswith("volunteer_doc/") and "aadhaar" not in upload["upload_url"]
    assert client.get("/api/volunteer/status").json()["has_govt_id"] is False
    stored[key_of(upload)] = 4096
    client.post(f"/api/files/{upload['file_id']}/complete")
    assert client.get("/api/volunteer/status").json()["has_govt_id"] is True
    client.delete(f"/api/files/{upload['file_id']}")
    assert client.get("/api/volunteer/status").json()["has_govt_id"] is False
