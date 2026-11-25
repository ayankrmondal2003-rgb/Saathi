import asyncio
import json
import logging
import hashlib
from datetime import datetime, timedelta, timezone
from redis.exceptions import RedisError
from uuid import UUID, uuid4
from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, WebSocket, WebSocketDisconnect
from sqlalchemy import case, func, select, update, text
from sqlalchemy.dialects.postgresql import insert
from app.core import account, database, identity
from app.database.v1 import devices, events, incidents, invitations, locations, responders, users
from typing import Literal
from pydantic import Field
from app.schemas import Input
from app.schemas import (
    ActiveIncident, CreateIncident, Decision, DeviceRegister, Fix, Incident, IncidentResolutionRequest,
    IncidentResolutionResponse, IncidentSnapshot, Profile, ProfileDetailsEdit, ProfileEdit, ResponderStatusUpdate,
    SafetyPinChange, SafetyPinRecovery, SafetyPinSetup, SafetyPinStatus, TestAlertResponse,
    TrustedContact, TrustedContactAlertBatchResponse, TrustedContactCreate, TrustedContactUpdate,
    IncidentContactAlertStatus, WsTicketResponse
, SmsPrepared)
from app.services import incidents as service
from app.services import safety_pin as safety_pin_service
from app.services import trusted_contacts as tc_service
from app.services.geofence import point, responder_changed
from app.services.outbox_worker import default_firebase_sender
from app.circle import request_phone_matches
from app.live_link import public_link
from app.privacy import notes_for_responder
from app.services.realtime import hub as default_hub, PROTOCOL_VERSION, RealtimeEvent

logger = logging.getLogger("surakshanet.routes")

def get_hub(request_or_ws):
    return getattr(request_or_ws.app.state, "hub", default_hub)

router = APIRouter(prefix="/api")
ws_router = APIRouter()


@router.get("/users/me", response_model=Profile)
async def me(user=Depends(account)):
    return {field: user[field] for field in Profile.model_fields}


@router.put("/users/me", response_model=Profile)
async def profile(body: ProfileEdit, request: Request, user=Depends(account), db=Depends(database)):
    if body.responder_available and not body.responder_opt_in:
        raise HTTPException(422, "Opt in before becoming available")
    if not body.responder_opt_in:
        await db.execute(locations.delete().where(locations.c.user_id == user.id))
    if not body.responder_available:
        await db.execute(update(responders).where(responders.c.responder_user_id == user.id,
            responders.c.revoked_at.is_(None)).values(authorization_status="REVOKED", revoked_at=func.now()))
    result = dict((await db.execute(update(users).where(users.c.id == user.id).values(
        **body.model_dump(), updated_at=func.now()).returning(users))).mappings().one())
    if body.responder_available and not user.responder_available:
        await responder_changed(db, user.id, request.app.state.settings, force=True)
    return {field: result[field] for field in Profile.model_fields}


@router.patch("/users/me", response_model=Profile)
async def profile_details(body: ProfileDetailsEdit, user=Depends(account), db=Depends(database)):
    changes = body.model_dump(exclude_unset=True)
    if not changes:
        return {field: user[field] for field in Profile.model_fields}
    result = (await db.execute(update(users).where(users.c.id == user.id).values(
        **changes, updated_at=func.now()).returning(users))).mappings().one()
    return {field: result[field] for field in Profile.model_fields}


@router.put("/responders/location", status_code=204)
async def responder_fix(body: Fix, request: Request, user=Depends(account), db=Depends(database)):
    if not user.responder_available or not user.responder_opt_in:
        raise HTTPException(403, "Responder consent and availability required")
    body.check(request.app.state.settings)
    query = insert(locations).values(user_id=user.id, location=point(body.latitude, body.longitude),
        **body.model_dump(exclude={"latitude","longitude"}))
    columns = ("location","accuracy_meters","recorded_at","is_mock")
    query = query.on_conflict_do_update(index_elements=["user_id"],
        set_={**{c: query.excluded[c] for c in columns}, "received_at": func.now()},
        where=locations.c.recorded_at < query.excluded.recorded_at)
    if await db.scalar(query.returning(locations.c.user_id)) is None:
        raise HTTPException(409, "A location with this timestamp or newer already exists",
                            headers={"X-Error-Code": "STALE_OR_DUPLICATE_LOCATION"})
    await responder_changed(db, user.id, request.app.state.settings)
    return Response(status_code=204)


@router.post("/incidents", response_model=Incident)
async def create(body: CreateIncident, request: Request, idempotency_key: UUID = Header(),
                 user=Depends(account), db=Depends(database)):
    return dict(await service.create(db, user, body, idempotency_key, request.app.state.settings))


@router.get("/incidents/active", response_model=ActiveIncident, responses={204: {"description": "No open incident"}})
async def active_incident(user=Depends(account), db=Depends(database)):
    """The caller's own open (non-CLOSED) incident, so the app can resume it after losing local state."""
    row = await service.open_incident(db, user.id)
    return Response(status_code=204) if row is None else dict(row)


@router.post("/incidents/{incident_id}/location", status_code=204)
async def incident_fix(incident_id: UUID, body: Fix, request: Request, user=Depends(account), db=Depends(database)):
    row = await service.incident_row(db, incident_id)
    service.owner(row, user)
    if not await service.update_fix(db, row, body, request.app.state.settings):
        return Response(status_code=204)

    # Broadcast real-time location update to authorized participants
    authorizer = lambda inc_id, u_id: service.is_authorized_participant(db, inc_id, u_id)
    await get_hub(request).broadcast(
        incident_id,
        "LOCATION_UPDATED",
        {
            "latitude": body.latitude,
            "longitude": body.longitude,
            "accuracy_meters": body.accuracy_meters,
            "recorded_at": body.recorded_at.isoformat(),
            "received_at": datetime.now(timezone.utc).isoformat(),
            "is_mock": body.is_mock
        },
        authorizer=authorizer
    )
    return Response(status_code=204)


@router.get("/incidents/{incident_id}/location")
async def location(incident_id: UUID, user=Depends(account), db=Depends(database)):
    row = await service.incident_row(db, incident_id)
    await service.may_read(db, row, user)
    return {"is_test": row.is_test, "status": row.status, "latitude": row.latitude, "longitude": row.longitude,
            "accuracy_meters": row.location_accuracy_meters, "recorded_at": row.location_recorded_at,
            "received_at": row.location_received_at, "is_mock": row.location_is_mock}


@router.get("/incidents/{incident_id}/snapshot", response_model=IncidentSnapshot)
async def snapshot(incident_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    incident = await service.incident_row(db, incident_id)
    await service.may_read(db, incident, user)
    role = "OWNER" if incident.victim_user_id == user.id else "RESPONDER"
    result = await service.build_snapshot(db, incident_id, user.id, role)
    if role == "RESPONDER" and result.requester_membership.authorization_status == "AUTHORIZED":
        result.medical_notes = await notes_for_responder(db, request.app.state.settings, incident)
    return result


@router.post("/incidents/{incident_id}/ws-ticket", response_model=WsTicketResponse)
async def ws_ticket(incident_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    incident = await service.incident_row(db, incident_id)
    service.active(incident)
    role = "OWNER" if incident.victim_user_id == user.id else "RESPONDER"
    if role == "RESPONDER":
        await service.may_read(db, incident, user)
    app_hub = get_hub(request)
    try:
        ticket_data = await app_hub.ticket_store.issue_ticket(user.id, incident_id, role)
    except (RedisError, OSError):
        raise HTTPException(503, "Realtime service unavailable") from None
    return {
        "ticket": ticket_data.ticket,
        "expires_in": 60,
        "expires_at": ticket_data.expires_at,
        "role": role
    }


@router.post("/incidents/{incident_id}/request-help")
async def request_help(incident_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    from app.services.moving_geofence import invite, parameters

    incident = await service.incident_row(db, incident_id)
    service.active(incident)
    if incident.victim_user_id == user.id:
        raise HTTPException(403, "Authorized responder required")
    await service.may_read(db, incident, user)
    queued = await invite(db, dict(parameters(request.app.state.settings), id=incident_id))
    return {"queued": len(queued)}


@router.put("/incidents/{incident_id}/responders/status")
async def update_responder_status(incident_id: UUID, body: ResponderStatusUpdate,
                                  request: Request, user=Depends(account), db=Depends(database)):
    incident = await service.incident_row(db, incident_id)
    if incident.victim_user_id == user.id:
        raise HTTPException(400, "Incident owner cannot set responder assistance status")
    await service.may_read(db, incident, user)

    app_hub = get_hub(request)
    now = datetime.now(timezone.utc)
    if body.status == "LEFT_INCIDENT":
        await db.execute(update(responders).where(
            responders.c.incident_id == incident_id,
            responders.c.responder_user_id == user.id,
            responders.c.revoked_at.is_(None)
        ).values(
            authorization_status="REVOKED",
            revoked_at=now,
            assistance_status="LEFT_INCIDENT",
            assistance_updated_at=now
        ))
        await service.audit(db, incident_id, user.id, "RESPONDER_LEFT_INCIDENT")
        await app_hub.disconnect_user(incident_id, user.id, reason="Responder left incident")
        await app_hub.broadcast(incident_id, "RESPONDER_LEFT", {
            "responder_id": "resp-" + user.id.hex[:8],
            "status": "LEFT_INCIDENT"
        })
        return {"status": "LEFT_INCIDENT"}

    await db.execute(update(responders).where(
        responders.c.incident_id == incident_id,
        responders.c.responder_user_id == user.id,
        responders.c.revoked_at.is_(None)
    ).values(
        assistance_status=body.status,
        assistance_updated_at=now
    ))
    await service.audit(db, incident_id, user.id, f"RESPONDER_STATUS_{body.status}")
    await app_hub.broadcast(incident_id, "RESPONDER_STATUS_CHANGED", {
        "responder_id": "resp-" + user.id.hex[:8],
        "status": body.status,
        "updated_at": now.isoformat()
    })
    return {"status": body.status}


@router.get("/responders/invitations")
async def inbox(user=Depends(account), db=Depends(database)):
    status = case(((invitations.c.status == "PENDING") & (invitations.c.expires_at <= func.now()),
                   "EXPIRED"), else_=invitations.c.status)
    rows = await db.execute(select(invitations.c.id, invitations.c.incident_id, status.label("status"),
        invitations.c.expires_at, incidents.c.is_test).select_from(invitations.join(incidents,
        incidents.c.id == invitations.c.incident_id)).where(invitations.c.recipient_user_id == user.id)
        .order_by(invitations.c.invited_at.desc()).limit(100))
    result = [dict(row) for row in rows.mappings()]
    # Before accepting, a responder learns only a distance band, never coordinates.
    distances = dict((await db.execute(text("""SELECT v.id, ST_Distance(l.location, i.latest_location)
        FROM incident_invitations v JOIN incidents i ON i.id = v.incident_id
        JOIN responder_locations l ON l.user_id = v.recipient_user_id
        WHERE v.recipient_user_id = :me AND v.status = 'PENDING' AND i.latest_location IS NOT NULL"""),
        {"me": user.id})).all())
    for row in result:
        row["distance_band"] = distance_band(distances.get(row["id"]))
    return result


def distance_band(meters):
    if meters is None:
        return None
    for limit, label in ((500, "Under 500 m"), (1000, "500 m – 1 km"), (2000, "1–2 km"), (5000, "2–5 km")):
        if meters < limit:
            return label
    return "Over 5 km"


class ResponderReport(Input):
    reason: Literal["FALSE_ALARM", "UNSAFE", "OTHER"]
    note: str = Field(default="", max_length=500)


@router.post("/incidents/{incident_id}/report", status_code=204)
async def report_incident(incident_id: UUID, body: ResponderReport, user=Depends(account), db=Depends(database)):
    """An accepted responder flags an SOS (e.g. a false alarm). Recorded for review; nothing changes for the owner."""
    incident = await service.incident_row(db, incident_id)
    if incident.victim_user_id == user.id:
        raise HTTPException(403, "Only responders can report an SOS")
    await service.may_read(db, incident, user)
    await db.execute(events.insert().values(incident_id=incident_id, actor_user_id=user.id, event_type="RESPONDER_REPORT",
                                            metadata={"reason": body.reason, "note": body.note.strip()[:500]}))
    return Response(status_code=204)


@router.post("/responders/invitations/{invitation_id}/response")
async def respond(invitation_id: UUID, body: Decision, request: Request, user=Depends(account), db=Depends(database)):
    match = (invitations.c.id == invitation_id) & (invitations.c.recipient_user_id == user.id)
    incident_id = await db.scalar(select(invitations.c.incident_id).where(match))
    if not incident_id:
        raise HTTPException(404, "Invitation unavailable")
    incident = await service.incident_row(db, incident_id)
    service.active(incident)
    invitation = (await db.execute(select(invitations).where(match).with_for_update())).mappings().one()
    if invitation.status == body.decision:
        if body.decision == "ACCEPTED":
            await service.may_read(db, incident, user)
        return {"status": invitation.status}
    if invitation.status == "EXPIRED":
        return {"status": "EXPIRED"}
    if invitation.status != "PENDING":
        raise HTTPException(409, "Invitation already answered")
    now = datetime.now(timezone.utc)
    await db.execute(text("UPDATE notification_outbox SET delivery_status='CANCELLED' WHERE invitation_id=:id"),
                     {"id":invitation_id})
    if invitation.expires_at <= now:
        await db.execute(update(invitations).where(match).values(status="EXPIRED"))
        return {"status": "EXPIRED"}
    if body.decision == "ACCEPTED":
        if not user.responder_opt_in or not user.responder_available:
            raise HTTPException(403, "Responder unavailable")
        # Re-checked at acceptance: approval may be revoked after the invitation was sent.
        if await db.scalar(text("SELECT status FROM volunteer_applications WHERE user_id=:id"), {"id": user.id}) != "APPROVED":
            raise HTTPException(403, "Volunteer approval required")
        settings = request.app.state.settings
        eligible = await db.scalar(select(locations.c.user_id).select_from(locations.join(incidents,
            func.ST_DWithin(locations.c.location, incidents.c.latest_location, incidents.c.radius_meters))).where(
            locations.c.user_id == user.id, locations.c.is_mock == incident.location_is_mock,
            locations.c.recorded_at >= now - timedelta(seconds=settings.max_location_age_seconds),
            locations.c.accuracy_meters <= settings.max_accuracy_meters,
            incidents.c.id == incident_id))
        if eligible is None:
            raise HTTPException(403, "Fresh matching responder location within the active radius required")
        await db.execute(responders.insert().values(incident_id=incident_id, responder_user_id=user.id,
            access_expires_at=now + timedelta(hours=2), assistance_status="ACCEPTED"))
        await get_hub(request).broadcast(incident_id, "RESPONDER_JOINED", {
            "responder_id": "resp-" + user.id.hex[:8],
            "status": "ACCEPTED",
            "joined_at": now.isoformat()
        })
    await db.execute(update(invitations).where(match).values(status=body.decision, responded_at=now))
    await service.audit(db, incident_id, user.id, body.decision)
    # Enough volunteers accepted: the other open requests close ("Help is already on the way").
    needed = request.app.state.settings.responders_needed
    if body.decision == "ACCEPTED" and needed and await db.scalar(text(
            "SELECT count(*) FROM incident_responders WHERE incident_id=:id AND revoked_at IS NULL"), {"id": incident_id}) >= needed:
        closed = await service.close_requests(db, incident_id, "FILLED", keep_user=user.id)
        if closed:
            await db.execute(text("UPDATE incident_invitations SET status='EXPIRED' WHERE id = ANY(:ids)"), {"ids": closed})
            await db.execute(text("UPDATE notification_outbox SET delivery_status='CANCELLED' WHERE invitation_id = ANY(:ids) "
                                  "AND delivery_status IN ('PENDING','RETRY_SCHEDULED','SENDING')"), {"ids": closed})
    return {"status": body.decision}





@router.post("/incidents/{incident_id}/resolve", response_model=IncidentResolutionResponse)
async def resolve_incident(incident_id: UUID, body: IncidentResolutionRequest, request: Request, user=Depends(account), db=Depends(database)):
    return await safety_pin_service.resolve_incident_with_pin(
        db=db,
        hub=get_hub(request),
        incident_id=incident_id,
        user=user,
        pin=body.pin,
        reason=body.reason
    )


@router.post("/security/safety-pin/setup")
async def setup_safety_pin(body: SafetyPinSetup, user=Depends(account), db=Depends(database)):
    await safety_pin_service.setup_pin(
        db=db,
        user_id=user.id,
        pin=body.pin,
        confirm_pin=body.confirm_pin,
        credential_type=body.credential_type
    )
    return {"status": "CONFIGURED", "credential_type": body.credential_type}


@router.post("/security/safety-pin/change")
async def change_safety_pin(body: SafetyPinChange, user=Depends(account), db=Depends(database)):
    await safety_pin_service.change_pin(
        db=db,
        user_id=user.id,
        current_pin=body.current_pin,
        new_pin=body.new_pin,
        confirm_pin=body.confirm_pin,
        credential_type=body.credential_type
    )
    return {"status": "UPDATED", "credential_type": body.credential_type}


@router.get("/security/safety-pin/status", response_model=SafetyPinStatus)
async def safety_pin_status(user=Depends(account), db=Depends(database)):
    return await safety_pin_service.get_status(db, user.id)


@router.post("/security/safety-pin/recover")
async def recover_safety_pin(body: SafetyPinRecovery, user=Depends(account), who=Depends(identity), db=Depends(database)):
    if not 0 <= datetime.now(timezone.utc).timestamp() - who.auth_time <= 300:
        raise HTTPException(403, "Sign in again to recover your Safety PIN")
    await safety_pin_service.recover_pin(
        db=db,
        user_id=user.id,
        new_pin=body.new_pin,
        confirm_pin=body.confirm_pin
    )
    return {"status": "RECOVERED"}


# --- Trusted Contacts & Emergency Alert Endpoints (Phase 13) ---

@router.get("/trusted-contacts", response_model=list[TrustedContact])
async def get_trusted_contacts(user=Depends(account), db=Depends(database)):
    return await tc_service.list_contacts(db, user.id)


@router.post("/trusted-contacts", response_model=TrustedContact, status_code=201)
async def create_trusted_contact(body: TrustedContactCreate, user=Depends(account), db=Depends(database)):
    contact = await tc_service.create_contact(db, user.id, body)
    await request_phone_matches(db, user, contact["id"], contact["normalized_phone_number"])
    return contact


@router.put("/trusted-contacts/{contact_id}", response_model=TrustedContact)
async def update_trusted_contact(contact_id: UUID, body: TrustedContactUpdate, user=Depends(account), db=Depends(database)):
    contact = await tc_service.update_contact(db, user.id, contact_id, body)
    await request_phone_matches(db, user, contact["id"], contact["normalized_phone_number"])
    return contact


@router.delete("/trusted-contacts/{contact_id}", status_code=204)
async def delete_trusted_contact(contact_id: UUID, user=Depends(account), db=Depends(database)):
    await tc_service.delete_contact(db, user.id, contact_id)
    return Response(status_code=204)


@router.post("/trusted-contacts/{contact_id}/primary", response_model=TrustedContact)
async def set_primary_trusted_contact(contact_id: UUID, user=Depends(account), db=Depends(database)):
    return await tc_service.set_primary_contact(db, user.id, contact_id)


@router.post("/incidents/{incident_id}/trusted-contacts/alert", response_model=TrustedContactAlertBatchResponse)
async def trigger_trusted_contact_alerts(incident_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    return await tc_service.dispatch_incident_alerts(db, user, incident_id, public_link(request, incident_id))


@router.post("/incidents/{incident_id}/trusted-contacts/sms-prepared", status_code=204)
async def report_sms_prepared(incident_id: UUID, body: SmsPrepared, user=Depends(account), db=Depends(database)):
    """The owner's messaging app was opened with these contacts and came back. Not proof of sending."""
    await tc_service.mark_sms_prepared(db, user.id, incident_id, body.contact_ids)


@router.get("/incidents/{incident_id}/trusted-contacts", response_model=list[IncidentContactAlertStatus])
async def get_incident_contact_statuses(incident_id: UUID, user=Depends(account), db=Depends(database)):
    return await tc_service.incident_contact_statuses(db, user.id, incident_id)


@router.post("/incidents/{incident_id}/invitations/{invitation_id}/revoke", status_code=204)
async def revoke(incident_id: UUID, invitation_id: UUID, request: Request, user=Depends(account), db=Depends(database)):
    incident = await service.incident_row(db, incident_id)
    service.owner(incident, user)
    recipient = await db.scalar(select(invitations.c.recipient_user_id).where(
        invitations.c.id == invitation_id, invitations.c.incident_id == incident_id))
    if recipient is None:
        raise HTTPException(404, "Accepted invitation unavailable")
    await db.execute(update(invitations).where(invitations.c.id == invitation_id,
        invitations.c.status == "PENDING").values(status="EXPIRED", expires_at=func.clock_timestamp()))
    await db.execute(text("UPDATE notification_outbox SET delivery_status='CANCELLED' WHERE invitation_id=:id"),
                     {"id":invitation_id})
    grant = await db.scalar(update(responders).where(responders.c.incident_id == incident_id,
        responders.c.responder_user_id == recipient, responders.c.revoked_at.is_(None))
        .values(authorization_status="REVOKED", revoked_at=func.now()).returning(responders.c.id))
    if grant:
        app_hub = get_hub(request)
        await service.audit(db, incident_id, user.id, "ACCESS_REVOKED")
        await app_hub.disconnect_user(incident_id, recipient, reason="Access revoked by victim")
        await app_hub.broadcast(incident_id, "RESPONDER_LEFT", {
            "responder_id": "resp-" + recipient.hex[:8],
            "reason": "REVOKED"
        })
    return Response(status_code=204)


@router.post("/devices/register")
async def register_device(body: DeviceRegister, user=Depends(account), db=Depends(database)):
    identifier = body.device_identifier or "token-" + hashlib.sha256(body.fcm_token.encode()).hexdigest()
    values = {"uid": user.id, "token": body.fcm_token, "platform": body.platform.upper(), "identifier": identifier}
    await db.execute(text("SELECT pg_advisory_xact_lock(hashtextextended(:token, 0))"), values)
    await db.execute(text("""UPDATE devices SET is_active=false, disabled_at=clock_timestamp()
        WHERE fcm_token=:token AND is_active AND (user_id<>:uid OR device_identifier<>:identifier)"""), values)
    await db.execute(text("""INSERT INTO devices(user_id,device_identifier,platform,fcm_token,is_active,last_seen_at)
        VALUES(:uid,:identifier,:platform,:token,true,clock_timestamp())
        ON CONFLICT (user_id,device_identifier) DO UPDATE SET fcm_token=EXCLUDED.fcm_token,
        platform=EXCLUDED.platform,is_active=true,disabled_at=NULL,last_seen_at=clock_timestamp()"""), values)
    return {"status": "REGISTERED"}


@router.delete("/devices/{token}", status_code=204)
async def unregister_device(token: str, user=Depends(account), db=Depends(database)):
    await db.execute(text("""UPDATE devices
        SET is_active = false, disabled_at = clock_timestamp()
        WHERE user_id = :uid AND fcm_token = :token AND is_active = true"""),
        {"uid": user.id, "token": token})
    return Response(status_code=204)


@router.post("/devices/{device_id}/test-alert", response_model=TestAlertResponse)
async def test_alert(device_id: str, request: Request, user=Depends(account), db=Depends(database)):
    tokens = (await db.scalars(text("""SELECT fcm_token FROM devices WHERE user_id = :uid
        AND device_identifier = :device_id AND is_active = true AND fcm_token IS NOT NULL"""),
                               {"uid": user.id, "device_id": device_id})).all()
    if not tokens:
        raise HTTPException(404, "No active device registered for this user")
    firebase_app = getattr(request.app.state, "firebase", None)
    sender = getattr(request.app.state, "fcm_sender", None) or default_firebase_sender
    payload = {
        "event_type": "TEST_ALERT",
        "notification_id": str(uuid4()),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "expires_at": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
    }
    title = "Maitri alert"
    body = "This is an authorized test notification for your registered device."
    from starlette.concurrency import run_in_threadpool
    results = await run_in_threadpool(sender, list(tokens), title, body, payload, firebase_app)
    accepted = sum(result.success for result in results)
    if not accepted:
        raise HTTPException(503, "No device message accepted by Firebase")
    return {"status": "FCM_ACCEPTED", "device_count": accepted}


async def handle_incident_ws(websocket: WebSocket, incident_id: UUID, ticket: str | None):
    settings = websocket.app.state.settings
    if settings.environment == "production":
        if websocket.url.scheme != "wss":
            await websocket.close(code=1008, reason="WSS required")
            return

    authorization = websocket.headers.get("authorization")
    if authorization:
        scheme, _, value = authorization.partition(" ")
        ticket = value if scheme.lower() == "ticket" else None
    if not ticket:
        await websocket.close(code=1008, reason="Ticket required")
        return

    app_hub = get_hub(websocket)
    ticket_data = await app_hub.ticket_store.consume_ticket(ticket, incident_id)
    if not ticket_data:
        await websocket.close(code=1008, reason="Invalid or expired ticket")
        return

    user_id = ticket_data.user_id
    role = ticket_data.role

    async with websocket.app.state.sessions() as db:
        if not await service.is_authorized_participant(db, incident_id, user_id):
            await websocket.close(code=1008, reason="Unauthorized")
            return

    await websocket.accept()
    subscriber = await app_hub.register(incident_id, user_id, role, websocket)

    try:
        async with websocket.app.state.sessions() as db:
            await service.incident_row(db, incident_id)
            if not await service.is_authorized_participant(db, incident_id, user_id):
                await websocket.close(code=1008, reason="Access revoked")
                return
            snapshot_data = await service.build_snapshot(db, incident_id, user_id, role)
            initial_event = RealtimeEvent(
                protocol_version=PROTOCOL_VERSION,
                event_type="INCIDENT_SNAPSHOT",
                incident_id=str(incident_id),
                timestamp=datetime.now(timezone.utc).isoformat(),
                sequence=app_hub._next_sequence(incident_id),
                payload=snapshot_data.model_dump(mode="json")
            )
            await websocket.send_text(initial_event.model_dump_json())

        loop = asyncio.get_running_loop()
        recheck_at = loop.time() + 30
        while True:
            if loop.time() >= recheck_at:
                async with websocket.app.state.sessions() as db:
                    if not await service.is_authorized_participant(db, incident_id, user_id):
                        await app_hub._notify_and_disconnect(subscriber, incident_id, "ACCESS_REVOKED", {"reason": "Authorization expired"})
                        return
                recheck_at = loop.time() + 30
            try:
                text_data = await asyncio.wait_for(websocket.receive_text(), timeout=recheck_at - loop.time())
            except asyncio.TimeoutError:
                continue
            try:
                data = json.loads(text_data)
                msg_type = data.get("type", "").upper()
                if msg_type == "PING":
                    await websocket.send_text(json.dumps({"type": "PONG", "timestamp": datetime.now(timezone.utc).isoformat()}))
                elif msg_type == "HEARTBEAT":
                    async with websocket.app.state.sessions() as db:
                        if not await service.is_authorized_participant(db, incident_id, user_id):
                            await app_hub._notify_and_disconnect(subscriber, incident_id, "ACCESS_REVOKED", {"reason": "Authorization revoked"})
                            break
                    await websocket.send_text(json.dumps({"type": "ACK", "timestamp": datetime.now(timezone.utc).isoformat()}))
            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        pass
    except Exception as error:
        logger.warning("WebSocket session interrupted (%s)", type(error).__name__)
    finally:
        await app_hub.unregister(incident_id, subscriber.connection_id)


@ws_router.websocket("/ws/incidents/{incident_id}")
async def ws_root(websocket: WebSocket, incident_id: UUID, ticket: str | None = None):
    await handle_incident_ws(websocket, incident_id, ticket)


@router.websocket("/ws/incidents/{incident_id}")
async def ws_api(websocket: WebSocket, incident_id: UUID, ticket: str | None = None):
    await handle_incident_ws(websocket, incident_id, ticket)
