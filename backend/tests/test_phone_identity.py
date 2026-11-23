"""Release phase 1: accounts verified by phone OTP (Firebase Phone Auth) are first-class users."""
import asyncio
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials

import app.core as core
from app.core import Identity, identity
from tests.test_maitri_features import create_test_sos_incident, maitri_env  # noqa: F401  (fixture)


def _request(allowed):
    return SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(
        firebase=None, settings=SimpleNamespace(test_account_uids=set(allowed)))))


def _identity_for(claims, monkeypatch):
    monkeypatch.setattr(core.auth, "verify_id_token", lambda *args, **kwargs: claims)
    creds = HTTPAuthorizationCredentials(scheme="Bearer", credentials="token")
    return asyncio.run(identity(_request([claims["uid"]]), creds))


def test_phone_verified_token_is_accepted_without_email(monkeypatch):
    who = _identity_for({"uid": "u-phone", "phone_number": "+919876500001", "auth_time": 1,
                         "firebase": {"sign_in_provider": "phone"}}, monkeypatch)
    assert (who.email, who.verified, who.phone, who.phone_verified) == ("", False, "+919876500001", True)


def test_email_verified_token_still_works(monkeypatch):
    who = _identity_for({"uid": "u-mail", "email": "a@example.invalid", "email_verified": True}, monkeypatch)
    assert (who.email, who.verified, who.phone_verified) == ("a@example.invalid", True, False)


def test_unverified_email_without_phone_is_rejected(monkeypatch):
    with pytest.raises(HTTPException) as denied:
        _identity_for({"uid": "u-x", "email": "x@example.invalid", "email_verified": False}, monkeypatch)
    assert denied.value.status_code == 403 and denied.value.detail == "Verified email or phone required"


def test_phone_only_account_uses_the_api_end_to_end(maitri_env):
    client, _, settings, prefix = maitri_env
    uid = prefix + "uid-phone-only"
    settings.test_account_uids.add(uid)
    previous = client.app.dependency_overrides[identity]
    client.app.dependency_overrides[identity] = lambda: Identity(uid, "", "Phone User", False, 0, "+919876500001", True)
    try:
        me = client.get("/api/users/me").json()
        # The verified number pre-fills the profile; there is no email for a phone-only account.
        assert (me["email"], me["email_verified"], me["phone_number"]) == ("", False, "+919876500001")
        # An edited number is kept on later sign-ins.
        assert client.patch("/api/users/me", json={"phone_number": "+91 90000 11111"}).status_code == 200
        assert client.get("/api/users/me").json()["phone_number"] == "+91 90000 11111"
        # Phone-only accounts can raise a (test) SOS and read its snapshot as the owner.
        incident = client.post("/api/incidents", headers={"Idempotency-Key": "8f5d1b0e-1c2a-4b7e-9a51-3c0d2f4e6a7b"},
                               json={"is_test": True}).json()
        snapshot = client.get(f"/api/incidents/{incident['id']}/snapshot")
        assert snapshot.status_code == 200 and snapshot.json()["requester_membership"]["role"] == "OWNER"
    finally:
        client.app.dependency_overrides[identity] = previous

    # Blank or non-email pairing input can no longer match phone-only accounts (whose email is empty).
    for owner_email in ("   ", "", "+919876500001"):
        bad = client.post("/api/circle-sharing/pairing-code", headers={"X-Test-Actor": "VICTIM_A"},
                          json={"owner_email": owner_email})
        assert bad.status_code == 422, owner_email
