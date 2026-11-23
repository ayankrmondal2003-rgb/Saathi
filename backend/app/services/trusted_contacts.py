"""Service layer for trusted emergency contacts, preferences, alert dispatch, and tracking grants."""
import re
from datetime import datetime, timedelta, timezone
from uuid import UUID
from fastapi import HTTPException
from sqlalchemy import func, select, update, text
from app.database.v1 import incidents, trusted_contacts, trusted_contact_grants, notification_outbox, users


def normalize_phone(raw: str) -> str:
    trimmed = raw.strip()
    has_plus = trimmed.startswith("+")
    digits = re.sub(r"\D", "", trimmed)
    return f"+{digits}" if has_plus else digits


def mask_phone(phone: str) -> str:
    normalized = normalize_phone(phone)
    if len(normalized) <= 4:
        return "****"
    return f"{normalized[:3]}*****{normalized[-4:]}"


def format_emergency_message(display_name: str, lat: float | None, lng: float | None, recorded_at: datetime | None, include_location: bool) -> str:
    if include_location and lat is not None and lng is not None:
        ts_str = recorded_at.strftime("%Y-%m-%d %H:%M:%S UTC") if recorded_at else "recent"
        loc_link = f"https://maps.google.com/?q={lat:.6f},{lng:.6f}"
        return (f"Maitri SOS: I may need urgent help. My last available location is: {loc_link}. "
                f"Recorded at: {ts_str}. If you believe I am in immediate danger, contact emergency services (112).")
    return ("Maitri SOS: I may need urgent help. Location is currently unavailable. "
            "If you believe I am in immediate danger, contact emergency services (112).")


async def list_contacts(db, user_id: UUID) -> list[dict]:
    query = select(trusted_contacts).where(trusted_contacts.c.owner_user_id == user_id).order_by(
        trusted_contacts.c.is_primary.desc(), trusted_contacts.c.created_at.asc()
    )
    rows = (await db.execute(query)).mappings().all()
    return [dict(r) for r in rows]


async def create_contact(db, user_id: UUID, data) -> dict:
    normalized = normalize_phone(data.phone_number)
    if len(re.sub(r"\D", "", normalized)) < 7:
        raise HTTPException(422, "Phone number must have at least 7 digits")

    existing = await db.scalar(select(trusted_contacts.c.id).where(
        trusted_contacts.c.owner_user_id == user_id,
        trusted_contacts.c.normalized_phone_number == normalized
    ))
    if existing:
        raise HTTPException(409, "This phone number is already saved as a trusted contact.")

    existing_count = await db.scalar(select(func.count(trusted_contacts.c.id)).where(
        trusted_contacts.c.owner_user_id == user_id
    )) or 0

    should_be_primary = data.is_primary or existing_count == 0

    if should_be_primary and existing_count > 0:
        await db.execute(update(trusted_contacts).where(
            trusted_contacts.c.owner_user_id == user_id
        ).values(is_primary=False))

    now = datetime.now(timezone.utc)
    values = data.model_dump()
    values.update(
        owner_user_id=user_id,
        normalized_phone_number=normalized,
        is_primary=should_be_primary,
        created_at=now,
        updated_at=now
    )

    result = (await db.execute(trusted_contacts.insert().values(**values).returning(trusted_contacts))).mappings().one()
    return dict(result)


async def update_contact(db, user_id: UUID, contact_id: UUID, data) -> dict:
    row = (await db.execute(select(trusted_contacts).where(
        trusted_contacts.c.id == contact_id,
        trusted_contacts.c.owner_user_id == user_id
    ))).mappings().first()

    if not row:
        raise HTTPException(404, "Trusted contact not found")

    values = {k: v for k, v in data.model_dump().items() if v is not None}
    if "phone_number" in values:
        norm = normalize_phone(values["phone_number"])
        if len(re.sub(r"\D", "", norm)) < 7:
            raise HTTPException(422, "Phone number must have at least 7 digits")
        dup = await db.scalar(select(trusted_contacts.c.id).where(
            trusted_contacts.c.owner_user_id == user_id,
            trusted_contacts.c.normalized_phone_number == norm,
            trusted_contacts.c.id != contact_id
        ))
        if dup:
            raise HTTPException(409, "This phone number is already saved as another contact.")
        values["normalized_phone_number"] = norm

    if values.get("is_primary") is True:
        await db.execute(update(trusted_contacts).where(
            trusted_contacts.c.owner_user_id == user_id,
            trusted_contacts.c.id != contact_id
        ).values(is_primary=False))

    values["updated_at"] = datetime.now(timezone.utc)
    updated = (await db.execute(update(trusted_contacts).where(
        trusted_contacts.c.id == contact_id
    ).values(**values).returning(trusted_contacts))).mappings().one()
    return dict(updated)


async def delete_contact(db, user_id: UUID, contact_id: UUID):
    row = (await db.execute(select(trusted_contacts).where(
        trusted_contacts.c.id == contact_id,
        trusted_contacts.c.owner_user_id == user_id
    ))).mappings().first()

    if not row:
        raise HTTPException(404, "Trusted contact not found")

    was_primary = row["is_primary"]
    await db.execute(trusted_contacts.delete().where(trusted_contacts.c.id == contact_id))

    if was_primary:
        # Promote earliest remaining contact to primary
        earliest_id = await db.scalar(select(trusted_contacts.c.id).where(
            trusted_contacts.c.owner_user_id == user_id
        ).order_by(trusted_contacts.c.created_at.asc()).limit(1))
        if earliest_id:
            await db.execute(update(trusted_contacts).where(
                trusted_contacts.c.id == earliest_id
            ).values(is_primary=True))


async def set_primary_contact(db, user_id: UUID, contact_id: UUID) -> dict:
    row = (await db.execute(select(trusted_contacts).where(
        trusted_contacts.c.id == contact_id,
        trusted_contacts.c.owner_user_id == user_id
    ))).mappings().first()

    if not row:
        raise HTTPException(404, "Trusted contact not found")

    await db.execute(update(trusted_contacts).where(
        trusted_contacts.c.owner_user_id == user_id
    ).values(is_primary=False))

    updated = (await db.execute(update(trusted_contacts).where(
        trusted_contacts.c.id == contact_id
    ).values(is_primary=True, updated_at=func.now()).returning(trusted_contacts))).mappings().one()
    return dict(updated)


NO_SMS_PROVIDER = "NO_SMS_PROVIDER"


def contact_alert_status(notify_on_sos: bool, outbox_row) -> str:
    """Public status derived from recorded outbox state only; never reports delivery that did not happen."""
    if not notify_on_sos:
        return "NOT_REQUESTED"
    if outbox_row is None:
        return "NOT_SENT"
    if outbox_row["failure_reason"] == NO_SMS_PROVIDER:
        return NO_SMS_PROVIDER
    return outbox_row["delivery_status"]


async def incident_contact_statuses(db, user_id: UUID, incident_id: UUID) -> list[dict]:
    owned = await db.scalar(select(incidents.c.id).where(
        incidents.c.id == incident_id, incidents.c.victim_user_id == user_id))
    if owned is None:
        raise HTTPException(404, "Incident not found or unauthorized")
    rows = (await db.execute(select(notification_outbox.c.contact_id, notification_outbox.c.delivery_status,
        notification_outbox.c.failure_reason, notification_outbox.c.created_at).where(
        notification_outbox.c.incident_id == incident_id,
        notification_outbox.c.event_type == "TRUSTED_CONTACT_SOS_ALERT"))).mappings().all()
    by_contact = {r["contact_id"]: r for r in rows}
    now = datetime.now(timezone.utc)
    return [{
        "contact_id": c["id"],
        "display_name": c["display_name"],
        "masked_phone": mask_phone(c["phone_number"]),
        "is_primary": c["is_primary"],
        "delivery_status": contact_alert_status(c["notify_on_sos"], by_contact.get(c["id"])),
        "message_preview": "Maitri SOS: I may need urgent help.",
        "location_disclosed": c["include_location_link"],
        "tracking_granted": c["allow_live_tracking"],
        "updated_at": by_contact[c["id"]]["created_at"] if c["id"] in by_contact else now,
    } for c in await list_contacts(db, user_id)]


async def dispatch_incident_alerts(db, user_id: UUID, incident_id: UUID) -> dict:
    inc = (await db.execute(select(incidents).where(
        incidents.c.id == incident_id,
        incidents.c.victim_user_id == user_id
    ))).mappings().first()

    if not inc:
        raise HTTPException(404, "Incident not found or unauthorized")
    if inc["status"] != "ACTIVE":
        raise HTTPException(409, "Incident is not active")

    # Extract geometry point if available
    lat, lng = None, None
    if inc["latest_location"] is not None:
        coords = (await db.execute(select(
            func.ST_Y(func.Geometry(incidents.c.latest_location)).label("lat"),
            func.ST_X(func.Geometry(incidents.c.latest_location)).label("lng")
        ).where(incidents.c.id == incident_id))).mappings().first()
        if coords:
            lat, lng = coords["lat"], coords["lng"]

    contacts = await list_contacts(db, user_id)
    alerts_to_send = [c for c in contacts if c["notify_on_sos"]]

    statuses = []
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(hours=2)

    primary_name = None
    for c in alerts_to_send:
        if c["is_primary"]:
            primary_name = c["display_name"]

        msg = format_emergency_message(c["display_name"], lat, lng, inc["location_recorded_at"], c["include_location_link"])

        # Check for grant if live tracking allowed
        grant_created = False
        if c["allow_live_tracking"]:
            existing_grant = await db.scalar(select(trusted_contact_grants.c.id).where(
                trusted_contact_grants.c.incident_id == incident_id,
                trusted_contact_grants.c.contact_id == c["id"]
            ))
            if not existing_grant:
                await db.execute(trusted_contact_grants.insert().values(
                    incident_id=incident_id,
                    contact_id=c["id"],
                    recipient_user_id=user_id, # Scoped to this grant context
                    authorization_status="AUTHORIZED",
                    access_expires_at=expires_at,
                    granted_at=now
                ))
            grant_created = True

        # Check existing outbox to prevent duplicate alerts
        existing_outbox = await db.scalar(select(notification_outbox.c.id).where(
            notification_outbox.c.incident_id == incident_id,
            notification_outbox.c.contact_id == c["id"],
            notification_outbox.c.event_type == "TRUSTED_CONTACT_SOS_ALERT"
        ))

        # No SMS provider is integrated. Record the attempt as not sent instead of claiming provider acceptance;
        # FAILED rows are never picked up by the outbox worker.
        delivery_status = NO_SMS_PROVIDER
        if not existing_outbox:
            await db.execute(notification_outbox.insert().values(
                incident_id=incident_id,
                recipient_user_id=user_id,
                contact_id=c["id"],
                event_type="TRUSTED_CONTACT_SOS_ALERT",
                delivery_status="FAILED",
                failure_reason=NO_SMS_PROVIDER,
                created_at=now
            ))

        statuses.append({
            "contact_id": c["id"],
            "display_name": c["display_name"],
            "masked_phone": mask_phone(c["phone_number"]),
            "is_primary": c["is_primary"],
            "delivery_status": delivery_status,
            "message_preview": msg,
            "location_disclosed": c["include_location_link"] and lat is not None,
            "tracking_granted": grant_created,
            "updated_at": now
        })

    return {
        "incident_id": incident_id,
        "alerts_queued": len(statuses),
        "primary_contact_name": primary_name,
        "statuses": statuses
    }
