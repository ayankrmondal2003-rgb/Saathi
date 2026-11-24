"""Isolated volunteer verification lifecycle and secure document boundary tests."""
import pytest
from test_trusted_contacts import client_env


def headers(actor="victim"):
    return {"X-Test-Actor": actor}


def test_volunteer_status_default(client_env):
    client, _ = client_env
    res = client.get("/api/volunteer/status", headers=headers("victim"))
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "NOT_STARTED"
    assert data["intent"] == "HELP"
    assert data["secure_storage_ready"] is False
    assert data["consent_declared"] is False
    assert data["has_govt_id"] is False


def test_volunteer_intent_help_creates_draft(client_env):
    client, _ = client_env
    res = client.post("/api/volunteer/intent", headers=headers("victim"), json={"intent": "HELP"})
    assert res.status_code == 200
    assert res.json()["intent"] == "HELP"

    status = client.get("/api/volunteer/status", headers=headers("victim")).json()
    assert status["status"] == "DRAFT"
    assert status["intent"] == "HELP"


def test_volunteer_intent_not_now_skips_without_enrollment(client_env):
    client, _ = client_env
    res = client.post("/api/volunteer/intent", headers=headers("friend"), json={"intent": "NOT_NOW"})
    assert res.status_code == 200
    assert res.json()["intent"] == "NOT_NOW"

    status = client.get("/api/volunteer/status", headers=headers("friend")).json()
    assert status["status"] == "NOT_STARTED"
    assert status["intent"] == "NOT_NOW"

    # User must not be enrolled or activated as responder
    user_me = client.get("/api/users/me", headers=headers("friend")).json()
    assert user_me.get("is_responder", False) is False


def test_volunteer_consent_update(client_env):
    client, _ = client_env
    res = client.post("/api/volunteer/consent", headers=headers("victim"), json={"consent_declared": True})
    assert res.status_code == 200
    assert res.json()["consent_declared"] is True

    status = client.get("/api/volunteer/status", headers=headers("victim")).json()
    assert status["consent_declared"] is True


def test_volunteer_upload_blocked_pending_secure_storage(client_env):
    client, _ = client_env
    # Live identity documents must NOT be accepted on unreviewed or unencrypted endpoints
    res = client.post(
        "/api/volunteer/upload-document",
        headers=headers("victim"),
        json={
            "doc_type": "GOVT_ID",
            "file_name": "aadhaar.pdf",
            "file_size_bytes": 102400,
            "mime_type": "application/pdf"
        }
    )
    assert res.status_code == 503
    assert res.json()["detail"]["code"] == "STORAGE_NOT_CONFIGURED"


def test_volunteer_submit_validation(client_env):
    client, _ = client_env
    # Initialize draft application
    client.post("/api/volunteer/intent", headers=headers("victim"), json={"intent": "HELP"})

    # Submitting without consent must fail with 422
    res_no_consent = client.post("/api/volunteer/submit", headers=headers("victim"))
    assert res_no_consent.status_code == 422
    assert "Declaration and consent must be accepted" in res_no_consent.json()["detail"]

    # Declare consent, but submit without required documents
    client.post("/api/volunteer/consent", headers=headers("victim"), json={"consent_declared": True})
    res_no_docs = client.post("/api/volunteer/submit", headers=headers("victim"))
    assert res_no_docs.status_code == 422
    assert "Missing required documents" in res_no_docs.json()["detail"]
