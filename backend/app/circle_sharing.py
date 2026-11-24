"""Authenticated ordinary sharing. Never creates SOS incidents or public location links."""
import hashlib
import json
import secrets
from datetime import datetime, timedelta, timezone
from typing import Literal
from uuid import UUID
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import Field
from sqlalchemy import text
from app.core import account, database
from app.schemas import Input, Fix
from app.inbox import add_item

router = APIRouter(prefix="/api/circle-sharing")


class PairingRequest(Input):
    owner_email: str = Field(min_length=3, max_length=320)
    consent: Literal[True]


class BindRequest(Input):
    contact_id: UUID
    code: str = Field(min_length=32, max_length=128)


class ShareRequest(Input):
    kind: Literal["TRIP", "CHECK_IN"]
    contact_ids: list[UUID] = Field(min_length=1, max_length=10)
    consent: Literal[True]
    location: Fix | None = None
    duration_minutes: int = Field(default=30, ge=5, le=120)
    # CHECK_IN: minutes from now until the owner must check in; the circle is told if they don't.
    due_minutes: int = Field(default=60, ge=5, le=1440)


def now():
    return datetime.now(timezone.utc)


async def one(db, sql, **params):
    return (await db.execute(text(sql), params)).mappings().first()


async def rate_limit(db, user_id, table, limit, interval):
    # account() already locks the owner user row through its upsert, serializing
    # concurrent requests by this identity. Table and interval are server constants.
    count = await db.scalar(text(f"SELECT count(*) FROM {table} WHERE "
        f"{'recipient_id' if table == 'circle_pairing_codes' else 'owner_id'}=:id "
        f"AND created_at > now() - interval '{interval}'"), {"id": user_id})
    if count >= limit:
        raise HTTPException(429, "Sharing limit reached. Try again later.")


@router.post("/pairing-code")
async def pairing(body: PairingRequest, request: Request, user=Depends(account), db=Depends(database)):
    await rate_limit(db, user.id, "circle_pairing_codes", 20, "1 hour")
    email = body.owner_email.strip()
    if "@" not in email:
        raise HTTPException(422, "Enter the circle owner's email address")
    # Email lookup only matches owners whose email is verified; phone-only accounts have no email.
    owner = await one(db, """SELECT id FROM users WHERE lower(email)=lower(:email)
        AND id<>:recipient AND account_status='ACTIVE' AND email_verified
        AND (CAST(:allowed AS text[]) IS NULL OR firebase_uid = ANY(CAST(:allowed AS text[])))""",
        email=email, recipient=user.id, allowed=request.app.state.settings.allowed_uids())
    if not owner:
        raise HTTPException(404, "Eligible sharing account unavailable")
    code = secrets.token_urlsafe(32)
    expiry = now() + timedelta(minutes=10)
    await db.execute(text("""INSERT INTO circle_pairing_codes(token_hash, recipient_id, owner_id, expires_at)
        VALUES (:hash,:recipient,:owner,:expires)"""),
        {"hash": hashlib.sha256(code.encode()).hexdigest(), "recipient": user.id, "owner": owner.id, "expires": expiry})
    return {"code": code, "expires_at": expiry}


@router.post("/links")
async def bind(body: BindRequest, user=Depends(account), db=Depends(database)):
    code = await one(db, """SELECT * FROM circle_pairing_codes WHERE token_hash=:hash
        AND owner_id=:owner AND expires_at>now() AND consumed_at IS NULL FOR UPDATE""",
        hash=hashlib.sha256(body.code.encode()).hexdigest(), owner=user.id)
    contact = await one(db, "SELECT id FROM trusted_contacts WHERE id=:id AND owner_user_id=:owner",
        id=body.contact_id, owner=user.id)
    if not code or not contact:
        raise HTTPException(404, "Pairing code or owned contact unavailable")
    # Re-pairing revokes existing sessions using the old link, rather than silently
    # redirecting an existing location grant to a different account.
    await db.execute(text("UPDATE circle_links SET revoked_at=now() WHERE contact_id=:id AND revoked_at IS NULL"),
        {"id": body.contact_id})
    link = await one(db, """INSERT INTO circle_links(owner_id,contact_id,recipient_id)
        VALUES (:owner,:contact,:recipient) RETURNING id""",
        owner=user.id, contact=body.contact_id, recipient=code.recipient_id)
    await db.execute(text("UPDATE circle_pairing_codes SET consumed_at=now() WHERE token_hash=:hash"),
        {"hash": code.token_hash})
    return {"id": link.id, "status": "PAIRED"}


@router.get("/links")
async def links(user=Depends(account), db=Depends(database)):
    rows = await db.execute(text("""SELECT l.id, l.contact_id, c.display_name, l.owner_id=:user AS is_owner
        FROM circle_links l JOIN trusted_contacts c ON c.id=l.contact_id
        WHERE (l.owner_id=:user OR l.recipient_id=:user) AND l.revoked_at IS NULL
        ORDER BY l.created_at DESC LIMIT 100"""), {"user": user.id})
    return [dict(row) for row in rows.mappings()]


@router.delete("/links/{link_id}")
async def revoke_link(link_id: UUID, user=Depends(account), db=Depends(database)):
    row = await one(db, """UPDATE circle_links SET revoked_at=coalesce(revoked_at,now())
        WHERE id=:id AND (owner_id=:user OR recipient_id=:user) RETURNING id""", id=link_id, user=user.id)
    if not row:
        raise HTTPException(404, "Sharing link unavailable")
    return {"status": "REVOKED"}


async def recipient_links(db, owner, contacts, allowed, trip):
    result = await db.execute(text("""SELECT l.id FROM circle_links l
        JOIN trusted_contacts c ON c.id=l.contact_id
        JOIN users u ON u.id=l.recipient_id
        WHERE l.owner_id=:owner AND c.owner_user_id=:owner
        AND c.id=ANY(CAST(:contacts AS uuid[])) AND l.revoked_at IS NULL
        AND (NOT :trip OR c.allow_live_tracking)
        AND u.account_status='ACTIVE' AND (u.email_verified OR u.phone_verified)
        AND (CAST(:allowed AS text[]) IS NULL OR u.firebase_uid = ANY(CAST(:allowed AS text[])))"""),
        {"owner": owner, "contacts": contacts, "allowed": allowed, "trip": trip})
    ids = list(result.scalars())
    if len(ids) != len(contacts):
        raise HTTPException(403, "Every selected contact must be paired and eligible for sharing")
    return ids


@router.post("/sessions")
async def create(body: ShareRequest, request: Request, idempotency_key: UUID = Header(),
                 user=Depends(account), db=Depends(database)):
    digest = hashlib.sha256(body.model_dump_json().encode()).hexdigest()
    previous = await one(db, """SELECT id, request_hash FROM circle_sessions
        WHERE owner_id=:owner AND idempotency_key=:key""", owner=user.id, key=idempotency_key)
    if previous:
        if previous.request_hash != digest:
            raise HTTPException(409, "Request key already used for different sharing details")
        return await snapshot(previous.id, request, user, db)
    await rate_limit(db, user.id, "circle_sessions", 50, "1 day")
    if len(set(body.contact_ids)) != len(body.contact_ids):
        raise HTTPException(422, "Duplicate contacts are not allowed")
    if body.location:
        body.location.check(request.app.state.settings)
    if body.kind == "TRIP" and body.location is None:
        raise HTTPException(422, "A fresh location is required to start a trip")
    recipients = await recipient_links(db, user.id, body.contact_ids,
        request.app.state.settings.allowed_uids(), body.kind == "TRIP")
    if body.kind == "TRIP" and await db.scalar(text("""SELECT id FROM circle_sessions
        WHERE owner_id=:owner AND kind='TRIP' AND stopped_at IS NULL AND expires_at>now() LIMIT 1"""),
        {"owner": user.id}):
        raise HTTPException(409, "An active trip already exists. Resume or stop it first.")
    due = now() + timedelta(minutes=body.due_minutes) if body.kind == "CHECK_IN" else None
    # A check-in stays visible to the circle for 12 hours after it was due.
    expiry = now() + timedelta(minutes=body.duration_minutes) if body.kind == "TRIP" else due + timedelta(hours=12)
    row = await one(db, """INSERT INTO circle_sessions
        (owner_id,kind,idempotency_key,request_hash,expires_at,latest_fix,fix_received_at,due_at)
        VALUES (:owner,:kind,:key,:hash,:expires,CAST(:fix AS jsonb),:received,:due) RETURNING id""",
        owner=user.id, kind=body.kind, key=idempotency_key, hash=digest, expires=expiry,
        fix=body.location.model_dump_json() if body.location else None, received=now() if body.location else None, due=due)
    owner_name = (user.display_name or "").strip() or "Someone in your circle"
    for link in recipients:
        await db.execute(text("INSERT INTO circle_session_recipients(session_id,link_id) VALUES (:session,:link)"),
            {"session": row.id, "link": link})
        recipient = await db.scalar(text("SELECT recipient_id FROM circle_links WHERE id=:id"), {"id": link})
        if body.kind == "TRIP":
            await add_item(db, recipient, "TRIP_STARTED", f"{owner_name} is sharing a trip with you",
                           f"Follow along in Maitri for the next {body.duration_minutes} minutes.",
                           data={"session_id": str(row.id)})
        else:
            await add_item(db, recipient, "CHECKIN_STARTED", f"{owner_name} set a check-in",
                           "Maitri will tell you if they don't check in on time.",
                           data={"session_id": str(row.id), "due_at": due.isoformat()})
    if body.kind == "CHECK_IN":
        wake_scheduler(request)
    return await snapshot(row.id, request, user, db)


async def visible(db, session_id, user, allowed):
    row = await one(db, """SELECT s.* FROM circle_sessions s JOIN users owner ON owner.id=s.owner_id
        WHERE s.id=:id AND owner.account_status='ACTIVE'
        AND (CAST(:allowed AS text[]) IS NULL OR owner.firebase_uid = ANY(CAST(:allowed AS text[])))
        AND (s.owner_id=:user OR (s.stopped_at IS NULL AND s.expires_at>now() AND EXISTS (
            SELECT 1 FROM circle_session_recipients r
            JOIN circle_links l ON l.id=r.link_id
            JOIN trusted_contacts c ON c.id=l.contact_id
            WHERE r.session_id=s.id AND l.recipient_id=:user AND l.revoked_at IS NULL
            AND (s.kind<>'TRIP' OR c.allow_live_tracking))))""",
        id=session_id, user=user.id, allowed=allowed)
    if not row:
        raise HTTPException(404, "Sharing session unavailable or access expired")
    return row


@router.get("/sessions/{session_id}")
async def snapshot(session_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    row = await visible(db, session_id, user, request.app.state.settings.allowed_uids())
    active = row.stopped_at is None and row.expires_at > now()
    status = "STOPPED" if row.stopped_at else ("ACTIVE" if active else "EXPIRED")
    result = {"id": row.id, "kind": row.kind, "status": status, "created_at": row.created_at,
        "expires_at": row.expires_at, "latest_fix": row.latest_fix if active else None,
        "due_at": row.due_at, "missed": row.missed_at is not None,
        "fix_received_at": row.fix_received_at if active else None, "is_owner": row.owner_id == user.id}
    if row.owner_id == user.id:
        recipients = await db.execute(text("""SELECT l.contact_id, r.notification_status, r.opened_at,
            l.revoked_at IS NOT NULL AS revoked FROM circle_session_recipients r
            JOIN circle_links l ON l.id=r.link_id WHERE r.session_id=:id"""), {"id": session_id})
        result["recipients"] = [dict(r) for r in recipients.mappings()]
    else:
        result["recipients"] = []
    return result


@router.get("/requests/{key}")
async def lookup_request(key: UUID, request: Request, user=Depends(account), db=Depends(database)):
    row = await one(db, "SELECT id FROM circle_sessions WHERE owner_id=:owner AND idempotency_key=:key",
        owner=user.id, key=key)
    if not row:
        raise HTTPException(404, "Sharing request not found")
    return await snapshot(row.id, request, user, db)


@router.get("/sessions")
async def sessions(request: Request, user=Depends(account), db=Depends(database)):
    rows = (await db.execute(text("""SELECT DISTINCT s.id, s.created_at FROM circle_sessions s
        LEFT JOIN circle_session_recipients r ON r.session_id=s.id
        LEFT JOIN circle_links l ON l.id=r.link_id
        WHERE s.owner_id=:user OR (l.recipient_id=:user AND l.revoked_at IS NULL
            AND s.stopped_at IS NULL AND s.expires_at>now())
        ORDER BY s.created_at DESC LIMIT 50"""), {"user": user.id})).mappings().all()
    results = []
    for row in rows:
        try:
            results.append(await snapshot(row.id, request, user, db))
        except HTTPException as error:
            if error.status_code != 404:
                raise
    return results


@router.post("/sessions/{session_id}/opened")
async def opened(session_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    await visible(db, session_id, user, request.app.state.settings.allowed_uids())
    await db.execute(text("""UPDATE circle_session_recipients r
        SET notification_status='OPENED', opened_at=coalesce(opened_at,now())
        FROM circle_links l WHERE r.link_id=l.id AND r.session_id=:id
        AND l.recipient_id=:user AND l.revoked_at IS NULL"""), {"id": session_id, "user": user.id})
    return {"status": "OPENED"}


@router.put("/sessions/{session_id}/location")
async def update_location(session_id: UUID, body: Fix, request: Request, user=Depends(account), db=Depends(database)):
    body.check(request.app.state.settings)
    row = await one(db, """SELECT * FROM circle_sessions WHERE id=:id AND owner_id=:owner FOR UPDATE""",
        id=session_id, owner=user.id)
    if not row:
        raise HTTPException(404, "Owned trip unavailable")
    if row.kind != "TRIP" or row.stopped_at or row.expires_at <= now():
        raise HTTPException(409, "Trip is no longer active")
    if row.latest_fix and body.recorded_at <= datetime.fromisoformat(row.latest_fix["recorded_at"].replace("Z", "+00:00")):
        raise HTTPException(409, "A newer location is already stored")
    await db.execute(text("""UPDATE circle_sessions SET latest_fix=CAST(:fix AS jsonb),fix_received_at=now()
        WHERE id=:id"""), {"id": session_id, "fix": body.model_dump_json()})
    return {"status": "SYNCED"}


def wake_scheduler(request: Request):
    scheduler = getattr(request.app.state, "checkins", None)
    if scheduler is not None:
        scheduler.wake()


@router.post("/sessions/{session_id}/stop")
async def stop(session_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    row = await one(db, """UPDATE circle_sessions SET stopped_at=coalesce(stopped_at,now()),
        latest_fix=NULL,fix_received_at=NULL WHERE id=:id AND owner_id=:owner RETURNING id""",
        id=session_id, owner=user.id)
    if not row:
        raise HTTPException(404, "Owned sharing session unavailable")
    wake_scheduler(request)
    return {"status": "STOPPED"}


async def cleanup(db):
    """Minimize retention: erase expired coordinates; records retained at most seven days."""
    await db.execute(text("UPDATE circle_sessions SET latest_fix=NULL,fix_received_at=NULL WHERE expires_at<=now() AND latest_fix IS NOT NULL"))
    await db.execute(text("DELETE FROM circle_sessions WHERE expires_at<now()-interval '7 days'"))
    await db.execute(text("DELETE FROM circle_pairing_codes WHERE expires_at<now()-interval '1 day'"))
