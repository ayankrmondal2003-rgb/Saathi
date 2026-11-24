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


def contact_alert_status(notify_on_sos: bool, alert) -> str:
    """Per-contact status from recorded facts only. Never claims an SMS was sent or a push was read unless it was.

    NOT_REQUESTED: SOS alerts are off for this contact.
    SENDING / PUSH_SENT / DELIVERED / OPENED: a push to their linked Maitri account (DELIVERED once their phone
        confirmed receipt, OPENED once they opened it).
    SMS_PREPARED: the owner's messaging app was opened with this contact; Maitri can't tell if it was sent.
    NOT_REACHED: nothing has reached them yet (no app link and no SMS prepared, or the push failed).
    """
    if not notify_on_sos:
        return "NOT_REQUESTED"
    if alert is None:
        return "NOT_REACHED"
    if alert["channel"] == "SMS":
        return "SMS_PREPARED" if alert["sms_prepared_at"] else "NOT_REACHED"
    if alert["read_at"]:
        return "OPENED"
    return {"PENDING": "SENDING", "SENT": "PUSH_SENT", "DELIVERED": "DELIVERED"}.get(alert["push_status"], "NOT_REACHED")


async def _owned_incident(db, user_id, incident_id):
    inc = (await db.execute(select(incidents).where(
        incidents.c.id == incident_id, incidents.c.victim_user_id == user_id))).mappings().first()
    if not inc:
        raise HTTPException(404, "Incident not found or unauthorized")
    return inc


async def incident_contact_statuses(db, user_id: UUID, incident_id: UUID) -> list[dict]:
    await _owned_incident(db, user_id, incident_id)
    rows = (await db.execute(text("""SELECT a.contact_id, a.channel, a.sms_prepared_at, a.created_at,
        i.push_status, i.read_at, coalesce(i.read_at, i.delivered_at, i.pushed_at, a.sms_prepared_at, a.created_at) AS updated_at
        FROM incident_contact_alerts a LEFT JOIN inbox_items i ON i.id = a.inbox_item_id
        WHERE a.incident_id=:id"""), {"id": incident_id})).mappings().all()
    by_contact = {r["contact_id"]: r for r in rows}
    now = datetime.now(timezone.utc)
    return [{
        "contact_id": c["id"],
        "display_name": c["display_name"],
        "masked_phone": mask_phone(c["phone_number"]),
        "is_primary": c["is_primary"],
        "delivery_status": contact_alert_status(c["notify_on_sos"], by_contact.get(c["id"])),
        "message_preview": "I need help. This is my live location from Maitri.",
        "location_disclosed": c["include_location_link"],
        "tracking_granted": c["id"] in by_contact and by_contact[c["id"]]["channel"] == "PUSH",
        "updated_at": by_contact[c["id"]]["updated_at"] if c["id"] in by_contact else now,
    } for c in await list_contacts(db, user_id)]


async def dispatch_incident_alerts(db, user, incident_id: UUID, live_link: str | None = None) -> dict:
    """Idempotent: each SOS-enabled contact is alerted once per incident.

    A contact linked to a Maitri account gets an inbox item, which is pushed to that account's phones. Every
    other contact is returned in sms_contacts so the owner's phone can open its messaging app for them.
    """
    from app.inbox import add_item  # avoid an import cycle with app.core users
    inc = await _owned_incident(db, user.id, incident_id)
    if inc["status"] != "ACTIVE":
        raise HTTPException(409, "Incident is not active")
    owner_name = (user.display_name or "").strip() or "Someone in your circle"
    contacts = [c for c in await list_contacts(db, user.id) if c["notify_on_sos"]]
    links = dict((await db.execute(text("""SELECT contact_id, recipient_id FROM circle_links
        WHERE owner_id=:owner AND revoked_at IS NULL"""), {"owner": user.id})).all())
    existing = {r[0]: r[1] for r in (await db.execute(text(
        "SELECT contact_id, channel FROM incident_contact_alerts WHERE incident_id=:id"), {"id": incident_id})).all()}
    sms = []
    for c in contacts:
        recipient = links.get(c["id"])
        if c["id"] in existing:
            if existing[c["id"]] == "SMS":
                sms.append(c)
            continue
        if recipient is not None:
            item = await add_item(db, recipient, "CIRCLE_SOS", f"{owner_name} needs help",
                                  "They started an SOS in Maitri. Tap to see their live location.",
                                  data={"url": live_link or "", "incident_id": str(incident_id)}, incident_id=incident_id)
            await db.execute(text("""INSERT INTO incident_contact_alerts(incident_id, contact_id, channel, inbox_item_id)
                VALUES (:i, :c, 'PUSH', :item)"""), {"i": incident_id, "c": c["id"], "item": item})
        else:
            await db.execute(text("""INSERT INTO incident_contact_alerts(incident_id, contact_id, channel)
                VALUES (:i, :c, 'SMS')"""), {"i": incident_id, "c": c["id"]})
            sms.append(c)
    statuses = await incident_contact_statuses(db, user.id, incident_id)
    primary = next((c["display_name"] for c in contacts if c["is_primary"]), None)
    return {"incident_id": incident_id, "alerts_queued": len(contacts), "primary_contact_name": primary,
            "statuses": [s for s in statuses if s["delivery_status"] != "NOT_REQUESTED"],
            "live_link": live_link,
            "sms_contacts": [{"contact_id": c["id"], "display_name": c["display_name"],
                              "phone_number": c["phone_number"]} for c in sms]}


async def mark_sms_prepared(db, user_id, incident_id, contact_ids):
    await _owned_incident(db, user_id, incident_id)
    await db.execute(text("""UPDATE incident_contact_alerts SET sms_prepared_at = coalesce(sms_prepared_at, now())
        WHERE incident_id=:i AND channel='SMS' AND contact_id = ANY(CAST(:ids AS uuid[]))"""),
        {"i": incident_id, "ids": [str(x) for x in contact_ids]})
