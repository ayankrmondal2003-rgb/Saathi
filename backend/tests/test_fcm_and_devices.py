"""Automated tests for Phase 6 FCM device registration, outbox worker, delivery tracking, and security."""
import asyncio
from datetime import datetime, timedelta, timezone
from uuid import uuid4
import pytest
from sqlalchemy import text
from app.services.outbox_worker import FcmSendResult, process_outbox_batch
from tests.test_moving_geofence import create, enable, fix, world

pytestmark = pytest.mark.asyncio


async def test_device_registration_and_lifecycle(world):
    call, sessions, settings = world

    # 1. Register device for user A
    token_a = "fcm-token-" + uuid4().hex
    resp = await call("user-a", "POST", "/devices/register", json={
        "fcm_token": token_a,
        "platform": "android",
        "device_identifier": "pixel-7-a",
    })
    assert resp.status_code == 200
    assert resp.json()["status"] == "REGISTERED"

    async with sessions.begin() as db:
        dev = (await db.execute(text("SELECT user_id, fcm_token, is_active, platform FROM devices WHERE fcm_token = :token"),
                                {"token": token_a})).mappings().first()
        assert dev is not None
        assert dev["is_active"] is True
        assert dev["platform"] == "ANDROID"

    # 2. Token rotation / device reuse: user B logs into the same device with the same FCM token
    resp_b = await call("user-b", "POST", "/devices/register", json={
        "fcm_token": token_a,
        "platform": "android",
        "device_identifier": "pixel-7-b",
    })
    assert resp_b.status_code == 200

    async with sessions.begin() as db:
        # User A's registration with this token should be deactivated
        old_dev = (await db.execute(text("""SELECT is_active FROM devices d JOIN users u ON u.id = d.user_id
                                           WHERE u.display_name = 'user-a' AND d.fcm_token = :token"""),
                                    {"token": token_a})).scalar()
        assert old_dev is False

        # User B's registration should be active
        new_dev = (await db.execute(text("""SELECT is_active FROM devices d JOIN users u ON u.id = d.user_id
                                           WHERE u.display_name = 'user-b' AND d.fcm_token = :token"""),
                                    {"token": token_a})).scalar()
        assert new_dev is True

    # 3. Multiple devices per user: user B adds a second device
    token_b2 = "fcm-token-" + uuid4().hex
    assert (await call("user-b", "POST", "/devices/register", json={
        "fcm_token": token_b2,
        "platform": "android",
        "device_identifier": "tablet-b",
    })).status_code == 200

    async with sessions.begin() as db:
        active_count = await db.scalar(text("""SELECT count(*) FROM devices d JOIN users u ON u.id = d.user_id
                                              WHERE u.display_name = 'user-b' AND d.is_active = true"""))
        assert active_count == 2

    # 4. Unregister device
    del_resp = await call("user-b", "DELETE", f"/devices/{token_b2}")
    assert del_resp.status_code == 204

    async with sessions.begin() as db:
        active_count = await db.scalar(text("""SELECT count(*) FROM devices d JOIN users u ON u.id = d.user_id
                                              WHERE u.display_name = 'user-b' AND d.is_active = true"""))
        assert active_count == 1


async def test_test_alert_endpoint(world, monkeypatch):
    call, sessions, settings = world

    # Without registered device: returns 404
    assert (await call("alert-user", "POST", "/devices/test-phone/test-alert")).status_code == 404

    # Register device
    token = "fcm-alert-token-" + uuid4().hex
    assert (await call("alert-user", "POST", "/devices/register", json={"fcm_token": token, "device_identifier": "test-phone"})).status_code == 200

    # Test alert captured via mock sender
    sent_messages = []

    def mock_sender(tokens, title, body, data, firebase_app):
        sent_messages.append({"tokens": tokens, "title": title, "body": body, "data": data})
        return [FcmSendResult(token=t, message_id=f"msg-{t}") for t in tokens]

    assert (await call("alert-user", "POST", "/devices/test-phone/test-alert")).status_code == 503
    monkeypatch.setattr("app.routes.default_firebase_sender", mock_sender)
    assert (await call("alert-user", "POST", "/devices/test-phone/test-alert")).status_code == 200
    payload = sent_messages[0]["data"]
    assert payload["event_type"] == "TEST_ALERT" and payload["notification_id"]
    assert "invitation_id" not in payload and "incident_id" not in payload


async def test_outbox_worker_successful_delivery_and_privacy(world):
    call, sessions, settings = world

    # Setup responder with registered active device
    resp_token = "fcm-responder-" + uuid4().hex
    await enable(call, "responder-1", 500)
    assert (await call("responder-1", "POST", "/devices/register", json={"fcm_token": resp_token})).status_code == 200

    # Victim creates incident
    inc_resp = await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    incident_id = inc_resp.json()["id"]

    # Verify outbox item was created with PENDING
    async with sessions.begin() as db:
        outbox = (await db.execute(text("SELECT id, delivery_status, attempt_count FROM notification_outbox"))).mappings().one()
        assert outbox["delivery_status"] == "PENDING"
        assert outbox["attempt_count"] == 0

    # Mock FCM sender that asserts payload privacy
    captured_payloads = []

    def mock_fcm_success(tokens, title, body, data, firebase_app):
        captured_payloads.append({"tokens": tokens, "title": title, "body": body, "data": data})
        return [FcmSendResult(token=t, message_id="fcm-msg-id-123") for t in tokens]

    assert await process_outbox_batch(sessions, None, fcm_sender=mock_fcm_success) == 0
    assert captured_payloads == []

    # Run outbox worker batch
    processed = await process_outbox_batch(sessions, None, batch_size=10, fcm_sender=mock_fcm_success, allowed_uids=settings.test_account_uids)
    assert processed == 1

    # Verify privacy guarantees: no GPS coordinates in data payload
    assert len(captured_payloads) == 1
    sent_data = captured_payloads[0]["data"]
    assert "latitude" not in sent_data
    assert "longitude" not in sent_data
    assert "lat" not in sent_data
    assert "lon" not in sent_data
    assert sent_data["event_type"] == "RESPONDER_INVITATION_CREATED"
    inbox = (await call("responder-1", "GET", "/responders/invitations")).json()
    assert sent_data["invitation_id"] == inbox[0]["id"] != incident_id
    assert sent_data["incident_id"] == incident_id

    # Verify outbox status updated to FCM_ACCEPTED and attempt_count incremented
    async with sessions.begin() as db:
        outbox = (await db.execute(text("SELECT delivery_status, attempt_count, fcm_message_id FROM notification_outbox"))).mappings().one()
        assert outbox["delivery_status"] == "FCM_ACCEPTED"
        assert outbox["attempt_count"] == 1
        assert outbox["fcm_message_id"] == "fcm-msg-id-123"


async def test_outbox_worker_transient_retry_and_permanent_failure(world):
    call, sessions, settings = world

    # Setup responder with token
    token = "fcm-retry-token-" + uuid4().hex
    await enable(call, "retry-user", 300)
    await call("retry-user", "POST", "/devices/register", json={"fcm_token": token})

    # Create incident -> creates outbox record
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})

    # 1. Transient failure -> RETRY_SCHEDULED
    def mock_transient(tokens, title, body, data, app):
        return [FcmSendResult(token=t, error_code="unavailable", is_permanent=False) for t in tokens]

    assert await process_outbox_batch(sessions, None, fcm_sender=mock_transient, allowed_uids=settings.test_account_uids) == 1

    async with sessions.begin() as db:
        outbox = (await db.execute(text("SELECT delivery_status, attempt_count, next_attempt_at FROM notification_outbox"))).mappings().one()
        assert outbox["delivery_status"] == "RETRY_SCHEDULED"
        assert outbox["attempt_count"] == 1
        assert outbox["next_attempt_at"] is not None

        # Backdate next_attempt_at to make it eligible immediately
        await db.execute(text("UPDATE notification_outbox SET next_attempt_at = clock_timestamp() - interval '1 second'"))

    # 2. Permanent failure (e.g. Unregistered token) -> PERMANENT_FAILURE and deactivates token
    def mock_permanent(tokens, title, body, data, app):
        return [FcmSendResult(token=t, error_code="registration-token-not-registered", is_permanent=True) for t in tokens]

    assert await process_outbox_batch(sessions, None, fcm_sender=mock_permanent, allowed_uids=settings.test_account_uids) == 1

    async with sessions.begin() as db:
        outbox = (await db.execute(text("SELECT delivery_status, failure_reason FROM notification_outbox"))).mappings().one()
        assert outbox["delivery_status"] == "PERMANENT_FAILURE"
        assert outbox["failure_reason"] == "ALL_DEVICES_INVALID"

        # Check device token was deactivated
        is_active = await db.scalar(text("SELECT is_active FROM devices WHERE fcm_token = :token"), {"token": token})
        assert is_active is False


async def test_outbox_worker_cancels_on_closed_incident(world):
    call, sessions, settings = world

    await enable(call, "cancel-responder", 200)
    token = "fcm-cancel-token-" + uuid4().hex
    await call("cancel-responder", "POST", "/devices/register", json={"fcm_token": token})

    inc = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]

    # Close the incident before outbox is processed
    path = f"/incidents/{inc}"
    assert (await call("owner", "POST", "/security/safety-pin/setup", json={"pin": "839162", "confirm_pin": "839162"})).is_success
    assert (await call("owner", "POST", path + "/resolve", json={"pin": "839162"})).is_success

    # Run outbox worker
    called = []
    def mock_sender(tokens, title, body, data, app):
        called.append(tokens)
        return []

    await process_outbox_batch(sessions, None, fcm_sender=mock_sender, allowed_uids=settings.test_account_uids)

    # Assert no FCM call was made
    assert len(called) == 0

    # Assert outbox is CANCELLED
    async with sessions.begin() as db:
        status = await db.scalar(text("SELECT delivery_status FROM notification_outbox"))
        assert status == "CANCELLED"


async def test_closure_while_fcm_call_is_in_flight(world):
    from threading import Event
    call, sessions, settings = world
    await enable(call, "helper", 300)
    assert (await call("helper", "POST", "/devices/register", json={"fcm_token": "fixture-token-helper"})).is_success
    pin = {"pin": "839162", "confirm_pin": "839162"}
    assert (await call("owner", "POST", "/security/safety-pin/setup", json=pin)).is_success
    incident = (await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})).json()["id"]
    sending, release = Event(), Event()

    def delayed(tokens, title, body, data, app):
        sending.set()
        assert release.wait(10)
        return [FcmSendResult(token=t, message_id="fixture-message") for t in tokens]

    worker = asyncio.create_task(process_outbox_batch(sessions, None, fcm_sender=delayed,
                                                       allowed_uids=settings.test_account_uids))
    try:
        assert await asyncio.to_thread(sending.wait, 5)
        result = await call("owner", "POST", f"/incidents/{incident}/resolve", json={"pin": pin["pin"]})
        assert result.is_success
    finally:
        release.set()
        await worker
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT delivery_status FROM notification_outbox")) == "CANCELLED"


async def test_reclaim_abandoned_send_and_ignore_old_completion(world):
    from app.services.outbox_worker import claim_pending_work, update_item_status
    call, sessions, settings = world
    await enable(call, "recover-helper", 200)
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    async with sessions.begin() as db:
        first, = await claim_pending_work(db, allowed_uids=settings.test_account_uids)
    async with sessions.begin() as db:
        assert not await claim_pending_work(db, allowed_uids=settings.test_account_uids)
        await db.execute(text("UPDATE notification_outbox SET last_attempt_at=now()-interval '2 minutes'"))
    async with sessions.begin() as db:
        assert not await claim_pending_work(db, allowed_uids=set())
        second, = await claim_pending_work(db, allowed_uids=settings.test_account_uids)
        assert second["id"] == first["id"]
        assert second["attempt_count"] == first["attempt_count"] + 1
        await update_item_status(db, first, "PERMANENT_FAILURE")
        assert await db.scalar(text("SELECT delivery_status FROM notification_outbox")) == "SENDING"
        await update_item_status(db, second, "FCM_ACCEPTED", fcm_message_id="fixture-only")
        assert await db.scalar(text("SELECT delivery_status FROM notification_outbox")) == "FCM_ACCEPTED"


async def test_mixed_delivery_retries_only_unaccepted_device(world):
    call, sessions, settings = world
    await enable(call, "helper", 200)
    first, second = "fixture-device-one", "fixture-device-two"
    for token in [first, second]:
        assert (await call("helper", "POST", "/devices/register", json={"fcm_token": token})).is_success
    await create(call, str(uuid4()), {"is_test": True, "location": fix(0)})
    deliveries = []

    def mixed(tokens, title, body, data, app):
        deliveries.append(set(tokens))
        return [FcmSendResult(token=t, message_id="accepted" if t == first or len(deliveries) == 2 else None)
                for t in tokens]

    await process_outbox_batch(sessions, None, fcm_sender=mixed, allowed_uids=settings.test_account_uids)
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT delivery_status FROM notification_outbox")) == "RETRY_SCHEDULED"
        await db.execute(text("UPDATE notification_outbox SET next_attempt_at=now()"))
    await process_outbox_batch(sessions, None, fcm_sender=mixed, allowed_uids=settings.test_account_uids)
    assert deliveries == [{first, second}, {second}]
    async with sessions.begin() as db:
        assert await db.scalar(text("SELECT delivery_status FROM notification_outbox")) == "FCM_ACCEPTED"



