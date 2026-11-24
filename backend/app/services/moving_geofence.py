"""Dynamic moving geofence engine using PostGIS geography ST_DWithin in metres.

All callers lock the incident row before evaluating or creating invitations.
"""
from geoalchemy2 import Geography
from sqlalchemy import cast, func, text


def point(latitude, longitude):
    return cast(func.ST_SetSRID(func.ST_MakePoint(longitude, latitude), 4326), Geography("POINT", 4326))


def parameters(settings):
    return dict(allowed=settings.allowed_uids(), age=settings.max_location_age_seconds,
                responder_age=settings.responder_location_max_age_seconds, accuracy=settings.max_accuracy_meters,
                interval=settings.reevaluation_seconds, floor=settings.min_search_interval_seconds,
                movement=settings.movement_threshold_meters, ttl=settings.invitation_ttl_seconds,
                production=settings.environment == "production")


FRESH = """i.status='ACTIVE' AND i.location_accuracy_meters <= :accuracy
    AND i.location_recorded_at >= clock_timestamp() - make_interval(secs => :age)
    AND (NOT :production OR NOT i.location_is_mock)"""
DUE = "i.evaluated_at IS NULL OR i.evaluated_at <= clock_timestamp() - make_interval(secs => :interval)"


async def invite(db, params, *, recipient=None):
    """Find eligible responders within dynamic radius and atomically create invitations and outbox records."""
    restrict = "AND u.id=:recipient" if recipient is not None else ""
    sql = f"""
        WITH added AS (
            INSERT INTO incident_invitations(incident_id,recipient_user_id,expires_at)
            SELECT i.id,u.id,clock_timestamp()+make_interval(secs => :ttl)
            FROM incidents i JOIN responder_locations l ON ST_DWithin(l.location,i.latest_location,i.radius_meters)
            JOIN users u ON u.id=l.user_id
            WHERE i.id=:id AND {FRESH} AND u.id<>i.victim_user_id AND u.account_status='ACTIVE'
              AND (CAST(:allowed AS text[]) IS NULL OR u.firebase_uid = ANY(CAST(:allowed AS text[])))
              AND EXISTS (SELECT 1 FROM users owner WHERE owner.id=i.victim_user_id AND (CAST(:allowed AS text[]) IS NULL OR owner.firebase_uid = ANY(CAST(:allowed AS text[]))))
              AND (u.email_verified OR u.phone_verified) AND u.responder_opt_in AND u.responder_available
              AND EXISTS (SELECT 1 FROM volunteer_applications va WHERE va.user_id=u.id AND va.status='APPROVED')
              AND l.recorded_at >= clock_timestamp()-make_interval(secs => :responder_age)
              AND l.accuracy_meters <= :accuracy AND l.is_mock=i.location_is_mock {restrict}
              AND NOT EXISTS (SELECT 1 FROM incident_invitations old
                              WHERE old.incident_id=i.id AND old.recipient_user_id=u.id)
            ON CONFLICT (incident_id,recipient_user_id) DO NOTHING RETURNING id,incident_id,recipient_user_id
        ), queued AS (
            INSERT INTO notification_outbox(invitation_id,incident_id,recipient_user_id)
            SELECT id,incident_id,recipient_user_id FROM added RETURNING invitation_id
        )
        SELECT invitation_id FROM queued"""
    ids = (await db.scalars(text(sql), dict(params, recipient=recipient))).all()
    if ids:
        await db.execute(text("""INSERT INTO incident_events(incident_id,actor_user_id,event_type,metadata)
            SELECT id,victim_user_id,'INVITATIONS_CREATED',jsonb_build_object('count',CAST(:count AS integer))
            FROM incidents WHERE id=:id"""), dict(params, count=len(ids)))
    return ids


async def evaluate(db, incident_id, settings):
    """Reevaluate geofence when victim moves significantly or periodic interval is due."""
    params = dict(parameters(settings), id=incident_id)
    due = await db.scalar(text(f"""UPDATE incidents i SET evaluated_center=latest_location,evaluated_at=clock_timestamp()
        WHERE id=:id AND {FRESH} AND ({DUE} OR (evaluated_at <= clock_timestamp()-make_interval(secs => :floor)
        AND NOT ST_DWithin(evaluated_center,latest_location,greatest(:movement,location_accuracy_meters))))
        RETURNING id"""), params)
    if due:
        await invite(db, params)


async def responder_changed(db, user_id, settings, *, force=False):
    """Evaluate nearby active incidents when an opted-in responder updates location or availability."""
    params = dict(parameters(settings), uid=user_id, radius=settings.radius_meters)
    due = await db.scalar(text("""UPDATE responder_locations SET searched_center=location,searched_at=clock_timestamp()
        WHERE user_id=:uid AND (:force OR searched_at IS NULL OR
        searched_at<=clock_timestamp()-make_interval(secs => :interval) OR (searched_at<=clock_timestamp()-make_interval(secs => :floor)
        AND NOT ST_DWithin(searched_center,location,greatest(:movement,accuracy_meters))))
        RETURNING user_id"""), dict(params, force=force))
    if not due:
        return
    ids = (await db.scalars(text(f"""SELECT i.id FROM responder_locations l JOIN incidents i
        ON ST_DWithin(i.latest_location,l.location,i.radius_meters) WHERE l.user_id=:uid AND {FRESH}
        AND i.location_is_mock=l.is_mock AND i.victim_user_id<>:uid
        AND NOT EXISTS(SELECT 1 FROM incident_invitations v WHERE v.incident_id=i.id AND v.recipient_user_id=:uid)
        ORDER BY i.id LIMIT 100 FOR NO KEY UPDATE OF i SKIP LOCKED"""), params)).all()
    for incident_id in ids:
        await invite(db, dict(params, id=incident_id), recipient=user_id)


async def tick(db, settings):
    """Periodic worker pass to process stationary or deferred incidents."""
    params = parameters(settings)
    ids = (await db.scalars(text(f"""SELECT i.id FROM incidents i WHERE {FRESH} AND ({DUE})
        ORDER BY evaluated_at NULLS FIRST,id LIMIT 100 FOR NO KEY UPDATE SKIP LOCKED"""), params)).all()
    for incident_id in ids:
        await evaluate(db, incident_id, settings)
