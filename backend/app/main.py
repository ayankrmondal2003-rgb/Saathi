from contextlib import asynccontextmanager
import asyncio
from contextlib import suppress
from app.services.geofence import tick
from app.services.incidents import expire_orphaned
from app.services.outbox_worker import process_outbox_batch
import logging
import os
import firebase_admin
from firebase_admin import credentials
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import async_sessionmaker
from app.config import load_settings
from app.database.engine import make_engine
from app.activity import DbActivity
from app.routes import router, ws_router
from app.circle_sharing import router as circle_router, cleanup as cleanup_circle
from app.volunteer import router as volunteer_router
from app.files import router as files_router
from app.inbox import router as inbox_router, send_pending_pushes, cleanup as cleanup_inbox
from app.circle import router as circle_members_router
from app.live_link import router as live_router
from app.checkins import CheckInScheduler
from app.admin import router as admin_router
from app.privacy import router as privacy_router, purge_sos_media
from app.maintenance import cleanup as retention_cleanup
from app.services.outbox_worker import default_firebase_sender
from app.services.realtime import hub


def create_app(settings=None, *, initialize_firebase=True):
    settings = settings or load_settings()
    from app.telemetry import init_sentry
    init_sentry(settings)
    if os.getenv("FIREBASE_AUTH_EMULATOR_HOST") and settings.environment != "test":
        raise RuntimeError("Auth emulator requires an isolated test deployment")
    if not initialize_firebase and settings.environment != "test":
        raise RuntimeError("Firebase verification required")

    @asynccontextmanager
    async def lifespan(app):
        logging.getLogger("surakshanet").setLevel(settings.log_level)
        engine = None
        if not hasattr(app.state, "sessions") or app.state.sessions is None:
            engine = make_engine(settings.database_url.get_secret_value(), pool_pre_ping=True)
            app.state.sessions = async_sessionmaker(engine, expire_on_commit=False)

        async def periodic_geofence():
            while True:
                await asyncio.sleep(settings.reevaluation_seconds)
                if not app.state.activity.should_poll("geofence"):
                    continue
                try:
                    async with app.state.sessions.begin() as db:
                        await tick(db, settings)
                        await cleanup_circle(db)
                        await cleanup_inbox(db)
                        # Retention: SOS locations 7 days after closing, expired grants and old audit rows.
                        await retention_cleanup(db, settings)
                except (SQLAlchemyError, OSError):
                    logging.getLogger("surakshanet").warning("Geofence batch unavailable")
                try:
                    await purge_sos_media(app.state.sessions, settings)
                except (SQLAlchemyError, OSError):
                    logging.getLogger("surakshanet").warning("SOS media retention unavailable")
                try:
                    async with app.state.sessions.begin() as db:
                        expired = await expire_orphaned(db, settings)
                    for incident_id in expired:
                        logging.getLogger("surakshanet").info("Auto-expired orphaned incident %s", incident_id)
                        await getattr(app.state, "hub", hub).close_incident(incident_id, reason="Incident expired after inactivity")
                except (SQLAlchemyError, OSError):
                    logging.getLogger("surakshanet").warning("Orphaned incident expiry unavailable")

        async def periodic_outbox():
            while True:
                await asyncio.sleep(2)
                if not app.state.activity.should_poll("outbox"):
                    continue
                try:
                    firebase_app = getattr(app.state, "firebase", None)
                    fcm_sender = getattr(app.state, "fcm_sender", None)
                    await process_outbox_batch(app.state.sessions, firebase_app, fcm_sender=fcm_sender, allowed_uids=settings.allowed_uids())
                    await send_pending_pushes(app.state.sessions, firebase_app, fcm_sender or default_firebase_sender)
                except (SQLAlchemyError, OSError):
                    logging.getLogger("surakshanet").warning("Outbox worker batch unavailable")

        geofence_worker = None
        outbox_worker = None
        checkin_worker = None
        try:
            if initialize_firebase:
                print("[startup] Loading Firebase Admin credentials", flush=True)
                try:
                    credential = (credentials.Certificate(settings.google_application_credentials)
                                  if settings.google_application_credentials else credentials.ApplicationDefault())
                    credential.get_credential()
                except Exception as error:
                    raise RuntimeError(f"Firebase credential loading failed ({type(error).__name__})") from None
                if settings.google_application_credentials and credential.project_id != settings.firebase_project_id:
                    raise RuntimeError("Firebase credential project mismatch")
                app.state.firebase = firebase_admin.initialize_app(credential, {"projectId": settings.firebase_project_id},
                                                                  name="surakshanet")
            redis_url = settings.redis_url.get_secret_value() if settings.redis_url else os.getenv("REDIS_URL")
            app_hub = getattr(app.state, "hub", hub)
            print("[startup] Firebase initialized; starting realtime services" if initialize_firebase else "[startup] Starting test realtime services", flush=True)
            await app_hub.start_distributed(redis_url, sessions=app.state.sessions, worker_id=settings.worker_id)
            geofence_worker = asyncio.create_task(periodic_geofence())
            outbox_worker = asyncio.create_task(periodic_outbox())
            app.state.checkins = CheckInScheduler(app.state.sessions, on_items=app.state.activity.touch)
            checkin_worker = asyncio.create_task(app.state.checkins.run())
            yield
        finally:
            app_hub = getattr(app.state, "hub", hub)
            await app_hub.stop_distributed()
            for w in (geofence_worker, outbox_worker, checkin_worker):
                if w:
                    w.cancel()
                    with suppress(asyncio.CancelledError):
                        await w
            if hasattr(app.state, "firebase"):
                firebase_admin.delete_app(app.state.firebase)
            if engine is not None:
                await engine.dispose()

    app = FastAPI(title="Maitri API", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings
    app.state.activity = DbActivity(settings.db_idle_seconds, settings.idle_sweep_seconds)
    if settings.allowed_origins:
        app.add_middleware(CORSMiddleware, allow_origins=settings.allowed_origins,
                           allow_methods=["GET","PUT","POST","DELETE"], allow_headers=["Authorization","Content-Type","Idempotency-Key"])
    @app.middleware("http")
    async def private_responses(request, call_next):
        if request.url.path.startswith("/api/"):
            app.state.activity.touch()  # keep background database work running while the app is in use
        response = await call_next(request)
        route = getattr(request.scope.get("route"), "path", "")
        if response.status_code >= 400 and route in {
            "/api/incidents", "/api/incidents/{incident_id}/resolve", "/api/incidents/{incident_id}/location",
            "/api/responders/location", "/api/devices/{device_id}/test-alert",
        }:
            logging.getLogger("surakshanet").warning("Request failed %s %s %d",
                                                       request.method, route, response.status_code)
        if request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    app.include_router(router)
    app.include_router(circle_router)
    app.include_router(volunteer_router)
    app.include_router(files_router)
    app.include_router(inbox_router)
    app.include_router(circle_members_router)
    app.include_router(live_router)
    app.include_router(admin_router)
    app.include_router(privacy_router)
    app.include_router(ws_router)

    @app.get("/health")
    async def health():
        return {"status": "ok", "service": "Maitri API"}

    @app.get("/ready")
    async def ready():
        """Cheap: answers from this process only and never opens a database connection, so Render's health
        check and the cron-job.org keep-alive keep the API warm without waking a suspended Neon compute.
        Database problems surface on the first API call and on /health/db."""
        if settings.redis_url and not await getattr(app.state, "hub", hub).redis_available():
            raise HTTPException(503, "Realtime service unavailable")
        return {"status": "ready", "database_busy": app.state.activity.busy()}

    @app.get("/health/db")
    async def database_health():
        """Deep check for manual diagnosis. It wakes the database; never point a monitor or keep-alive at it."""
        try:
            async with app.state.sessions() as session:
                await session.execute(text("SELECT PostGIS_Version(), id FROM incidents LIMIT 0"))
        except (SQLAlchemyError, OSError):
            raise HTTPException(503, "Database unavailable") from None
        return {"status": "ok"}

    return app
