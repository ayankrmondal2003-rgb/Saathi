"""Linking a trusted contact to the Maitri account of the person behind it, so SOS alerts reach them as pushes.

Two ways, both ending with the recipient's own action:
- Pairing code: the owner creates a code for one contact and sends it however they like; the friend enters
  it in their app. 8 characters from a 31-letter alphabet (~40 bits), 24 h, single use, 10 tries an hour.
- Phone match: when a contact's number equals a Maitri account whose phone was verified by Firebase OTP,
  that account gets a circle request in its inbox. Self-declared profile numbers are never matched, because
  anyone could type someone else's number.

Links are one-way (the owner's SOS reaches the recipient). A recipient can add the owner back from "Circles you're
in": that saves the owner as the recipient's own contact and sends the owner the usual circle request, so a mutual
link always needs both people to choose it.
"""
import hashlib
import re
import secrets
from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import Field
from sqlalchemy import text

from app.core import account, database
from app.inbox import add_item
from app.schemas import Input

router = APIRouter(prefix="/api/circle")
ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
ATTEMPTS_PER_HOUR = 10


class InviteRequest(Input):
    contact_id: UUID


class AcceptRequest(Input):
    code: str = Field(min_length=8, max_length=16)


class AddBackRequest(Input):
    # Needed only when the owner's phone isn't OTP-verified (typed profile numbers are never trusted).
    phone_number: str | None = Field(default=None, min_length=7, max_length=32)


def normalize_code(code: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", code.upper())


def code_hash(code: str) -> str:
    return hashlib.sha256(("circle:" + normalize_code(code)).encode()).hexdigest()


def new_code() -> str:
    raw = "".join(secrets.choice(ALPHABET) for _ in range(8))
    return raw[:4] + "-" + raw[4:]


def display(name: str | None) -> str:
    return (name or "").strip() or "Someone"


async def one(db, sql, **params):
    return (await db.execute(text(sql), params)).mappings().first()


async def link(db, owner_id, contact_id, recipient_id):
    # Re-linking revokes the old link, so a contact never silently points at two accounts.
    await db.execute(text("UPDATE circle_links SET revoked_at=now() WHERE contact_id=:c AND revoked_at IS NULL"),
                     {"c": contact_id})
    return await one(db, """INSERT INTO circle_links(owner_id, contact_id, recipient_id)
        VALUES (:owner, :contact, :recipient) RETURNING id""", owner=owner_id, contact=contact_id, recipient=recipient_id)


async def announce_link(db, owner_id, recipient):
    await add_item(db, owner_id, "CIRCLE_LINKED", f"{display(recipient.display_name)} joined your circle",
                   "Your SOS alerts now reach them in Maitri as well.")


@router.post("/invites")
async def create_invite(body: InviteRequest, user=Depends(account), db=Depends(database)):
    contact = await one(db, "SELECT id FROM trusted_contacts WHERE id=:id AND owner_user_id=:owner",
                        id=body.contact_id, owner=user.id)
    if not contact:
        raise HTTPException(404, "Trusted contact not found")
    await db.execute(text("""UPDATE circle_invites SET expires_at=now() WHERE contact_id=:c AND code_hash IS NOT NULL
        AND accepted_at IS NULL AND expires_at > now()"""), {"c": body.contact_id})
    code = new_code()
    expires = datetime.now(timezone.utc) + timedelta(hours=24)
    await db.execute(text("""INSERT INTO circle_invites(owner_id, contact_id, code_hash, expires_at)
        VALUES (:owner, :contact, :hash, :expires)"""),
        {"owner": user.id, "contact": body.contact_id, "hash": code_hash(code), "expires": expires})
    return {"code": code, "expires_at": expires}


@router.post("/invites/accept")
async def accept_invite(body: AcceptRequest, request: Request, user=Depends(account), db=Depends(database)):
    # Counted in its own transaction: a wrong code raises 404, which rolls back the request transaction.
    async with request.app.state.sessions.begin() as counter:
        tries = await counter.scalar(text("""SELECT count(*) FROM circle_invite_attempts
            WHERE firebase_uid=:u AND created_at > now() - interval '1 hour'"""), {"u": user.firebase_uid})
        if tries < ATTEMPTS_PER_HOUR:
            await counter.execute(text("INSERT INTO circle_invite_attempts(firebase_uid) VALUES (:u)"), {"u": user.firebase_uid})
    if tries >= ATTEMPTS_PER_HOUR:
        raise HTTPException(429, {"code": "TOO_MANY_ATTEMPTS", "message": "Too many codes tried. Try again in an hour."})
    invite = await one(db, """SELECT i.*, c.display_name AS contact_name FROM circle_invites i
        JOIN trusted_contacts c ON c.id = i.contact_id
        WHERE i.code_hash=:hash AND i.accepted_at IS NULL AND i.expires_at > now() FOR UPDATE OF i""",
        hash=code_hash(body.code))
    if not invite:
        raise HTTPException(404, {"code": "INVITE_NOT_FOUND", "message": "That code isn't valid or has expired."})
    if invite.owner_id == user.id:
        raise HTTPException(422, {"code": "OWN_INVITE", "message": "This is your own code. Send it to the person you added."})
    await link(db, invite.owner_id, invite.contact_id, user.id)
    await db.execute(text("UPDATE circle_invites SET accepted_at=now() WHERE id=:id"), {"id": invite.id})
    await announce_link(db, invite.owner_id, user)
    owner = await one(db, "SELECT display_name FROM users WHERE id=:id", id=invite.owner_id)
    return {"status": "LINKED", "owner_name": display(owner.display_name)}


async def request_phone_matches(db, owner, contact_id, normalized_phone: str):
    """Called after a contact is saved: a phone-verified Maitri account with that number gets a request."""
    digits = re.sub(r"\D", "", normalized_phone)
    if len(digits) < 10:
        return
    target = await one(db, """SELECT id FROM users WHERE phone_verified AND account_status='ACTIVE' AND id <> :owner
        AND right(regexp_replace(coalesce(phone_number,''), '\\D', '', 'g'), 10) = :last10
        AND (length(:digits) = 10 OR regexp_replace(phone_number, '\\D', '', 'g') = :digits)
        LIMIT 1""", owner=owner.id, last10=digits[-10:], digits=digits)
    if not target:
        return
    await send_request(db, owner, contact_id, target.id)


async def send_request(db, owner, contact_id, target_id) -> bool:
    """A circle request from `owner` (for their contact) to the account `target_id`, unless one is open or linked."""
    if await db.scalar(text("""SELECT 1 FROM circle_invites WHERE contact_id=:c AND target_user_id=:t
        AND accepted_at IS NULL AND declined_at IS NULL AND expires_at > now()"""), {"c": contact_id, "t": target_id}):
        return False
    if await db.scalar(text("SELECT 1 FROM circle_links WHERE contact_id=:c AND recipient_id=:t AND revoked_at IS NULL"),
                       {"c": contact_id, "t": target_id}):
        return False
    invite = await one(db, """INSERT INTO circle_invites(owner_id, contact_id, target_user_id, expires_at)
        VALUES (:owner, :contact, :target, now() + interval '14 days') RETURNING id""",
        owner=owner.id, contact=contact_id, target=target_id)
    await add_item(db, target_id, "CIRCLE_REQUEST", f"{display(owner.display_name)} added you to their circle",
                   "Accept to get their SOS alerts in Maitri.", data={"request_id": str(invite.id)})
    return True


@router.get("/requests")
async def requests(user=Depends(account), db=Depends(database)):
    rows = (await db.execute(text("""SELECT i.id, u.display_name AS owner_name, i.created_at FROM circle_invites i
        JOIN users u ON u.id = i.owner_id WHERE i.target_user_id=:me AND i.accepted_at IS NULL
        AND i.declined_at IS NULL AND i.expires_at > now() ORDER BY i.created_at DESC"""), {"me": user.id})).mappings()
    return [{"id": r["id"], "owner_name": display(r["owner_name"]), "created_at": r["created_at"]} for r in rows]


@router.post("/requests/{request_id}/{decision}")
async def answer(request_id: UUID, decision: str, user=Depends(account), db=Depends(database)):
    if decision not in ("accept", "decline"):
        raise HTTPException(404, "Unknown action")
    invite = await one(db, """SELECT * FROM circle_invites WHERE id=:id AND target_user_id=:me AND accepted_at IS NULL
        AND declined_at IS NULL AND expires_at > now() FOR UPDATE""", id=request_id, me=user.id)
    if not invite:
        raise HTTPException(404, "Circle request not found")
    if decision == "decline":
        await db.execute(text("UPDATE circle_invites SET declined_at=now() WHERE id=:id"), {"id": invite.id})
        return {"status": "DECLINED"}
    await link(db, invite.owner_id, invite.contact_id, user.id)
    await db.execute(text("UPDATE circle_invites SET accepted_at=now() WHERE id=:id"), {"id": invite.id})
    await announce_link(db, invite.owner_id, user)
    return {"status": "LINKED"}


async def reverse_state(db, me, owner_id) -> str | None:
    """Whether `me` has added `owner_id` back: LINKED (their SOS reaches them), REQUESTED (waiting), or None."""
    if await db.scalar(text("""SELECT 1 FROM circle_links WHERE owner_id=:me AND recipient_id=:o AND revoked_at IS NULL"""),
                       {"me": me, "o": owner_id}):
        return "LINKED"
    if await db.scalar(text("""SELECT 1 FROM circle_invites WHERE owner_id=:me AND target_user_id=:o AND accepted_at IS NULL
        AND declined_at IS NULL AND expires_at > now()"""), {"me": me, "o": owner_id}):
        return "REQUESTED"
    return None


@router.get("/memberships")
async def memberships(user=Depends(account), db=Depends(database)):
    """Circles this account belongs to (whose SOS alerts it receives), and whether it has added each owner back."""
    rows = (await db.execute(text("""SELECT l.id, l.owner_id, u.display_name AS owner_name, l.created_at FROM circle_links l
        JOIN users u ON u.id = l.owner_id WHERE l.recipient_id=:me AND l.revoked_at IS NULL
        ORDER BY l.created_at DESC"""), {"me": user.id})).mappings().all()
    return [{"link_id": r["id"], "owner_name": display(r["owner_name"]), "since": r["created_at"],
             "added_back": await reverse_state(db, user.id, r["owner_id"])} for r in rows]


@router.post("/memberships/{link_id}/add-back")
async def add_back(link_id: UUID, body: AddBackRequest, user=Depends(account), db=Depends(database)):
    """Save the circle's owner as my contact and send them a circle request (their choice to accept)."""
    from app.schemas import TrustedContactCreate
    from app.services import trusted_contacts as tc_service
    from app.services.trusted_contacts import normalize_phone

    owner = await one(db, """SELECT u.id, u.display_name, u.phone_number, u.phone_verified FROM circle_links l
        JOIN users u ON u.id = l.owner_id WHERE l.id=:id AND l.recipient_id=:me AND l.revoked_at IS NULL""",
        id=link_id, me=user.id)
    if not owner:
        raise HTTPException(404, {"code": "MEMBERSHIP_NOT_FOUND", "message": "That circle is no longer linked to you."})
    state = await reverse_state(db, user.id, owner.id)
    if state:
        return {"status": state}
    phone = body.phone_number or (owner.phone_number if owner.phone_verified else None)
    if not phone:
        raise HTTPException(422, {"code": "PHONE_NEEDED",
                                  "message": f"Enter {display(owner.display_name)}'s phone number to add them."})
    # Same number written differently ("98444 44444" vs "+919844444444"): match on the last 10 digits, as phone match does.
    digits = re.sub(r"\D", "", normalize_phone(phone))
    contact_id = await db.scalar(text("""SELECT id FROM trusted_contacts WHERE owner_user_id=:me
        AND right(regexp_replace(normalized_phone_number, '\\D', '', 'g'), 10) = :last10 LIMIT 1"""),
        {"me": user.id, "last10": digits[-10:]})
    if contact_id is None:
        contact = await tc_service.create_contact(db, user.id, TrustedContactCreate(
            display_name=display(owner.display_name)[:256], phone_number=phone, label="Circle"))
        contact_id = contact["id"]
    await send_request(db, user, contact_id, owner.id)
    return {"status": "REQUESTED", "contact_id": contact_id}
