from datetime import datetime, timezone
import hashlib
from fastapi import HTTPException
from sqlalchemy import func, select, text, update
from app.database.v1 import events, incidents, invitations, responders, trusted_contact_grants, users
from app.services.geofence import evaluate, point


async def audit(db, incident_id, actor, event):
    await db.execute(events.insert().values(incident_id=incident_id, actor_user_id=actor, event_type=event))


async def incident_row(db, incident_id):
    geometry = func.Geometry(incidents.c.latest_location)
    query = select(incidents, func.ST_X(geometry).label("longitude"),
                   func.ST_Y(geometry).label("latitude")).where(incidents.c.id == incident_id).with_for_update()
    row = (await db.execute(query)).mappings().first()
    if row is None:
        raise HTTPException(404, "Incident unavailable")
    return row


def owner(incident, user):
    if incident.victim_user_id != user.id:
        raise HTTPException(404, "Incident unavailable")


def active(incident):
    if incident.status != "ACTIVE":
        raise HTTPException(409, "Incident is inactive")


async def update_fix(db, row, fix, settings):
    active(row)
    if row.location_recorded_at and fix.recorded_at <= row.location_recorded_at:
        if (fix.recorded_at, fix.latitude, fix.longitude, fix.accuracy_meters, fix.is_mock) == (
            row.location_recorded_at, row.latitude, row.longitude, row.location_accuracy_meters, row.location_is_mock):
            return False
        raise conflict("LOCATION_CONFLICT", "Location superseded or conflicting")
    if row.location_is_mock is not None and row.location_is_mock != fix.is_mock:
        raise HTTPException(422, "Location source type cannot change during a test incident")
    # Victim SOS rules: any fix up to max_sos_accuracy_meters is kept, and the very first fix may be the
    # phone's last-known position. Geofence matching separately requires precise, fresh fixes (FRESH).
    first = row.location_recorded_at is None
    fix.check(settings, max_accuracy=settings.max_sos_accuracy_meters,
              max_age=settings.max_last_known_age_seconds if first else settings.max_location_age_seconds)
    await db.execute(update(incidents).where(incidents.c.id == row.id).values(
        latest_location=point(fix.latitude, fix.longitude), location_accuracy_meters=fix.accuracy_meters,
        location_is_mock=fix.is_mock, location_recorded_at=fix.recorded_at, location_received_at=func.now()))
    await evaluate(db, row.id, settings)
    return True


def conflict(code, message, incident_id=None):
    """A 409 the app can act on: {"detail": {"code", "message", "incident_id"}} plus an X-Error-Code header."""
    detail = {"code": code, "message": message}
    if incident_id is not None:
        detail["incident_id"] = str(incident_id)
    return HTTPException(409, detail, headers={"X-Error-Code": code})


def request_digests(body):
    """Stable hash of the request without the GPS fix, so a retry carrying a newer fix is the same request.
    The second value is the pre-release hash of the full body, still accepted for incidents created before."""
    stable = hashlib.sha256(body.model_dump_json(exclude={"location"}).encode()).hexdigest()
    legacy = hashlib.sha256(body.model_dump_json().encode()).hexdigest()
    return stable, legacy


async def apply_creation_fix(db, row, fix, settings):
    """A fix sent with create is best effort: a stale, duplicate or invalid fix never fails the SOS itself."""
    if fix is None or row.status != "ACTIVE":
        return
    try:
        await update_fix(db, await incident_row(db, row.id), fix, settings)
    except HTTPException:
        pass


async def open_incident(db, user_id):
    return (await db.execute(select(incidents).where(incidents.c.victim_user_id == user_id,
        incidents.c.status != "CLOSED"))).mappings().first()


async def create(db, user, body, key, settings):
    digest, legacy = request_digests(body)
    # account() holds this owner's row lock for the whole request transaction.
    existing = (await db.execute(select(incidents).where(incidents.c.victim_user_id == user.id,
        incidents.c.idempotency_key == key))).mappings().first()
    if existing:
        if existing.request_hash not in (digest, legacy):
            raise conflict("IDEMPOTENCY_MISMATCH", "Idempotency key already used with different input", existing.id)
        await apply_creation_fix(db, existing, body.location, settings)
        return existing
    already = await open_incident(db, user.id)
    if already:
        raise conflict("INCIDENT_ALREADY_OPEN", "An incident is already open", already.id)
    if not body.is_test and not settings.real_incidents:
        raise HTTPException(403, {"code": "REAL_INCIDENTS_DISABLED",
                                  "message": "This server only accepts test incidents"})
    recent = await db.scalar(text("SELECT count(*) FROM incidents WHERE victim_user_id=:owner "
                                  "AND NOT is_test AND started_at > now() - interval '1 hour'"), {"owner": user.id})
    if not body.is_test and recent >= settings.sos_per_hour:
        raise HTTPException(429, {"code": "SOS_RATE_LIMITED",
                                  "message": f"At most {settings.sos_per_hour} SOS alerts per hour. Call 112 if you need help now."},
                            headers={"X-Error-Code": "SOS_RATE_LIMITED", "Retry-After": "600"})
    result = (await db.execute(incidents.insert().values(victim_user_id=user.id, idempotency_key=key,
        request_hash=digest, radius_meters=settings.radius_meters, is_test=body.is_test).returning(incidents))).mappings().one()
    await audit(db, result.id, user.id, "TEST_INCIDENT_CREATED" if body.is_test else "INCIDENT_CREATED")
    await apply_creation_fix(db, result, body.location, settings)
    return result


async def close_requests(db, incident_id, reason: str, keep_user=None):
    """Volunteers whose request is still open learn it closed (reason ENDED or FILLED): their phone stops alerting and
    shows "SOS ended" / "Help is already on the way". A push-only inbox row (hidden from the inbox) carries it."""
    from app.inbox import add_item
    rows = (await db.execute(text("""SELECT id, recipient_user_id FROM incident_invitations WHERE incident_id=:id
        AND status='PENDING' AND expires_at > now() AND (CAST(:keep AS uuid) IS NULL OR recipient_user_id <> :keep)"""),
        {"id": incident_id, "keep": keep_user})).all()
    for invitation_id, recipient in rows:
        item = await add_item(db, recipient, "SOS_REQUEST_CLOSED",
                              "Help is already on the way" if reason == "FILLED" else "SOS ended",
                              "You don't need to respond.",
                              data={"invitation_id": str(invitation_id), "incident_id": str(incident_id), "reason": reason},
                              incident_id=incident_id)
        await db.execute(text("UPDATE inbox_items SET read_at=now() WHERE id=:id"), {"id": item})
    return [invitation_id for invitation_id, _ in rows]


async def revoke_incident_access(db, incident_id, now):
    """Side effects shared by every way an incident closes: pending alerts stop and nobody keeps access."""
    await close_requests(db, incident_id, "ENDED")
    await db.execute(text("UPDATE notification_outbox SET delivery_status='CANCELLED' WHERE incident_id=:id "
                          "AND delivery_status IN ('PENDING','RETRY_SCHEDULED','SENDING')"), {"id": incident_id})
    await db.execute(text("UPDATE incident_invitations SET status='EXPIRED' WHERE incident_id=:id AND status='PENDING'"),
                     {"id": incident_id})
    await db.execute(update(responders).where(responders.c.incident_id == incident_id, responders.c.revoked_at.is_(None))
                     .values(authorization_status="REVOKED", revoked_at=now))
    await db.execute(text("UPDATE trusted_contact_grants SET authorization_status='REVOKED', revoked_at=:now "
                          "WHERE incident_id=:id AND authorization_status='AUTHORIZED'"), {"id": incident_id, "now": now})
    # Circle members who got the SOS push learn that it ended (the live link stops working by itself).
    from app.inbox import add_item
    rows = (await db.execute(text("""SELECT DISTINCT i.user_id, u.display_name FROM incident_contact_alerts a
        JOIN inbox_items i ON i.id = a.inbox_item_id JOIN incidents x ON x.id = a.incident_id
        JOIN users u ON u.id = x.victim_user_id WHERE a.incident_id=:id AND a.channel='PUSH'"""),
        {"id": incident_id})).all()
    for recipient, owner_name in rows:
        await add_item(db, recipient, "CIRCLE_SOS_ENDED", f"{(owner_name or '').strip() or 'Someone in your circle'} ended their SOS",
                       "Their location is no longer shared.", data={"incident_id": str(incident_id)}, incident_id=incident_id)


async def expire_orphaned(db, settings, batch=50):
    """Closes ACTIVE incidents nobody has touched for `orphaned_incident_hours`: no fix received and no
    responder activity in that window. Duress (ESCALATION_REQUIRED) and resolution requests are never touched."""
    rows = (await db.execute(text("""
        SELECT i.id, i.victim_user_id FROM incidents i
        WHERE i.status = 'ACTIVE'
          AND i.started_at < now() - make_interval(hours => :hours)
          AND COALESCE(i.location_received_at, i.started_at) < now() - make_interval(hours => :hours)
          AND NOT EXISTS (SELECT 1 FROM incident_responders r WHERE r.incident_id = i.id
              AND GREATEST(r.accepted_at, COALESCE(r.assistance_updated_at, r.accepted_at))
                  >= now() - make_interval(hours => :hours))
          AND NOT EXISTS (SELECT 1 FROM incident_invitations v WHERE v.incident_id = i.id
              AND COALESCE(v.responded_at, v.invited_at) >= now() - make_interval(hours => :hours))
        ORDER BY i.started_at LIMIT :batch FOR UPDATE OF i SKIP LOCKED"""),
        {"hours": settings.orphaned_incident_hours, "batch": batch})).all()
    now = datetime.now(timezone.utc)
    for incident_id, owner_id in rows:
        await db.execute(update(incidents).where(incidents.c.id == incident_id, incidents.c.status == "ACTIVE")
                         .values(status="CLOSED", ended_at=now))
        await revoke_incident_access(db, incident_id, now)
        # actor_user_id is NOT NULL; the owner is recorded and the metadata says the system closed it.
        await db.execute(events.insert().values(incident_id=incident_id, actor_user_id=owner_id,
            event_type="INCIDENT_AUTO_EXPIRED",
            metadata={"actor": "system", "idle_hours": settings.orphaned_incident_hours}))
    return [incident_id for incident_id, _ in rows]


async def may_read(db, incident, user):
    if incident.victim_user_id == user.id:
        return
    grant = responders.join(invitations, (responders.c.incident_id == invitations.c.incident_id) &
                            (responders.c.responder_user_id == invitations.c.recipient_user_id))
    authorized = await db.scalar(select(responders.c.id).select_from(grant).where(
        responders.c.incident_id == incident.id, responders.c.responder_user_id == user.id,
        responders.c.authorization_status == "AUTHORIZED", responders.c.revoked_at.is_(None),
        responders.c.access_expires_at > func.clock_timestamp(), invitations.c.status == "ACCEPTED"))
    if incident.status != "ACTIVE" or not user.responder_opt_in or not user.responder_available or not authorized:
        raise HTTPException(404, "Incident unavailable")


async def is_incident_active(db, incident_id):
    status = await db.scalar(select(incidents.c.status).where(incidents.c.id == incident_id))
    return status == "ACTIVE"


async def is_authorized_participant(db, incident_id, user_id):
    # Check if victim
    victim_id = await db.scalar(select(incidents.c.victim_user_id).where(
        incidents.c.id == incident_id, incidents.c.status == "ACTIVE"))
    if victim_id is None:
        return False
    if victim_id == user_id:
        return bool(await db.scalar(select(users.c.email_verified | users.c.phone_verified).where(
            users.c.id == user_id, users.c.account_status == "ACTIVE")))

    # Check if authorized responder
    grant = responders.join(invitations, (responders.c.incident_id == invitations.c.incident_id) &
                            (responders.c.responder_user_id == invitations.c.recipient_user_id))
    query = select(responders.c.id).select_from(grant).where(
        responders.c.incident_id == incident_id,
        responders.c.responder_user_id == user_id,
        responders.c.authorization_status == "AUTHORIZED",
        responders.c.revoked_at.is_(None),
        responders.c.access_expires_at > func.clock_timestamp(),
        invitations.c.status == "ACCEPTED"
    )
    if await db.scalar(query):
        user_ok = await db.scalar(select(users.c.id).where(
            users.c.id == user_id,
            users.c.account_status == "ACTIVE",
            users.c.responder_opt_in.is_(True),
            users.c.responder_available.is_(True)
        ))
        if user_ok is not None:
            return True

    # Check if authorized trusted contact
    tc_query = select(trusted_contact_grants.c.id).where(
        trusted_contact_grants.c.incident_id == incident_id,
        trusted_contact_grants.c.recipient_user_id == user_id,
        trusted_contact_grants.c.authorization_status == "AUTHORIZED",
        trusted_contact_grants.c.revoked_at.is_(None),
        trusted_contact_grants.c.access_expires_at > func.clock_timestamp()
    )
    if await db.scalar(tc_query):
        user_ok = await db.scalar(select(users.c.id).where(
            users.c.id == user_id,
            users.c.account_status == "ACTIVE"
        ))
        return user_ok is not None

    return False


async def build_snapshot(db, incident_id, user_id, role):
    from app.schemas import (ActiveResponderPublic, IncidentLocationDetail, IncidentSnapshot,
                             RequesterMembership, ResponderSummary)
    incident = await incident_row(db, incident_id)

    latest_loc = None
    if incident.latest_location is not None and incident.latitude is not None and incident.longitude is not None:
        latest_loc = IncidentLocationDetail(
            latitude=incident.latitude,
            longitude=incident.longitude,
            accuracy_meters=incident.location_accuracy_meters,
            recorded_at=incident.location_recorded_at,
            received_at=incident.location_received_at,
            is_mock=incident.location_is_mock
        )

    invited_cnt = await db.scalar(select(func.count(invitations.c.id)).where(invitations.c.incident_id == incident_id)) or 0
    accepted_cnt = await db.scalar(select(func.count(invitations.c.id)).where(
        invitations.c.incident_id == incident_id, invitations.c.status == "ACCEPTED")) or 0

    active_resp_q = select(func.count(responders.c.id)).where(
        responders.c.incident_id == incident_id,
        responders.c.authorization_status == "AUTHORIZED",
        responders.c.revoked_at.is_(None),
        responders.c.access_expires_at > func.clock_timestamp(),
        responders.c.assistance_status != "LEFT_INCIDENT"
    )
    active_resp_cnt = await db.scalar(active_resp_q) or 0

    responder_summary = ResponderSummary(
        invited_count=invited_cnt,
        accepted_count=accepted_cnt,
        active_responders_count=active_resp_cnt
    )

    auth_status = "AUTHORIZED"
    assist_status = None
    if role == "RESPONDER":
        grant = (await db.execute(select(responders.c.authorization_status, responders.c.assistance_status).where(
            responders.c.incident_id == incident_id, responders.c.responder_user_id == user_id
        ))).mappings().first()
        if grant:
            auth_status = grant.authorization_status
            assist_status = grant.assistance_status

    requester_membership = RequesterMembership(
        role="OWNER" if role == "OWNER" else "RESPONDER",
        authorization_status=auth_status,
        assistance_status=assist_status
    )

    active_rows = (await db.execute(text("""
        SELECT r.responder_user_id, r.assistance_status, r.assistance_updated_at, u.display_name,
               CASE WHEN l.recorded_at > now() - interval '10 minutes' THEN ST_Y(l.location::geometry) END AS latitude,
               CASE WHEN l.recorded_at > now() - interval '10 minutes' THEN ST_X(l.location::geometry) END AS longitude,
               CASE WHEN l.recorded_at > now() - interval '10 minutes' AND i.latest_location IS NOT NULL
                    THEN ST_Distance(l.location, i.latest_location) END AS distance_meters
        FROM incident_responders r JOIN users u ON u.id = r.responder_user_id
        JOIN incidents i ON i.id = r.incident_id
        LEFT JOIN responder_locations l ON l.user_id = r.responder_user_id
        WHERE r.incident_id = :id AND r.authorization_status = 'AUTHORIZED' AND r.revoked_at IS NULL
          AND r.access_expires_at > clock_timestamp() AND r.assistance_status <> 'LEFT_INCIDENT'
        ORDER BY r.accepted_at"""), {"id": incident_id})).mappings().all()

    owner = role == "OWNER"
    active_responders = [
        ActiveResponderPublic(
            responder_id="resp-" + r["responder_user_id"].hex[:8],
            assistance_status=r["assistance_status"],
            updated_at=r["assistance_updated_at"],
            display_name=((r["display_name"] or "").split() or ["Responder"])[0][:40],
            distance_meters=r["distance_meters"] if owner else None,
            latitude=r["latitude"] if owner else None,
            longitude=r["longitude"] if owner else None,
        )
        for r in active_rows
    ]
    victim_phone = None
    if role == "RESPONDER" and auth_status == "AUTHORIZED":
        victim_phone = await db.scalar(text("""SELECT phone_number FROM users WHERE id = :id AND share_phone_in_sos"""),
                                       {"id": incident.victim_user_id})

    return IncidentSnapshot(
        incident_id=incident_id,
        status=incident.status,
        is_test=incident.is_test,
        started_at=incident.started_at,
        latest_location=latest_loc,
        responder_summary=responder_summary,
        requester_membership=requester_membership,
        active_responders=active_responders,
        victim_phone=victim_phone
    )
