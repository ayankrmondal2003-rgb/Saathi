from datetime import datetime, timedelta, timezone

import httpx
import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.config import Settings
from app.main import create_app
from app.schemas import Fix, ProfileEdit


def settings():
    return Settings(_env_file=None, database_url="postgresql+asyncpg://localhost/suraksha_test",
                    firebase_project_id="test-project", environment="test")


def fix(**changes):
    return Fix(**(dict(latitude=0, longitude=0, accuracy_meters=5,
                       recorded_at=datetime.now(timezone.utc), is_mock=True) | changes))


@pytest.mark.parametrize("values", [{"latitude": 91}, {"longitude": -181}, {"accuracy_meters": -1},
                                    {"latitude": float("nan")}, {"recorded_at": "2026-01-01T10:00:00"}])
def test_invalid_fix(values):
    with pytest.raises(ValidationError):
        fix(**values)


def test_location_policy():
    now = datetime.now(timezone.utc)
    fix(recorded_at=now).check(settings(), now)
    for sample in [fix(recorded_at=now-timedelta(seconds=121)), fix(recorded_at=now+timedelta(seconds=6)),
                   fix(accuracy_meters=101)]:
        with pytest.raises(HTTPException):
            sample.check(settings(), now)
    with pytest.raises(HTTPException):
        fix().check(settings().model_copy(update={"environment":"production"}))


def test_profile_rejects_privileged_input():
    with pytest.raises(ValidationError):
        ProfileEdit(display_name="Example", responder_opt_in=True, responder_available=True, email_verified=True)


@pytest.mark.asyncio
async def test_health_and_authentication(monkeypatch):
    app = create_app(settings(), initialize_firebase=False)
    async with app.router.lifespan_context(app):
        app.state.firebase = None
        def reject(*args, **kwargs):
            assert kwargs["check_revoked"]
            raise ValueError("invalid")
        monkeypatch.setattr("app.core.auth.verify_id_token", reject)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            assert (await client.get("/health")).json() == {"status":"ok","service":"Maitri API"}
            for headers in [{}, {"Authorization":"Bearer invalid"}]:
                assert (await client.get("/api/users/me", headers=headers)).status_code == 401
            monkeypatch.setattr("app.core.auth.verify_id_token", lambda *a, **kw: {"uid":"test","email_verified":False})
            assert (await client.get("/api/users/me", headers={"Authorization":"Bearer test"})).status_code == 403
            monkeypatch.setattr("app.core.auth.verify_id_token", lambda *a, **kw: {
                "uid": "outside-demo", "email": "fixture@example.invalid", "email_verified": True})
            assert (await client.get("/api/users/me", headers={"Authorization":"Bearer test"})).status_code == 403
        del app.state.firebase


@pytest.mark.asyncio
async def test_uvicorn_loopback_health():
    import asyncio
    import socket
    import uvicorn

    server = uvicorn.Server(uvicorn.Config(create_app(settings(), initialize_firebase=False),
                                          log_level="warning", access_log=False))
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = listener.getsockname()[1]
        task = asyncio.create_task(server.serve(sockets=[listener]))
        try:
            async with asyncio.timeout(8):
                while not server.started:
                    if task.done():
                        await task
                    await asyncio.sleep(0.02)
            async with httpx.AsyncClient(trust_env=False) as client:
                response = await client.get(f"http://127.0.0.1:{port}/health")
                assert response.status_code == 200
                assert response.json()["service"] == "Maitri API"
        finally:
            server.should_exit = True
            await task
