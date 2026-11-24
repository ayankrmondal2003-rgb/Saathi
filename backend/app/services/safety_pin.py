"""Argon2id-based Safety PIN and Duress PIN management, verification, and rate-limiting."""
from datetime import datetime, timedelta, timezone
from uuid import UUID
from argon2 import PasswordHasher, Type
from argon2.exceptions import VerifyMismatchError
from fastapi import HTTPException
from sqlalchemy import func, select, text, update
from sqlalchemy.dialects.postgresql import insert
from app.database.v1 import (events, incidents, invitations,
                             resolution_attempts, resolution_requests,
                             responders, safety_credentials,
                             trusted_contacts, trusted_contact_grants, notification_outbox)
import app.services.incidents as service

MAX_FAILED_ATTEMPTS = 5
LOCKOUT_SECONDS = 300  # 5 minutes temporary cooldown

_hasher = PasswordHasher(
    time_cost=2,
    memory_cost=65536,
    parallelism=1,
    hash_len=32,
    type=Type.ID
)


def validate_pin_strength(pin: str) -> None:
    if len(pin) != 6 or not pin.isdigit():
        raise HTTPException(422, "Safety PIN must consist of exactly 6 digits.")
    if len(set(pin)) == 1:
        raise HTTPException(422, "PIN cannot consist of a single repeated digit (e.g., 111111).")
    if pin in "0123456789" or "0123456789".find(pin) != -1:
        raise HTTPException(422, "PIN cannot be an obvious sequential sequence (e.g., 123456).")
    if pin in "9876543210" or "9876543210".find(pin) != -1:
        raise HTTPException(422, "PIN cannot be an obvious reverse sequence (e.g., 654321).")


def hash_pin(pin: str) -> str:
    return _hasher.hash(pin)


def verify_hash(pin_hash: str, pin: str) -> bool:
    try:
        return _hasher.verify(pin_hash, pin)
    except VerifyMismatchError:
        return False
    except Exception:
        return False


async def get_credentials(db, user_id: UUID) -> dict[str, dict]:
    rows = (await db.execute(
        select(safety_credentials).where(safety_credentials.c.user_id == user_id)
    )).mappings().all()
    return {row["credential_type"]: dict(row) for row in rows}


async def get_status(db, user_id: UUID):
    creds = await get_credentials(db, user_id)
    primary = creds.get("PRIMARY_PIN")
    duress = creds.get("DURESS_PIN")

    failed_count = primary["failed_attempt_count"] if primary else 0
    locked_until = primary["locked_until"] if primary else None
    now = datetime.now(timezone.utc)

    is_locked = bool(locked_until and locked_until > now)
    retry_after = int((locked_until - now).total_seconds()) if is_locked else 0

    return {
        "has_primary_pin": primary is not None,
        "has_duress_pin": duress is not None,
        "failed_attempt_count": failed_count,
        "is_locked": is_locked,
        "locked_until": locked_until,
        "retry_after_seconds": max(0, retry_after)
    }


async def setup_pin(db, user_id: UUID, pin: str, confirm_pin: str, credential_type: str = "PRIMARY_PIN"):
    if pin != confirm_pin:
        raise HTTPException(400, "PIN and confirmation PIN do not match.")
    validate_pin_strength(pin)

    creds = await get_credentials(db, user_id)
    other_type = "DURESS_PIN" if credential_type == "PRIMARY_PIN" else "PRIMARY_PIN"
    other_cred = creds.get(other_type)
    if other_cred and verify_hash(other_cred["pin_hash"], pin):
        raise HTTPException(422, "Primary PIN and Duress PIN must be distinct.")

    now = datetime.now(timezone.utc)
    pin_hash = hash_pin(pin)

    query = insert(safety_credentials).values(
        user_id=user_id,
        credential_type=credential_type,
        pin_hash=pin_hash,
        created_at=now,
        updated_at=now,
        last_changed_at=now,
        failed_attempt_count=0,
        locked_until=None
    )
    inserted = await db.scalar(query.on_conflict_do_nothing().returning(safety_credentials.c.user_id))
    if inserted is None:
        raise HTTPException(409, "PIN already enrolled. Use PIN change or recovery.")
    return True


async def change_pin(db, user_id: UUID, current_pin: str, new_pin: str, confirm_pin: str, credential_type: str = "PRIMARY_PIN"):
    creds = await get_credentials(db, user_id)
    cred = creds.get(credential_type)
    if not cred:
        raise HTTPException(404, "No existing Safety PIN configured.")

    now = datetime.now(timezone.utc)
    locked_until = cred.get("locked_until")
    if locked_until and locked_until > now:
        retry_after = int((locked_until - now).total_seconds())
        raise HTTPException(429, f"Rate limited. Verification locked for {retry_after} seconds.")

    if not verify_hash(cred["pin_hash"], current_pin):
        new_count = cred["failed_attempt_count"] + 1
        new_lock = now + timedelta(seconds=LOCKOUT_SECONDS) if new_count >= MAX_FAILED_ATTEMPTS else None
        await db.execute(
            update(safety_credentials).where(
                safety_credentials.c.user_id == user_id,
                safety_credentials.c.credential_type == credential_type
            ).values(failed_attempt_count=new_count, locked_until=new_lock, updated_at=now)
        )
        await db.commit()
        remaining = max(0, MAX_FAILED_ATTEMPTS - new_count)
        raise HTTPException(403, f"Current PIN is incorrect. {remaining} attempt(s) remaining.")

    if new_pin != confirm_pin:
        raise HTTPException(400, "New PIN and confirmation PIN do not match.")
    validate_pin_strength(new_pin)

    if current_pin == new_pin:
        raise HTTPException(422, "New PIN must be different from current PIN.")

    other_type = "DURESS_PIN" if credential_type == "PRIMARY_PIN" else "PRIMARY_PIN"
    other_cred = creds.get(other_type)
    if other_cred and verify_hash(other_cred["pin_hash"], new_pin):
        raise HTTPException(422, "Primary PIN and Duress PIN must be distinct.")

    new_hash = hash_pin(new_pin)
    await db.execute(
        update(safety_credentials).where(
            safety_credentials.c.user_id == user_id,
            safety_credentials.c.credential_type == credential_type
        ).values(
            pin_hash=new_hash,
            last_changed_at=now,
            updated_at=now,
            failed_attempt_count=0,
            locked_until=None
        )
    )
    return True


async def recover_pin(db, user_id: UUID, new_pin: str, confirm_pin: str):
    if new_pin != confirm_pin:
        raise HTTPException(400, "New PIN and confirmation PIN do not match.")
    validate_pin_strength(new_pin)

    now = datetime.now(timezone.utc)
    credentials = await get_credentials(db, user_id)
    primary = credentials.get("PRIMARY_PIN")
    if primary:
        locked_until = primary["locked_until"]
        changed = primary["last_changed_at"]
        if locked_until and locked_until > now:
            raise HTTPException(429, "Safety PIN recovery is temporarily locked.")
        if changed and changed > primary["created_at"] and changed + timedelta(seconds=60) > now:
            raise HTTPException(429, "Wait one minute after a PIN change before recovering again.")
    duress = credentials.get("DURESS_PIN")
    if duress and verify_hash(duress["pin_hash"], new_pin):
        raise HTTPException(422, "Primary PIN and Duress PIN must be distinct.")
    new_hash = hash_pin(new_pin)

    query = insert(safety_credentials).values(
        user_id=user_id,
        credential_type="PRIMARY_PIN",
        pin_hash=new_hash,
        created_at=now,
        updated_at=now,
        last_changed_at=now,
        failed_attempt_count=0,
        locked_until=None
    )
    query = query.on_conflict_do_update(
        index_elements=[safety_credentials.c.user_id, safety_credentials.c.credential_type],
        set_={
            "pin_hash": new_hash,
            "updated_at": now,
            "last_changed_at": now,
            "failed_attempt_count": 0,
            "locked_until": None
        }
    )
    await db.execute(query)
    return True


async def resolve_incident_with_pin(db, hub, incident_id: UUID, user, pin: str, reason: str = "USER_RESOLVED"):
    incident = await service.incident_row(db, incident_id)

    # 1. Ownership validation
    if incident.victim_user_id != user.id:
        await db.execute(resolution_attempts.insert().values(
            incident_id=incident_id,
            actor_user_id=user.id,
            attempt_result="UNAUTHORIZED",
            metadata={"reason": "Non-owner attempt to resolve incident"}
        ))
        await db.commit()
        raise HTTPException(403, "Only the incident owner can resolve the incident.")

    # 2. Idempotent check
    if incident.status == "CLOSED":
        return {
            "incident_id": incident.id,
            "status": "CLOSED",
            "resolution_status": "CONFIRMED",
            "message": "Incident is already closed.",
            "ended_at": incident.ended_at
        }

    # 3. Check credentials exist
    creds = await get_credentials(db, user.id)
    primary = creds.get("PRIMARY_PIN")
    if not primary:
        await db.execute(resolution_attempts.insert().values(
            incident_id=incident_id,
            actor_user_id=user.id,
            attempt_result="USER_NOT_ENROLLED",
            metadata={"reason": "User attempted resolution without configured PIN"}
        ))
        await db.commit()
        raise HTTPException(400, "Safety PIN not enrolled. Configure your Safety PIN in settings first.")

    now = datetime.now(timezone.utc)
    locked_until = primary.get("locked_until")
    if locked_until and locked_until > now:
        retry_after = int((locked_until - now).total_seconds())
        await db.execute(resolution_attempts.insert().values(
            incident_id=incident_id,
            actor_user_id=user.id,
            attempt_result="RATE_LIMITED",
            metadata={"retry_after_seconds": retry_after}
        ))
        await db.commit()
        raise HTTPException(429, f"Safety PIN verification is rate-limited. Retry in {retry_after} seconds.")

    # 4. Check PRIMARY_PIN
    if verify_hash(primary["pin_hash"], pin):
        await db.execute(
            update(safety_credentials).where(
                safety_credentials.c.user_id == user.id,
                safety_credentials.c.credential_type == "PRIMARY_PIN"
            ).values(failed_attempt_count=0, locked_until=None, updated_at=now)
        )
        await db.execute(resolution_attempts.insert().values(
            incident_id=incident_id,
            actor_user_id=user.id,
            attempt_result="SUCCESS",
            metadata={"reason": reason}
        ))
        await db.execute(resolution_requests.insert().values(
            incident_id=incident_id,
            requester_user_id=user.id,
            status="VERIFIED",
            verified_at=now,
            completed_at=now,
            reason=reason
        ))
        await db.execute(
            update(incidents).where(incidents.c.id == incident_id).values(
                status="CLOSED",
                ended_at=now
            )
        )
        await service.revoke_incident_access(db, incident_id, now)
        closure_contacts = (await db.execute(
            select(trusted_contacts.c.id).where(
                trusted_contacts.c.owner_user_id == user.id,
                trusted_contacts.c.notify_on_closure.is_(True)
            )
        )).scalars().all()
        for cid in closure_contacts:
            await db.execute(notification_outbox.insert().values(
                incident_id=incident_id,
                recipient_user_id=user.id,
                contact_id=cid,
                event_type="TRUSTED_CONTACT_INCIDENT_CLOSED",
                delivery_status="QUEUED",
                created_at=now
            ))
        await service.audit(db, incident_id, user.id, "INCIDENT_RESOLVED_WITH_SAFETY_PIN")
        await db.commit()
        await hub.close_incident(incident_id, reason="Incident resolved with Safety PIN")

        return {
            "incident_id": incident_id,
            "status": "CLOSED",
            "resolution_status": "CONFIRMED",
            "message": "Incident closed after PIN verification.",
            "ended_at": now
        }

    # 5. Check DURESS_PIN (Coercion safeguard / development simulation)
    duress = creds.get("DURESS_PIN")
    if duress and verify_hash(duress["pin_hash"], pin):
        await db.execute(resolution_attempts.insert().values(
            incident_id=incident_id,
            actor_user_id=user.id,
            attempt_result="DURESS_SIMULATION",
            metadata={"reason": "Duress PIN entered"}
        ))
        await db.execute(resolution_requests.insert().values(
            incident_id=incident_id,
            requester_user_id=user.id,
            status="DURESS_FLAGGED",
            verified_at=now,
            reason="DURESS_SIMULATION"
        ))
        await db.execute(
            update(incidents).where(incidents.c.id == incident_id).values(
                status="ESCALATION_REQUIRED"
            )
        )
        await service.audit(db, incident_id, user.id, "DURESS_SIMULATION_TRIGGERED")
        # In duress simulation: live tracking and responder connections remain running!
        return {
            "incident_id": incident_id,
            "status": "ESCALATION_REQUIRED",
            "resolution_status": "DURESS_SIMULATION",
            "message": "Resolution request recorded.",
            "ended_at": None
        }

    # 6. INCORRECT PIN
    new_count = primary["failed_attempt_count"] + 1
    new_lock = now + timedelta(seconds=LOCKOUT_SECONDS) if new_count >= MAX_FAILED_ATTEMPTS else None
    await db.execute(
        update(safety_credentials).where(
            safety_credentials.c.user_id == user.id,
            safety_credentials.c.credential_type == "PRIMARY_PIN"
        ).values(failed_attempt_count=new_count, locked_until=new_lock, updated_at=now)
    )
    await db.execute(resolution_attempts.insert().values(
        incident_id=incident_id,
        actor_user_id=user.id,
        attempt_result="INCORRECT_PIN",
        metadata={"failed_attempt_count": new_count}
    ))
    await service.audit(db, incident_id, user.id, "RESOLUTION_FAILED_INCORRECT_PIN")
    await db.commit()

    remaining = max(0, MAX_FAILED_ATTEMPTS - new_count)
    if new_lock:
        raise HTTPException(429, "Too many incorrect attempts. Safety PIN verification is temporarily locked.")
    raise HTTPException(403, f"Incorrect Safety PIN. {remaining} attempt(s) remaining.")
