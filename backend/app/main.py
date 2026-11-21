from contextlib import asynccontextmanager
import asyncio
from contextlib import suppress
from app.services.geofence import tick
from app.services.outbox_worker import process_outbox_batch
import logging
import os
import firebase_admin
from firebase_admin import credentials
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from app.config import Settings
from app.routes import router, ws_router
from app.services.realtime import hub


def create_app(settings=None, *, initialize_firebase=True):
    settings = settings or Settings()
    if os.getenv("FIREBASE_AUTH_EMULATOR_HOST") and settings.environment != "test":
        raise RuntimeError("Auth emulator requires an isolated test deployment")
    if not initialize_firebase and settings.environment != "test":
        raise RuntimeError("Firebase verification required")

    @asynccontextmanager
    async def lifespan(app):
        logging.getLogger("surakshanet").setLevel(settings.log_level)
        engine = None
        if not hasattr(app.state, "sessions") or app.state.sessions is None:
            engine = create_async_engine(settings.database_url.get_secret_value(), hide_parameters=True, pool_pre_ping=True)
            app.state.sessions = async_sessionmaker(engine, expire_on_commit=False)

        async def periodic_geofence():
            while True:
                await asyncio.sleep(settings.reevaluation_seconds)
                try:
                    async with app.state.sessions.begin() as db:
                        await tick(db, settings)
                except (SQLAlchemyError, OSError):
                    logging.getLogger("surakshanet").warning("Geofence batch unavailable")

        async def periodic_outbox():
            while True:
                await asyncio.sleep(2)
                try:
                    firebase_app = getattr(app.state, "firebase", None)
                    fcm_sender = getattr(app.state, "fcm_sender", None)
                    await process_outbox_batch(app.state.sessions, firebase_app, fcm_sender=fcm_sender, allowed_uids=settings.test_account_uids)
                except (SQLAlchemyError, OSError):
                    logging.getLogger("surakshanet").warning("Outbox worker batch unavailable")

        geofence_worker = None
        outbox_worker = None
        try:
            if initialize_firebase:
                credential = (credentials.Certificate(settings.google_application_credentials)
                              if settings.google_application_credentials else credentials.ApplicationDefault())
                if settings.google_application_credentials and credential.project_id != settings.firebase_project_id:
                    raise RuntimeError("Firebase credential project mismatch")
                credential.get_credential()
                app.state.firebase = firebase_admin.initialize_app(credential, {"projectId": settings.firebase_project_id},
                                                                  name="surakshanet")
            redis_url = settings.redis_url.get_secret_value() if settings.redis_url else os.getenv("REDIS_URL")
            app_hub = getattr(app.state, "hub", hub)
            await app_hub.start_distributed(redis_url, sessions=app.state.sessions, worker_id=settings.worker_id)
            geofence_worker = asyncio.create_task(periodic_geofence())
            outbox_worker = asyncio.create_task(periodic_outbox())
            yield
        finally:
            app_hub = getattr(app.state, "hub", hub)
            await app_hub.stop_distributed()
            for w in (geofence_worker, outbox_worker):
                if w:
                    w.cancel()
                    with suppress(asyncio.CancelledError):
                        await w
            if hasattr(app.state, "firebase"):
                firebase_admin.delete_app(app.state.firebase)
            if engine is not None:
                await engine.dispose()

    app = FastAPI(title="SurakshaNet Test API", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings
    if settings.allowed_origins:
        app.add_middleware(CORSMiddleware, allow_origins=settings.allowed_origins,
                           allow_methods=["GET","PUT","POST","DELETE"], allow_headers=["Authorization","Content-Type","Idempotency-Key"])
    @app.middleware("http")
    async def private_responses(request, call_next):
        response = await call_next(request)
        route = getattr(request.scope.get("route"), "path", "")
        if response.status_code >= 400 and route in {
            "/api/incidents/{incident_id}/resolve", "/api/incidents/{incident_id}/location",
            "/api/responders/location", "/api/devices/{device_id}/test-alert",
        }:
            logging.getLogger("surakshanet").warning("Request failed %s %s %d",
                                                       request.method, route, response.status_code)
        if request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    app.include_router(router)
    app.include_router(ws_router)

    @app.get("/health")
    async def health():
        return {"status": "ok", "service": "SurakshaNet API"}

    @app.get("/ready")
    async def ready():
        try:
            async with app.state.sessions() as session:
                await session.execute(text("SELECT PostGIS_Version(), id FROM incidents LIMIT 0"))
        except (SQLAlchemyError, OSError):
            raise HTTPException(503, "Database unavailable") from None
        if settings.redis_url and not await getattr(app.state, "hub", hub).redis_available():
            raise HTTPException(503, "Realtime service unavailable")
        return {"status": "ready"}

    return app
