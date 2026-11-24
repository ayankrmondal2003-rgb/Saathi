"""Run one bounded retention batch with python -m app.maintenance."""
import asyncio
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker
from app.database.engine import make_engine
from app.config import load_settings


async def cleanup(db, settings, batch=250):
    params = dict(batch=batch, hours=settings.closed_location_hours, invitations=settings.expired_invitation_days,
                  grants=settings.expired_grant_days, audits=settings.audit_days)
    queries = [
        """UPDATE incidents SET latest_location=NULL,evaluated_center=NULL,location_recorded_at=NULL,
           location_received_at=NULL,location_accuracy_meters=NULL,location_is_mock=NULL
           WHERE id IN (SELECT id FROM incidents WHERE status='CLOSED' AND latest_location IS NOT NULL
           AND ended_at < now()-make_interval(hours => :hours) ORDER BY ended_at LIMIT :batch FOR UPDATE SKIP LOCKED)""",
        """UPDATE incident_invitations SET status='EXPIRED' WHERE id IN (SELECT id FROM incident_invitations
           WHERE status='PENDING' AND expires_at<=clock_timestamp() ORDER BY expires_at LIMIT :batch FOR UPDATE SKIP LOCKED)""",
        """UPDATE notification_outbox SET delivery_status='CANCELLED' WHERE id IN (
           SELECT o.id FROM notification_outbox o JOIN incident_invitations v ON v.id=o.invitation_id
           WHERE o.delivery_status='PENDING' AND v.expires_at<=clock_timestamp()
           LIMIT :batch FOR UPDATE OF o SKIP LOCKED)""",
        """DELETE FROM incident_responders WHERE id IN (SELECT id FROM incident_responders
           WHERE least(access_expires_at,revoked_at)<now()-make_interval(days => :grants)
           ORDER BY access_expires_at LIMIT :batch FOR UPDATE SKIP LOCKED)""",
        """DELETE FROM trusted_contact_grants WHERE id IN (SELECT id FROM trusted_contact_grants
           WHERE LEAST(access_expires_at, COALESCE(revoked_at, access_expires_at)) < now() - make_interval(days => :grants)
           ORDER BY access_expires_at LIMIT :batch FOR UPDATE SKIP LOCKED)""",
        """DELETE FROM incident_invitations WHERE id IN (SELECT v.id FROM incident_invitations v JOIN incidents i
           ON i.id=v.incident_id WHERE i.status='CLOSED' AND v.status='EXPIRED'
           AND v.expires_at<now()-make_interval(days => :invitations)
           ORDER BY v.expires_at LIMIT :batch FOR UPDATE OF v SKIP LOCKED)""",
        """DELETE FROM incident_resolution_attempts WHERE id IN (
           SELECT a.id FROM incident_resolution_attempts a JOIN incidents i ON i.id=a.incident_id
           WHERE i.status='CLOSED' AND a.created_at<now()-make_interval(days => :audits)
           ORDER BY a.created_at LIMIT :batch FOR UPDATE OF a SKIP LOCKED)""",
        """DELETE FROM incident_resolution_requests WHERE id IN (
           SELECT r.id FROM incident_resolution_requests r JOIN incidents i ON i.id=r.incident_id
           WHERE i.status='CLOSED' AND r.requested_at<now()-make_interval(days => :audits)
           ORDER BY r.requested_at LIMIT :batch FOR UPDATE OF r SKIP LOCKED)""",
        """DELETE FROM incident_events WHERE id IN (SELECT id FROM incident_events WHERE
           created_at<now()-make_interval(days => :audits) ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED)"""]
    for query in queries:
        await db.execute(text(query), params)


async def main():
    settings = load_settings()
    engine = make_engine(settings.database_url.get_secret_value())
    try:
        async with async_sessionmaker(engine).begin() as db:
            await cleanup(db, settings)
    finally:
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
