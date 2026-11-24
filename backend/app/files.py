"""Private files: volunteer documents, SOS photos and voice notes, profile photos.

Flow: POST /api/files/upload-url -> the phone PUTs the bytes straight to R2 with the returned URL and headers
-> POST /api/files/{id}/complete (the server checks the object exists with the declared size) -> the file is
usable. Downloads go through GET /api/files/{id}/download-url, valid for a few minutes: the owner, and for SOS
photos and voice notes also the circle members who got that SOS's alert in the app and are still in the circle.
Volunteer responders never get SOS media.
"""
from typing import Literal
from uuid import UUID, uuid4

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import Field, model_validator
from sqlalchemy import text

from app.core import account, database
from app.schemas import Input
from app.storage import Bucket

router = APIRouter(prefix="/api/files")

Kind = Literal["VOLUNTEER_DOC", "SOS_PHOTO", "SOS_VOICE", "PROFILE_PHOTO"]
DocType = Literal["GOVT_ID", "ADDRESS_PROOF", "POLICE_CERT", "PHOTO", "DECLARATION"]

IMAGES = {"image/jpeg", "image/png", "image/webp"}
ALLOWED_TYPES = {
    "VOLUNTEER_DOC": IMAGES | {"application/pdf"},
    "SOS_PHOTO": IMAGES,
    "SOS_VOICE": {"audio/mp4", "audio/aac", "audio/mpeg", "audio/ogg", "audio/webm", "audio/3gpp", "audio/amr"},
    "PROFILE_PHOTO": IMAGES,
}
DOC_COLUMNS = {"GOVT_ID": "has_govt_id", "ADDRESS_PROOF": "has_address_proof", "POLICE_CERT": "has_police_cert",
               "PHOTO": "has_photo", "DECLARATION": "has_declaration"}
LOCKED_APPLICATION = ("SUBMITTED", "UNDER_REVIEW", "APPROVED")


class UploadRequest(Input):
    kind: Kind
    mime_type: str = Field(min_length=3, max_length=100)
    size_bytes: int = Field(gt=0)
    doc_type: DocType | None = None
    incident_id: UUID | None = None

    @model_validator(mode="after")
    def matches_kind(self):
        if (self.kind == "VOLUNTEER_DOC") != (self.doc_type is not None):
            raise ValueError("doc_type is required for, and only for, volunteer documents")
        if (self.kind in ("SOS_PHOTO", "SOS_VOICE")) != (self.incident_id is not None):
            raise ValueError("incident_id is required for, and only for, SOS media")
        return self


def bucket_for(request: Request) -> Bucket:
    bucket = Bucket.from_settings(request.app.state.settings)
    if bucket is None:
        raise HTTPException(503, {"code": "STORAGE_NOT_CONFIGURED", "message": "Private file storage is not configured"})
    return bucket


async def owned_file(db, file_id: UUID, user):
    row = (await db.execute(text("SELECT * FROM stored_files WHERE id = :id AND owner_user_id = :uid"),
                            {"id": file_id, "uid": user.id})).mappings().first()
    if row is None:
        raise HTTPException(404, "File unavailable")
    return row


# A circle member who was alerted in the app about this SOS (inbox push) and whose circle link is still active.
CIRCLE_MEMBER_OF_INCIDENT = """
    SELECT 1 FROM incident_contact_alerts a
    JOIN inbox_items i ON i.id = a.inbox_item_id
    JOIN circle_links l ON l.contact_id = a.contact_id AND l.recipient_id = i.user_id AND l.revoked_at IS NULL
    WHERE a.incident_id = :incident AND a.channel = 'PUSH' AND i.user_id = :viewer
    LIMIT 1"""


async def can_see_incident_media(db, incident_id: UUID, user) -> bool:
    owner = await db.scalar(text("SELECT victim_user_id FROM incidents WHERE id = :id"), {"id": incident_id})
    if owner is None:
        return False
    if owner == user.id:
        return True
    return bool(await db.scalar(text(CIRCLE_MEMBER_OF_INCIDENT), {"incident": incident_id, "viewer": user.id}))


async def readable_file(db, file_id: UUID, user):
    """The owner's file, or SOS media the user's circle shared with them; 404 otherwise (no hint it exists)."""
    row = (await db.execute(text("SELECT * FROM stored_files WHERE id = :id"), {"id": file_id})).mappings().first()
    if row is None:
        raise HTTPException(404, "File unavailable")
    if row["owner_user_id"] == user.id:
        return row
    if row["kind"] in ("SOS_PHOTO", "SOS_VOICE") and row["incident_id"] is not None             and await can_see_incident_media(db, row["incident_id"], user):
        return row
    raise HTTPException(404, "File unavailable")


async def create_upload(db, request: Request, user, body: UploadRequest) -> dict:
    settings = request.app.state.settings
    bucket = bucket_for(request)
    mime = body.mime_type.lower()
    if mime not in ALLOWED_TYPES[body.kind]:
        raise HTTPException(422, {"code": "FILE_TYPE_NOT_ALLOWED", "message": f"{mime} is not accepted here"})
    if body.size_bytes > settings.max_upload_bytes:
        raise HTTPException(422, {"code": "FILE_TOO_LARGE", "message": "File is larger than the upload limit"})
    if body.incident_id is not None:
        owner = await db.scalar(text("SELECT victim_user_id FROM incidents WHERE id = :id"), {"id": body.incident_id})
        if owner != user.id:
            raise HTTPException(404, "Incident unavailable")
    if body.kind == "VOLUNTEER_DOC":
        status = await db.scalar(text("SELECT status FROM volunteer_applications WHERE user_id = :uid"), {"uid": user.id})
        if status in LOCKED_APPLICATION:
            raise HTTPException(409, "Application is already submitted or approved.")
    file_id = uuid4()
    # The key carries no names, numbers or file names: only the kind, the owner's internal id and a random id.
    key = f"{body.kind.lower()}/{user.id}/{file_id}"
    await db.execute(text("""
        INSERT INTO stored_files (id, owner_user_id, kind, doc_type, incident_id, object_key, mime_type, size_bytes)
        VALUES (:id, :uid, :kind, :doc_type, :incident, :key, :mime, :size)
    """), {"id": file_id, "uid": user.id, "kind": body.kind, "doc_type": body.doc_type, "incident": body.incident_id,
           "key": key, "mime": mime, "size": body.size_bytes})
    headers = {"Content-Type": mime, "Content-Length": str(body.size_bytes)}
    return {"file_id": str(file_id), "method": "PUT", "upload_url": bucket.url("PUT", key, settings.upload_url_seconds, headers),
            "headers": headers, "expires_in": settings.upload_url_seconds}


@router.post("/upload-url")
async def upload_url(body: UploadRequest, request: Request, user=Depends(account), db=Depends(database)):
    return await create_upload(db, request, user, body)


@router.post("/{file_id}/complete")
async def complete(file_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    bucket = bucket_for(request)
    row = await owned_file(db, file_id, user)
    if row["status"] == "UPLOADED":
        return {"file_id": str(file_id), "status": "UPLOADED"}
    try:
        size = await bucket.head(row["object_key"])
    except httpx.HTTPError:
        raise HTTPException(503, {"code": "STORAGE_UNAVAILABLE", "message": "File storage could not be reached"}) from None
    if size is None:
        raise HTTPException(409, {"code": "UPLOAD_NOT_FOUND", "message": "The file has not been uploaded yet"})
    if size != row["size_bytes"]:
        raise HTTPException(409, {"code": "UPLOAD_SIZE_MISMATCH", "message": "The uploaded file is not the declared size"})
    await db.execute(text("UPDATE stored_files SET status = 'UPLOADED', uploaded_at = now() WHERE id = :id"), {"id": file_id})
    if row["kind"] == "VOLUNTEER_DOC":
        column = DOC_COLUMNS[row["doc_type"]]  # fixed mapping, never user text
        await db.execute(text(f"""
            INSERT INTO volunteer_applications (user_id, status, {column}) VALUES (:uid, 'DRAFT', true)
            ON CONFLICT (user_id) DO UPDATE SET {column} = true, updated_at = now()
        """), {"uid": user.id})
    return {"file_id": str(file_id), "status": "UPLOADED"}


@router.get("/incidents/{incident_id}/media")
async def incident_media(incident_id: UUID, user=Depends(account), db=Depends(database)):
    """Uploaded SOS photos and voice notes for one SOS, oldest first, for its owner and their alerted circle."""
    if not await can_see_incident_media(db, incident_id, user):
        raise HTTPException(404, "Incident unavailable")
    rows = (await db.execute(text("""SELECT id, kind, mime_type, size_bytes, uploaded_at FROM stored_files
        WHERE incident_id = :id AND kind IN ('SOS_PHOTO', 'SOS_VOICE') AND status = 'UPLOADED'
        ORDER BY uploaded_at, id"""), {"id": incident_id})).mappings().all()
    return {"incident_id": str(incident_id), "items": [
        {"file_id": str(r["id"]), "kind": r["kind"], "mime_type": r["mime_type"], "size_bytes": r["size_bytes"],
         "uploaded_at": r["uploaded_at"]} for r in rows]}


@router.get("/{file_id}/download-url")
async def download_url(file_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    bucket = bucket_for(request)
    row = await readable_file(db, file_id, user)
    if row["status"] != "UPLOADED":
        raise HTTPException(409, {"code": "UPLOAD_NOT_FOUND", "message": "The file has not been uploaded yet"})
    seconds = request.app.state.settings.download_url_seconds
    return {"file_id": str(file_id), "download_url": bucket.url("GET", row["object_key"], seconds),
            "mime_type": row["mime_type"], "expires_in": seconds}


@router.delete("/{file_id}", status_code=204)
async def delete_file(file_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    bucket = bucket_for(request)
    row = await owned_file(db, file_id, user)
    try:
        await bucket.delete(row["object_key"])
    except httpx.HTTPError:
        raise HTTPException(503, {"code": "STORAGE_UNAVAILABLE", "message": "File storage could not be reached"}) from None
    await db.execute(text("DELETE FROM stored_files WHERE id = :id"), {"id": file_id})
    if row["kind"] == "VOLUNTEER_DOC" and row["status"] == "UPLOADED":
        still = await db.scalar(text("""SELECT count(*) FROM stored_files WHERE owner_user_id = :uid
            AND kind = 'VOLUNTEER_DOC' AND doc_type = :doc AND status = 'UPLOADED'"""), {"uid": user.id, "doc": row["doc_type"]})
        if not still:
            column = DOC_COLUMNS[row["doc_type"]]
            await db.execute(text(f"UPDATE volunteer_applications SET {column} = false, updated_at = now() WHERE user_id = :uid"
                                  " AND status NOT IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED')"), {"uid": user.id})
    return Response(status_code=204)


class PhotoRequest(Input):
    file_id: UUID


@router.post("/profile-photo")
async def set_profile_photo(body: PhotoRequest, request: Request, user=Depends(account), db=Depends(database)):
    row = await owned_file(db, body.file_id, user)
    if row["kind"] != "PROFILE_PHOTO" or row["status"] != "UPLOADED":
        raise HTTPException(409, {"code": "UPLOAD_NOT_FOUND", "message": "Upload the photo first"})
    previous = await db.scalar(text("SELECT photo_file_id FROM users WHERE id = :uid"), {"uid": user.id})
    await db.execute(text("UPDATE users SET photo_file_id = :f, updated_at = now() WHERE id = :uid"),
                     {"f": body.file_id, "uid": user.id})
    if previous and previous != body.file_id:
        old = await db.scalar(text("SELECT object_key FROM stored_files WHERE id = :id"), {"id": previous})
        await db.execute(text("DELETE FROM stored_files WHERE id = :id"), {"id": previous})
        if old:
            try:
                await bucket_for(request).delete(old)
            except (httpx.HTTPError, HTTPException):
                pass  # an orphaned object is removed by the bucket lifecycle rule; the account no longer points at it
    return {"file_id": str(body.file_id)}


@router.get("/profile-photo")
async def get_profile_photo(request: Request, user=Depends(account), db=Depends(database)):
    file_id = await db.scalar(text("SELECT photo_file_id FROM users WHERE id = :uid"), {"uid": user.id})
    if file_id is None:
        return {"file_id": None, "download_url": None}
    return {**(await download_url(file_id, request, user, db))}
