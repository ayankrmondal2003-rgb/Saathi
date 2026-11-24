"""Privacy and data (screen 09): medical notes, data export, account deletion, SOS media retention.

Medical notes are encrypted at rest with AES-256-GCM (MEDICAL_NOTES_KEY, 32 bytes base64). The ciphertext is
bound to the account id, so it can't be copied to another row. They're readable by the owner, and by a
responder who is authorized on the owner's ACTIVE incident (shown on screen 07); nobody else, ever.
"""
import base64
import hashlib
import json
import logging
import os
from datetime import datetime, timezone
from uuid import UUID

import httpx
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from firebase_admin import auth
from firebase_admin.exceptions import FirebaseError
from pydantic import Field
from sqlalchemy import text
from starlette.concurrency import run_in_threadpool

from app.core import Identity, account, database, identity
from app.schemas import Input
from app.storage import Bucket

router = APIRouter(prefix="/api/users/me")
log = logging.getLogger("surakshanet.privacy")
REAUTH_SECONDS = 600
NOTES_LIMIT = 1000


class Notes(Input):
    notes: str = Field(default="", max_length=NOTES_LIMIT)


def notes_cipher(settings) -> AESGCM:
    secret = settings.medical_notes_key
    if secret is None:
        raise HTTPException(503, {"code": "MEDICAL_NOTES_UNAVAILABLE", "message": "Medical notes can't be stored right now"})
    try:
        key = base64.urlsafe_b64decode(secret.get_secret_value() + "=" * (-len(secret.get_secret_value()) % 4))
    except (ValueError, TypeError):
        key = b""
    if len(key) != 32:
        log.error("MEDICAL_NOTES_KEY must be 32 bytes, base64-encoded")
        raise HTTPException(503, {"code": "MEDICAL_NOTES_UNAVAILABLE", "message": "Medical notes can't be stored right now"})
    return AESGCM(key)


def encrypt_notes(settings, user_id: UUID, notes: str) -> bytes:
    nonce = os.urandom(12)
    return nonce + notes_cipher(settings).encrypt(nonce, notes.encode(), user_id.bytes)


def decrypt_notes(settings, user_id: UUID, blob: bytes | None) -> str:
    if not blob:
        return ""
    return notes_cipher(settings).decrypt(bytes(blob[:12]), bytes(blob[12:]), user_id.bytes).decode()


@router.get("/medical-notes")
async def get_notes(request: Request, user=Depends(account), db=Depends(database)):
    blob = await db.scalar(text("SELECT medical_notes FROM users WHERE id=:id"), {"id": user.id})
    return {"notes": decrypt_notes(request.app.state.settings, user.id, blob)}


@router.put("/medical-notes")
async def put_notes(body: Notes, request: Request, user=Depends(account), db=Depends(database)):
    notes = body.notes.strip()
    blob = encrypt_notes(request.app.state.settings, user.id, notes) if notes else None
    await db.execute(text("UPDATE users SET medical_notes=:blob, updated_at=now() WHERE id=:id"), {"blob": blob, "id": user.id})
    return {"notes": notes}


async def notes_for_responder(db, settings, incident) -> str | None:
    if incident.status != "ACTIVE":
        return None
    blob = await db.scalar(text("SELECT medical_notes FROM users WHERE id=:id"), {"id": incident.victim_user_id})
    if not blob or settings.medical_notes_key is None:
        return None
    try:
        return decrypt_notes(settings, incident.victim_user_id, blob) or None
    except Exception:  # a rotated key must not break the snapshot
        log.warning("Medical notes could not be decrypted")
        return None


EXPORT_QUERIES = {
    "trusted_contacts": "SELECT display_name, phone_number, label, is_primary, notify_on_sos, created_at FROM trusted_contacts WHERE owner_user_id=:id",
    "circles_you_belong_to": "SELECT u.display_name AS owner_name, l.created_at, l.revoked_at FROM circle_links l JOIN users u ON u.id=l.owner_id WHERE l.recipient_id=:id",
    "sos_incidents": "SELECT id, status, is_test, started_at, ended_at FROM incidents WHERE victim_user_id=:id ORDER BY started_at",
    "trips_and_check_ins": "SELECT kind, created_at, expires_at, due_at, stopped_at, missed_at FROM circle_sessions WHERE owner_id=:id",
    "volunteer_application": "SELECT status, intent, consent_declared, submitted_at, reviewed_at, reviewer_notes FROM volunteer_applications WHERE user_id=:id",
    "responder_activity": "SELECT incident_id, assistance_status, accepted_at FROM incident_responders WHERE responder_user_id=:id",
    "stored_files": "SELECT kind, doc_type, mime_type, size_bytes, created_at FROM stored_files WHERE owner_user_id=:id",
    "notifications": "SELECT kind, title, body, created_at, read_at FROM inbox_items WHERE user_id=:id ORDER BY created_at",
    "devices": "SELECT platform, created_at, last_seen_at FROM devices WHERE user_id=:id",
}


def _plain(value):
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, UUID):
        return str(value)
    return value


@router.get("/export")
async def export(request: Request, user=Depends(account), db=Depends(database)):
    """Everything Maitri keeps about this account, as JSON. Files are listed, not included (they're private)."""
    profile = (await db.execute(text("""SELECT display_name, email, email_verified, phone_number, phone_verified, city,
        responder_opt_in, responder_available, share_phone_in_sos, created_at FROM users WHERE id=:id"""),
        {"id": user.id})).mappings().one()
    data = {"exported_at": datetime.now(timezone.utc).isoformat(), "profile": {k: _plain(v) for k, v in profile.items()}}
    blob = await db.scalar(text("SELECT medical_notes FROM users WHERE id=:id"), {"id": user.id})
    try:
        data["profile"]["medical_notes"] = decrypt_notes(request.app.state.settings, user.id, blob) if blob else ""
    except HTTPException:
        data["profile"]["medical_notes"] = "(stored encrypted; unavailable right now)"
    for name, query in EXPORT_QUERIES.items():
        rows = (await db.execute(text(query), {"id": user.id})).mappings().all()
        data[name] = [{k: _plain(v) for k, v in row.items()} for row in rows]
    body = json.dumps(data, indent=2, ensure_ascii=False)
    return Response(body, media_type="application/json",
                    headers={"Content-Disposition": 'attachment; filename="maitri-data.json"', "Cache-Control": "no-store"})


# Rows that reference the account without ON DELETE CASCADE, deleted explicitly (children before parents).
OWNED_INCIDENT_CHILDREN = ["incident_events", "notification_outbox", "incident_resolution_attempts",
                           "incident_resolution_requests", "trusted_contact_grants", "incident_responders",
                           "incident_invitations"]
PARTICIPATION = [
    "DELETE FROM incident_events WHERE actor_user_id=:id",
    "DELETE FROM notification_outbox WHERE recipient_user_id=:id",
    "DELETE FROM incident_resolution_attempts WHERE actor_user_id=:id",
    "DELETE FROM incident_resolution_requests WHERE requester_user_id=:id",
    "DELETE FROM incident_responders WHERE responder_user_id=:id",
    "DELETE FROM incident_invitations WHERE recipient_user_id=:id",
    "DELETE FROM responder_locations WHERE user_id=:id",
    "DELETE FROM devices WHERE user_id=:id",
    "DELETE FROM circle_invite_attempts WHERE firebase_uid=:uid",
]


@router.delete("", status_code=204)
async def delete_account(request: Request, who: Identity = Depends(identity), user=Depends(account), db=Depends(database)):
    """Deletes the account everywhere: database rows, private files in R2, and the Firebase sign-in.

    Needs a sign-in from the last 10 minutes (the app re-authenticates first). Refused while an SOS is open,
    so nobody deletes their way out of an emergency by mistake.
    """
    if datetime.now(timezone.utc).timestamp() - who.auth_time > REAUTH_SECONDS:
        raise HTTPException(401, {"code": "REAUTH_REQUIRED", "message": "Sign in again to delete your account"})
    if await db.scalar(text("SELECT 1 FROM incidents WHERE victim_user_id=:id AND status <> 'CLOSED'"), {"id": user.id}):
        raise HTTPException(409, {"code": "SOS_OPEN", "message": "End your SOS before deleting your account"})
    keys = list((await db.execute(text("SELECT object_key FROM stored_files WHERE owner_user_id=:id"), {"id": user.id})).scalars())
    owned = list((await db.execute(text("SELECT id FROM incidents WHERE victim_user_id=:id"), {"id": user.id})).scalars())
    if owned:
        for table in OWNED_INCIDENT_CHILDREN:
            await db.execute(text(f"DELETE FROM {table} WHERE incident_id = ANY(:ids)"), {"ids": owned})
    for statement in PARTICIPATION:
        await db.execute(text(statement), {"id": user.id, "uid": who.uid})
    if owned:
        await db.execute(text("DELETE FROM incidents WHERE id = ANY(:ids)"), {"ids": owned})
    await db.execute(text("DELETE FROM users WHERE id=:id"), {"id": user.id})
    # Files, then the sign-in. Any failure raises, which rolls the database back, so a retry starts clean.
    bucket = Bucket.from_settings(request.app.state.settings)
    if keys and bucket is None:
        raise HTTPException(503, {"code": "STORAGE_NOT_CONFIGURED", "message": "Files can't be deleted right now. Try later."})
    try:
        for key in keys:
            await bucket.delete(key)
    except httpx.HTTPError:
        raise HTTPException(503, {"code": "STORAGE_UNAVAILABLE", "message": "Files can't be deleted right now. Try later."}) from None
    firebase = getattr(request.app.state, "firebase", None)
    if firebase is not None:
        try:
            await run_in_threadpool(auth.delete_user, who.uid, app=firebase)
        except auth.UserNotFoundError:
            pass
        except FirebaseError:
            raise HTTPException(503, {"code": "IDENTITY_UNAVAILABLE", "message": "Couldn't delete your sign-in. Try later."}) from None
    log.info("Account deleted (%s files)", len(keys))
    return Response(status_code=204)


async def purge_sos_media(sessions, settings, batch: int = 100) -> int:
    """SOS photos and voice notes are kept 30 days (sos_media_days), then deleted from R2 and the database."""
    bucket = Bucket.from_settings(settings)
    if bucket is None:
        return 0
    async with sessions.begin() as db:
        rows = (await db.execute(text("""SELECT id, object_key FROM stored_files WHERE kind IN ('SOS_PHOTO','SOS_VOICE')
            AND created_at < now() - make_interval(days => :days) ORDER BY created_at LIMIT :batch"""),
            {"days": settings.sos_media_days, "batch": batch})).all()
    done = []
    for file_id, key in rows:
        try:
            await bucket.delete(key)
            done.append(file_id)
        except httpx.HTTPError:
            break
    if done:
        async with sessions.begin() as db:
            await db.execute(text("DELETE FROM stored_files WHERE id = ANY(:ids)"), {"ids": done})
    return len(done)


def fingerprint(value: str) -> str:  # for logs that must not contain the raw value
    return hashlib.sha256(value.encode()).hexdigest()[:8]
