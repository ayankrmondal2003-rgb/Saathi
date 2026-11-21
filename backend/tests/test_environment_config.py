import pytest
from sqlalchemy.engine import make_url

from app.config import ConfigurationError, Settings, load_settings


@pytest.fixture(autouse=True)
def isolated_settings(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    for name in Settings.model_fields:
        monkeypatch.delenv(name.upper(), raising=False)
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("DATABASE_URL", "postgresql://localhost/settings_test")
    monkeypatch.setenv("FIREBASE_PROJECT_ID", "isolated-test")


@pytest.mark.parametrize("scheme", ["postgres", "postgresql", "postgresql+asyncpg"])
def test_render_configuration(monkeypatch, scheme):
    monkeypatch.setenv("DATABASE_URL", f"{scheme}://tester:fixture%40only@localhost/settings_test")
    monkeypatch.setenv("TEST_ACCOUNT_UIDS", "[]")
    monkeypatch.setenv("REDIS_URL", "redis://localhost:6379/0")
    monkeypatch.setenv("ENVIRONMENT", "development")
    settings = load_settings()
    db = make_url(settings.database_url.get_secret_value())
    assert db.drivername == "postgresql+asyncpg"
    assert db.database == "settings_test"
    assert db.password == "fixture@only"
    assert settings.test_account_uids == set()


def test_uid_array_and_optional_local_redis(monkeypatch):
    monkeypatch.setenv("TEST_ACCOUNT_UIDS", '["fixture-a","fixture-b"]')
    monkeypatch.setenv("REDIS_URL", "")
    settings = load_settings()
    assert settings.test_account_uids == {"fixture-a", "fixture-b"}
    assert settings.redis_url is None


@pytest.mark.parametrize(("field", "value", "expected"), [
    ("DATABASE_URL", None, "DATABASE_URL (missing)"),
    ("DATABASE_URL", "sensitive-fixture", "DATABASE_URL (postgres_url)"),
    ("DATABASE_URL", "sqlite:///sensitive-fixture", "DATABASE_URL (postgres_url)"),
    ("DATABASE_URL", "postgres://localhost", "DATABASE_URL (postgres_url)"),
    ("TEST_ACCOUNT_UIDS", "sensitive-fixture", "TEST_ACCOUNT_UIDS (json_invalid)"),
    ("TEST_ACCOUNT_UIDS", '{"sensitive-fixture": true}', "TEST_ACCOUNT_UIDS (json_array)"),
    ("TEST_ACCOUNT_UIDS", '[{"sensitive-fixture":true}]', "TEST_ACCOUNT_UIDS[0] (string_type)"),
    ("ALLOWED_ORIGINS", "sensitive-fixture", "ALLOWED_ORIGINS (json_invalid)"),
    ("FIREBASE_PROJECT_ID", None, "FIREBASE_PROJECT_ID (missing)"),
    ("FIREBASE_PROJECT_ID", "", "FIREBASE_PROJECT_ID (string_too_short)"),
    ("REDIS_URL", "https://sensitive-fixture", "REDIS_URL (redis_url)"),
    ("ENVIRONMENT", "sensitive-fixture", "ENVIRONMENT (literal_error)"),
])
def test_safe_field_diagnostics(monkeypatch, field, value, expected):
    if value is None:
        monkeypatch.delenv(field, raising=False)
    else:
        monkeypatch.setenv(field, value)
    with pytest.raises(ConfigurationError) as captured:
        load_settings()
    assert str(captured.value) == "Invalid settings: " + expected
