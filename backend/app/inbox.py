"""In-app inbox (screen 02 bell) and the pushes that announce its items.

Every circle alert, check-in reminder or circle request is an inbox row first; the row's push_status makes
it its own push queue. Pending responder invitations are listed from incident_invitations at read time, so
they are never stored twice. Pushes are FCM data messages: no coordinates, only ids and short text.
"""
import asyncio
import json
import logging
from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import text

from app.core import account, database

router = APIRouter(prefix="/api/inbox")
# Push-only rows: they use the push queue (retries, delivery) but never appear in the inbox or its unread count.
HIDDEN_KINDS = ("SOS_REQUEST_CLOSED",)
log = logging.getLogger("surakshanet.inbox")
MAX_PUSH_ATTEMPTS = 6


async def add_item(db, user_id, kind: str, title: str, body: str, *, data: dict | None = None,
                   incident_id=None, push: bool = True):
    row = (await db.execute(text("""INSERT INTO inbox_items(user_id, kind, title, body, data, incident_id, push_status, next_push_at)
        VALUES (:user, :kind, :title, :body, CAST(:data AS jsonb), :incident, :status, now()) RETURNING id"""),
        {"user": user_id, "kind": kind, "title": title[:120], "body": body[:500], "data": json.dumps(data or {}),
         "incident": incident_id, "status": "PENDING" if push else "NOT_NEEDED"})).mappings().one()
    return row["id"]


def _item(row) -> dict:
    return {"id": row["id"], "kind": row["kind"], "title": row["title"], "body": row["body"],
            "data": row["data"] or {}, "created_at": row["created_at"], "read": row["read_at"] is not None}


@router.get("")
async def inbox(limit: int = Query(default=50, ge=1, le=100), user=Depends(account), db=Depends(database)):
    rows = (await db.execute(text("""SELECT * FROM inbox_items WHERE user_id=:user AND kind <> ALL(:hidden)
        ORDER BY created_at DESC LIMIT :limit"""), {"user": user.id, "limit": limit, "hidden": list(HIDDEN_KINDS)})).mappings().all()
    invitations = (await db.execute(text("""SELECT v.id, v.incident_id, v.invited_at, v.expires_at
        FROM incident_invitations v JOIN incidents i ON i.id = v.incident_id
        WHERE v.recipient_user_id=:user AND v.status='PENDING' AND v.expires_at > now() AND i.status='ACTIVE'
        ORDER BY v.invited_at DESC LIMIT 10"""), {"user": user.id})).mappings().all()
    requests = [{"id": v["id"], "kind": "SOS_REQUEST", "title": "Someone nearby needs help",
                 "body": "Open to see the request. Only accept if you can respond safely.",
                 "data": {"invitation_id": str(v["id"]), "incident_id": str(v["incident_id"])},
                 "created_at": v["invited_at"], "read": False} for v in invitations]
    items = requests + [_item(r) for r in rows]
    unread = await db.scalar(text("SELECT count(*) FROM inbox_items WHERE user_id=:user AND read_at IS NULL "
                                  "AND kind <> ALL(:hidden)"), {"user": user.id, "hidden": list(HIDDEN_KINDS)})
    return {"items": items, "unread_count": unread + len(requests)}


async def _own(db, item_id: UUID, user_id, update: str):
    row = (await db.execute(text(f"UPDATE inbox_items SET {update} WHERE id=:id AND user_id=:user RETURNING id"),
                            {"id": item_id, "user": user_id})).first()
    if not row:
        raise HTTPException(404, "Inbox item not found")


@router.post("/{item_id}/read", status_code=204)
async def mark_read(item_id: UUID, user=Depends(account), db=Depends(database)):
    await _own(db, item_id, user.id, "read_at = coalesce(read_at, now())")


@router.post("/read-all", status_code=204)
async def mark_all_read(user=Depends(account), db=Depends(database)):
    await db.execute(text("UPDATE inbox_items SET read_at=now() WHERE user_id=:user AND read_at IS NULL"), {"user": user.id})


@router.post("/{item_id}/delivered", status_code=204)
async def mark_delivered(item_id: UUID, user=Depends(account), db=Depends(database)):
    """The app calls this when the push reached the phone: the only honest basis for "Delivered"."""
    await _own(db, item_id, user.id,
               "push_status = CASE WHEN push_status IN ('PENDING','SENT') THEN 'DELIVERED' ELSE push_status END, "
               "delivered_at = coalesce(delivered_at, now())")


async def send_pending_pushes(sessions, firebase_app, sender, batch: int = 25) -> int:
    """Claims due inbox pushes, sends them with no transaction held, and records the outcome."""
    async with sessions.begin() as db:
        claimed = (await db.execute(text("""
            UPDATE inbox_items SET push_attempts = push_attempts + 1,
                next_push_at = now() + make_interval(secs => 30 * power(2, push_attempts))
            WHERE id IN (SELECT id FROM inbox_items WHERE push_status='PENDING' AND next_push_at <= now()
                         ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED)
            RETURNING id, user_id, kind, title, body, data, push_attempts"""), {"batch": batch})).mappings().all()
        tokens = {}
        for item in claimed:
            tokens[item["id"]] = list((await db.execute(text(
                "SELECT fcm_token FROM devices WHERE user_id=:user AND is_active"), {"user": item["user_id"]})).scalars())
    for item in claimed:
        targets = tokens[item["id"]]
        if not targets:
            status = "NO_DEVICE" if item["push_attempts"] >= MAX_PUSH_ATTEMPTS else "PENDING"
            async with sessions.begin() as db:
                await db.execute(text("UPDATE inbox_items SET push_status=:status WHERE id=:id"),
                                 {"status": status, "id": item["id"]})
            continue
        data = {"event_type": item["kind"], "inbox_id": str(item["id"]), "title": item["title"], "body": item["body"],
                **{k: str(v) for k, v in (item["data"] or {}).items()}}
        results = await asyncio.to_thread(sender, targets, item["title"], item["body"], data, firebase_app)
        ok = any(r.success for r in results)
        invalid = [r.token for r in results if r.is_permanent]
        status = "SENT" if ok else ("FAILED" if item["push_attempts"] >= MAX_PUSH_ATTEMPTS
                                    or len(invalid) == len(targets) else "PENDING")
        async with sessions.begin() as db:
            await db.execute(text("""UPDATE inbox_items SET push_status = CASE WHEN push_status='DELIVERED' THEN push_status
                ELSE :status END, pushed_at = CASE WHEN :ok THEN now() ELSE pushed_at END WHERE id=:id"""),
                {"status": status, "ok": ok, "id": item["id"]})
            if invalid:
                await db.execute(text("UPDATE devices SET is_active=false, disabled_at=clock_timestamp() "
                                      "WHERE fcm_token = ANY(:tokens)"), {"tokens": invalid})
    return len(claimed)


async def cleanup(db):
    await db.execute(text("DELETE FROM inbox_items WHERE created_at < now() - interval '30 days'"))
    await db.execute(text("DELETE FROM circle_invite_attempts WHERE created_at < now() - interval '1 day'"))
    await db.execute(text("DELETE FROM circle_invites WHERE expires_at < now() - interval '7 days'"))


def utcnow():
    return datetime.now(timezone.utc)


__all__ = ["router", "add_item", "send_pending_pushes", "cleanup", "timedelta", "utcnow"]
