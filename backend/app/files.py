"""Private files: volunteer documents, SOS photos and voice notes, profile photos.

Flow: POST /api/files/upload-url -> the phone PUTs the bytes straight to R2 with the returned URL and headers
-> POST /api/files/{id}/complete (the server checks the object exists with the declared size) -> the file is
usable. Downloads go through GET /api/files/{id}/download-url, owner only, valid for a few minutes.
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


@router.get("/{file_id}/download-url")
async def download_url(file_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    bucket = bucket_for(request)
    row = await owned_file(db, file_id, user)
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
