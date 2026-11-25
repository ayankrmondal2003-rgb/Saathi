"""P8: server error reports never carry personal data."""
import sentry_sdk
from pydantic import SecretStr

from app.config import Settings
from app.telemetry import init_sentry, scrub, scrub_text


def settings(**kw):
    return Settings(_env_file=None, database_url="postgresql+asyncpg://x/y_test", firebase_project_id="p", **kw)


def test_scrub_removes_the_person_and_keeps_the_error():
    event = {
        "request": {"method": "POST", "url": "https://api.example/api/incidents/1/location?x=1",
                    "data": {"latitude": 28.6139}, "headers": {"Authorization": "Bearer secret"}, "cookies": {"a": "b"}},
        "user": {"id": "uid", "email": "someone@example.com"},
        "extra": {"body": "x"},
        "exception": {"values": [{"type": "ValueError",
                                  "value": "bad fix 28.613912, 77.209012 for someone@example.com +91 98765 43210 /l/abcdefghijklmnopqrstuvwxyz123",
                                  "stacktrace": {"frames": [{"function": "update_fix", "vars": {"pin": "123456"}}]}}]},
        "breadcrumbs": {"values": [{"message": "SELECT 1 near 12.971599", "data": {"params": ["secret"]}}]},
    }
    out = scrub(event)
    assert out["request"] == {"method": "POST", "url": "https://api.example/api/incidents/1/location"}
    assert "user" not in out and "extra" not in out
    value = out["exception"]["values"][0]["value"]
    assert "28.6139" not in value and "someone@" not in value and "98765" not in value and "abcdefghij" not in value
    assert value.startswith("bad fix [coord], [coord] for [email] [phone] /l/[token]")
    assert "vars" not in out["exception"]["values"][0]["stacktrace"]["frames"][0]
    crumb = out["breadcrumbs"]["values"][0]
    assert "12.97" not in crumb["message"] and "data" not in crumb


def test_ids_and_codes_survive_scrubbing():
    assert scrub_text("Incident 3f2a-77 failed with 409 after 2 tries") == "Incident 3f2a-77 failed with 409 after 2 tries"


def test_sentry_is_off_without_a_dsn_and_safe_with_one():
    assert init_sentry(settings()) is False
    try:
        assert init_sentry(settings(sentry_dsn=SecretStr("https://public@o0.ingest.sentry.io/0"), environment="test")) is True
        options = sentry_sdk.get_client().options
        assert options["before_send"] is scrub
        collection = options["data_collection"]
        assert collection["user_info"] is False and collection["stack_frame_variables"] is False
        assert collection["http_bodies"] == [] and collection["cookies"] == {"mode": "off"}
        assert options["traces_sample_rate"] == 0.0 and options["max_request_body_size"] == "never"
    finally:
        sentry_sdk.init(dsn=None)


def test_sentry_test_switch_sends_one_message_at_startup(monkeypatch):
    sent = []
    monkeypatch.setattr(sentry_sdk, "capture_message", lambda message, level=None: sent.append((message, level)))
    try:
        dsn = SecretStr("https://public@o0.ingest.sentry.io/0")
        init_sentry(settings(sentry_dsn=dsn, environment="test"))
        assert sent == []
        init_sentry(settings(sentry_dsn=dsn, environment="test", sentry_test=True))
        assert sent == [("Sentry test from Maitri server (SENTRY_TEST=1)", "warning")]
    finally:
        sentry_sdk.init(dsn=None)
