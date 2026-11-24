"""Durable notification outbox worker for FCM delivery with retry and token lifecycle management.

Follows the critical rule: no database transactions or locks are held during external FCM network calls.
"""
import asyncio
import hashlib
from datetime import datetime, timedelta, timezone
import logging
from sqlalchemy import text

logger = logging.getLogger("surakshanet.outbox")

PERMANENT_FCM_ERROR_CODES = {
    "registration-token-not-registered",
    "invalid-registration-token",
    "invalid-argument",
    "mismatched-credential",
}


class FcmSendResult:
    def __init__(self, token: str, message_id: str | None = None, error_code: str | None = None, is_permanent: bool = False):
        self.token = token
        self.message_id = message_id
        self.error_code = error_code
        self.is_permanent = is_permanent

    @property
    def success(self) -> bool:
        return self.message_id is not None


def default_firebase_sender(tokens: list[str], title: str, body: str, data: dict[str, str], firebase_app) -> list[FcmSendResult]:
    """Sends FCM high-priority data/notification messages using Firebase Admin SDK."""
    if not tokens or firebase_app is None:
        return []
    try:
        from firebase_admin import messaging
    except ImportError:
        logger.warning("firebase_admin not available for FCM send")
        return [FcmSendResult(token=t, error_code="import-error", is_permanent=False) for t in tokens]

    messages = [
        messaging.Message(
            token=token,
            data=data,
            android=messaging.AndroidConfig(priority="high"),
        )
        for token in tokens
    ]

    try:
        batch_response = messaging.send_each(messages, app=firebase_app)
        results = []
        for idx, resp in enumerate(batch_response.responses):
            token = tokens[idx]
            if resp.success:
                results.append(FcmSendResult(token=token, message_id=resp.message_id))
            else:
                exc = resp.exception
                error_code = getattr(exc, "code", str(exc)).lower()
                is_perm = (
                    isinstance(exc, (messaging.UnregisteredError, messaging.SenderIdMismatchError))
                    or any(code in error_code for code in PERMANENT_FCM_ERROR_CODES)
                )
                results.append(FcmSendResult(token=token, error_code=error_code, is_permanent=is_perm))
        return results
    except Exception as exc:
        logger.error("Firebase batch send failed")
        return [FcmSendResult(token=t, error_code="batch-failure", is_permanent=False) for t in tokens]


async def claim_pending_work(db, batch_size=25, *, allowed_uids=frozenset()) -> list[dict]:
    """Atomically claim pending or retry-scheduled outbox records using FOR UPDATE SKIP LOCKED."""
    claim_sql = """
        UPDATE notification_outbox
        SET delivery_status = 'SENDING',
            last_attempt_at = clock_timestamp(),
            attempt_count = attempt_count + 1
        WHERE id IN (
            SELECT id FROM notification_outbox
            WHERE (delivery_status = 'PENDING' OR
                  (delivery_status = 'SENDING' AND last_attempt_at < clock_timestamp() - interval '90 seconds') OR
                  (delivery_status = 'RETRY_SCHEDULED' AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())))
              AND EXISTS (
                  SELECT 1 FROM incidents i
                  JOIN users owner ON owner.id = i.victim_user_id
                  JOIN users recipient ON recipient.id = notification_outbox.recipient_user_id
                  WHERE i.id = notification_outbox.incident_id
                    AND (CAST(:allowed AS text[]) IS NULL OR owner.firebase_uid = ANY(CAST(:allowed AS text[])))
                    AND (CAST(:allowed AS text[]) IS NULL OR recipient.firebase_uid = ANY(CAST(:allowed AS text[])))
              )
            ORDER BY created_at
            LIMIT :batch
            FOR UPDATE SKIP LOCKED
        )
        RETURNING id, invitation_id, incident_id, recipient_user_id, event_type, attempt_count, accepted_token_hashes
    """
    rows = (await db.execute(text(claim_sql), {"batch": batch_size, "allowed": None if allowed_uids is None else list(allowed_uids)})).mappings().all()
    return [dict(r) for r in rows]


async def fetch_item_context(db, item: dict) -> dict:
    """Fetch incident, invitation, recipient eligibility, and active device tokens."""
    query = """
        SELECT
            i.status AS incident_status,
            i.is_test AS incident_is_test,
            v.status AS invitation_status,
            v.expires_at AS invitation_expires_at,
            v.invited_at AS invitation_invited_at,
            u.account_status AS user_status,
            (u.email_verified OR u.phone_verified) AS user_email_verified,
            u.responder_opt_in AS user_opt_in,
            u.responder_available AS user_available,
            COALESCE(array_agg(d.fcm_token) FILTER (WHERE d.fcm_token IS NOT NULL AND d.is_active), '{}') AS active_tokens
        FROM notification_outbox o
        JOIN incidents i ON i.id = o.incident_id
        JOIN incident_invitations v ON v.id = o.invitation_id
        JOIN users u ON u.id = o.recipient_user_id
        LEFT JOIN devices d ON d.user_id = u.id AND d.is_active = true
        WHERE o.id = :id AND o.attempt_count = :attempt AND o.delivery_status = 'SENDING'
        GROUP BY i.status, i.is_test, v.status, v.expires_at, v.invited_at, u.account_status, u.email_verified, u.phone_verified, u.responder_opt_in, u.responder_available
    """
    row = (await db.execute(text(query), {"id": item["id"], "attempt": item["attempt_count"]})).mappings().first()
    return dict(row) if row else {}


async def update_item_status(db, item, status: str, fcm_message_id: str | None = None,
                             failure_reason: str | None = None, next_attempt_at=None,
                             invalid_tokens: list[str] | None = None, accepted_hashes=()):
    """Write back the send/cancellation result and deactivate permanently invalid device tokens."""
    update_sql = """
        UPDATE notification_outbox
        SET delivery_status = :status,
            fcm_message_id = coalesce(:fcm_message_id, fcm_message_id),
            accepted_token_hashes = accepted_token_hashes || CAST(:accepted AS text[]),
            failure_reason = :failure_reason,
            next_attempt_at = :next_attempt_at
        WHERE id = :id AND attempt_count = :attempt AND delivery_status = 'SENDING'
    """
    updated = await db.execute(text(update_sql), {
        "id": item["id"], "attempt": item["attempt_count"],
        "status": status, "accepted": list(accepted_hashes),
        "fcm_message_id": fcm_message_id,
        "failure_reason": failure_reason,
        "next_attempt_at": next_attempt_at,
    })
    if updated.rowcount and invalid_tokens:
        await db.execute(
            text("UPDATE devices SET is_active = false, disabled_at = clock_timestamp() WHERE fcm_token = ANY(:tokens)"),
            {"tokens": invalid_tokens}
        )


async def process_outbox_item(db_factory, item: dict, firebase_app, fcm_sender=None) -> str:
    """Process a single claimed item outside of any long transaction."""
    sender = fcm_sender or default_firebase_sender

    # Step 1: Read context in a quick read transaction
    async with db_factory() as db:
        ctx = await fetch_item_context(db, item)

    if not ctx:
        async with db_factory() as db:
            await update_item_status(db, item, "PERMANENT_FAILURE", failure_reason="CONTEXT_MISSING")
            await db.commit()
        return "PERMANENT_FAILURE"

    now = datetime.now(timezone.utc)

    # Step 2: Validate preconditions
    if ctx["incident_status"] != "ACTIVE":
        async with db_factory() as db:
            await update_item_status(db, item, "CANCELLED", failure_reason="INCIDENT_INACTIVE")
            await db.commit()
        return "CANCELLED"

    if ctx["invitation_status"] != "PENDING" or ctx["invitation_expires_at"] <= now:
        async with db_factory() as db:
            await update_item_status(db, item, "CANCELLED", failure_reason="INVITATION_EXPIRED_OR_ANSWERED")
            await db.commit()
        return "CANCELLED"

    if (ctx["user_status"] != "ACTIVE" or not ctx["user_email_verified"] or
            not ctx["user_opt_in"] or not ctx["user_available"]):
        async with db_factory() as db:
            await update_item_status(db, item, "CANCELLED", failure_reason="RECIPIENT_INELIGIBLE")
            await db.commit()
        return "CANCELLED"

    def fingerprint(token):
        return hashlib.sha256(token.encode()).hexdigest()

    previous = set(item["accepted_token_hashes"])
    active_tokens = [t for t in ctx.get("active_tokens") or [] if fingerprint(t) not in previous]
    if not active_tokens and previous:
        async with db_factory.begin() as db:
            await update_item_status(db, item, "FCM_ACCEPTED")
        return "FCM_ACCEPTED"
    if not active_tokens:
        max_attempts = 5
        if item["attempt_count"] >= max_attempts:
            final_status = "PERMANENT_FAILURE"
            reason = "NO_ACTIVE_DEVICES_MAX_ATTEMPTS"
            next_attempt = None
        else:
            final_status = "RETRY_SCHEDULED"
            reason = "NO_ACTIVE_DEVICES"
            next_attempt = now + timedelta(seconds=min(300, 2 ** item["attempt_count"] * 5))
        async with db_factory() as db:
            await update_item_status(db, item, final_status, failure_reason=reason, next_attempt_at=next_attempt)
            await db.commit()
        return final_status

    # Step 3: Construct privacy-safe payload (strictly NO GPS coordinates)
    payload_data = {
        "event_type": str(item["event_type"]),
        "invitation_id": str(item["invitation_id"]),
        "incident_id": str(item["incident_id"]),
        "created_at": ctx.get("invitation_invited_at", now).isoformat() if hasattr(ctx.get("invitation_invited_at"), "isoformat") else str(ctx.get("invitation_invited_at", "")),
        "expires_at": ctx.get("invitation_expires_at", now).isoformat() if hasattr(ctx.get("invitation_expires_at"), "isoformat") else str(ctx.get("invitation_expires_at", "")),
    }

    title = "Nearby SOS Alert"
    body = "Someone nearby has requested emergency assistance."

    # Step 4: External network call to FCM (NO DB TRANSACTION HELD!)
    results: list[FcmSendResult] = await asyncio.to_thread(sender, active_tokens, title, body, payload_data, firebase_app)

    # Step 5: Evaluate results and write back
    successful = [r for r in results if r.success]
    invalid_tokens = [r.token for r in results if r.is_permanent]

    remaining = len(active_tokens) - len(successful) - len(invalid_tokens)
    if (previous or successful) and (remaining == 0 or item["attempt_count"] >= 5):
        final_status = "FCM_ACCEPTED"
        message_id = successful[0].message_id if successful else None
        reason = "PARTIAL_DELIVERY" if remaining else None
        next_attempt = None
    elif len(invalid_tokens) == len(active_tokens):
        final_status = "PERMANENT_FAILURE"
        message_id = None
        reason = "ALL_DEVICES_INVALID"
        next_attempt = None
    else:
        max_attempts = 5
        if item["attempt_count"] >= max_attempts:
            final_status = "PERMANENT_FAILURE"
            reason = "MAX_RETRIES_EXCEEDED"
            next_attempt = None
        else:
            final_status = "RETRY_SCHEDULED"
            reason = "TRANSIENT_FAILURE"
            next_attempt = now + timedelta(seconds=2 ** item["attempt_count"])
        message_id = successful[0].message_id if successful else None

    async with db_factory() as db:
        await update_item_status(
            db,
            item,
            final_status,
            fcm_message_id=message_id,
            failure_reason=reason,
            next_attempt_at=next_attempt,
            invalid_tokens=invalid_tokens,
            accepted_hashes=[fingerprint(result.token) for result in successful],
        )
        await db.commit()

    return final_status


async def process_outbox_batch(session_factory, firebase_app, batch_size=25, fcm_sender=None, *, allowed_uids=frozenset()) -> int:
    """Claims a batch of pending notifications and processes each item."""
    async with session_factory() as db:
        async with db.begin():
            claimed = await claim_pending_work(db, batch_size=batch_size, allowed_uids=allowed_uids)

    if not claimed:
        return 0

    for item in claimed:
        try:
            await process_outbox_item(session_factory, item, firebase_app, fcm_sender=fcm_sender)
        except Exception as err:
            logger.error("Outbox processing interrupted (%s)", type(err).__name__)
    return len(claimed)
