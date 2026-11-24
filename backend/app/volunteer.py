"""Volunteer application lifecycle and verification boundaries.
Enforces that document uploads require secure private storage and that
applications cannot grant verified volunteer/responder status automatically.
"""
from datetime import datetime, timezone
from typing import Literal
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import Field
from sqlalchemy import text
from app.core import account, database
from app.files import UploadRequest, create_upload
from app.schemas import Input

router = APIRouter(prefix="/api/volunteer")


class IntentRequest(Input):
    intent: Literal["HELP", "NOT_NOW"]


class ConsentRequest(Input):
    consent_declared: bool


class DocumentUploadMetadata(Input):
    doc_type: Literal["GOVT_ID", "ADDRESS_PROOF", "POLICE_CERT", "PHOTO", "DECLARATION"]
    file_name: str = Field(min_length=1, max_length=256)
    file_size_bytes: int = Field(gt=0, le=10 * 1024 * 1024)
    mime_type: str = Field(min_length=3, max_length=100)


def now():
    return datetime.now(timezone.utc)


async def one(db, sql, **params):
    return (await db.execute(text(sql), params)).mappings().first()


@router.get("/status")
async def get_volunteer_status(request: Request, user=Depends(account), db=Depends(database)):
    storage_ready = request.app.state.settings.storage_configured
    documents = [dict(r) for r in (await db.execute(text("""SELECT DISTINCT ON (doc_type) doc_type, id AS file_id,
        mime_type, size_bytes, uploaded_at FROM stored_files WHERE owner_user_id = :uid AND kind = 'VOLUNTEER_DOC'
        AND status = 'UPLOADED' ORDER BY doc_type, uploaded_at DESC"""), {"uid": user.id})).mappings().all()]
    row = await one(db, "SELECT * FROM volunteer_applications WHERE user_id = :uid", uid=user.id)
    if not row:
        return {
            "status": "NOT_STARTED",
            "intent": "HELP",
            "has_govt_id": False,
            "has_address_proof": False,
            "has_police_cert": False,
            "has_photo": False,
            "has_declaration": False,
            "consent_declared": False,
            "secure_storage_ready": storage_ready,
            "submitted_at": None,
            "reviewer_notes": None,
            "reviewed_at": None,
            "documents": documents,
        }
    return {
        "status": row["status"],
        "intent": row["intent"],
        "has_govt_id": row["has_govt_id"],
        "has_address_proof": row["has_address_proof"],
        "has_police_cert": row["has_police_cert"],
        "has_photo": row["has_photo"],
        "has_declaration": row["has_declaration"],
        "consent_declared": row["consent_declared"],
        "secure_storage_ready": storage_ready,
        "submitted_at": row["submitted_at"].isoformat() if row["submitted_at"] else None,
        "reviewer_notes": row["reviewer_notes"],
        "reviewed_at": row["reviewed_at"].isoformat() if row["reviewed_at"] else None,
        "documents": documents,
    }


@router.post("/intent")
async def set_volunteer_intent(body: IntentRequest, user=Depends(account), db=Depends(database)):
    row = await one(db, "SELECT * FROM volunteer_applications WHERE user_id = :uid", uid=user.id)
    if not row:
        status = "NOT_STARTED" if body.intent == "NOT_NOW" else "DRAFT"
        await db.execute(text("""
            INSERT INTO volunteer_applications (user_id, intent, status)
            VALUES (:uid, :intent, :status)
        """), {"uid": user.id, "intent": body.intent, "status": status})
    else:
        status = "NOT_STARTED" if body.intent == "NOT_NOW" and row["status"] in ("NOT_STARTED", "DRAFT") else row["status"]
        await db.execute(text("""
            UPDATE volunteer_applications
            SET intent = :intent, status = :status, updated_at = now()
            WHERE user_id = :uid
        """), {"uid": user.id, "intent": body.intent, "status": status})
    return {"status": "ok", "intent": body.intent}


@router.post("/consent")
async def set_volunteer_consent(body: ConsentRequest, user=Depends(account), db=Depends(database)):
    row = await one(db, "SELECT * FROM volunteer_applications WHERE user_id = :uid", uid=user.id)
    if not row:
        await db.execute(text("""
            INSERT INTO volunteer_applications (user_id, status, consent_declared)
            VALUES (:uid, 'DRAFT', :consent)
        """), {"uid": user.id, "consent": body.consent_declared})
    else:
        if row["status"] in ("SUBMITTED", "UNDER_REVIEW", "APPROVED"):
            raise HTTPException(409, "Application is already submitted or approved.")
        await db.execute(text("""
            UPDATE volunteer_applications
            SET consent_declared = :consent, updated_at = now()
            WHERE user_id = :uid
        """), {"uid": user.id, "consent": body.consent_declared})
    return {"status": "ok", "consent_declared": body.consent_declared}


@router.post("/upload-document")
async def upload_volunteer_document(body: DocumentUploadMetadata, request: Request, user=Depends(account),
                                    db=Depends(database)):
    """Per Security Policy 2.3 identity documents go only to private storage with short-lived signed URLs.
    Returns a presigned upload (see app/files.py); 503 while that storage is not configured."""
    return await create_upload(db, request, user, UploadRequest(kind="VOLUNTEER_DOC", doc_type=body.doc_type,
        mime_type=body.mime_type, size_bytes=body.file_size_bytes))


@router.post("/submit")
async def submit_volunteer_application(user=Depends(account), db=Depends(database)):
    row = await one(db, "SELECT * FROM volunteer_applications WHERE user_id = :uid", uid=user.id)
    if not row:
        raise HTTPException(404, "No volunteer application found. Start an application first.")
    if row["status"] in ("SUBMITTED", "UNDER_REVIEW", "APPROVED"):
        raise HTTPException(409, f"Application already in state: {row['status']}")
    if not row["consent_declared"]:
        raise HTTPException(422, "Declaration and consent must be accepted before submission.")

    missing = []
    if not row["has_govt_id"]: missing.append("Government-issued identity proof")
    if not row["has_address_proof"]: missing.append("Address proof")
    if not row["has_police_cert"]: missing.append("Police character certificate")
    if not row["has_photo"]: missing.append("Recent photograph")
    if not row["has_declaration"]: missing.append("Signed volunteer declaration")

    if missing:
        raise HTTPException(422, f"Missing required documents: {', '.join(missing)}")

    await db.execute(text("""
        UPDATE volunteer_applications
        SET status = 'SUBMITTED', submitted_at = now(), updated_at = now()
        WHERE user_id = :uid
    """), {"uid": user.id})
    return {"status": "ok", "application_status": "SUBMITTED"}
