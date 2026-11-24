import json
from typing import Annotated, Literal
from urllib.parse import urlsplit
from pydantic import Field, SecretStr, ValidationError, field_validator
from pydantic_core import PydanticCustomError
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict
from sqlalchemy.exc import ArgumentError
from sqlalchemy.engine import make_url


class ConfigurationError(RuntimeError):
    """Safe to display during startup."""


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", hide_input_in_errors=True)
    database_url: SecretStr
    firebase_project_id: str = Field(min_length=1)
    google_application_credentials: str | None = None
    environment: Literal["development", "test", "beta", "production"] = "development"
    # Firebase web app config for the /admin sign-in page (public values from the Firebase console).
    firebase_web_api_key: str | None = None
    firebase_auth_domain: str | None = None
    # Signs /l/<token> live-location links. Without it links break on every restart.
    live_link_secret: SecretStr | None = None
    # Public https origin used in live links (e.g. https://surakshanet-api.onrender.com); request host if empty.
    public_url: str = ""
    # Real SOS per owner per rolling hour (beta abuse control).
    sos_per_hour: int = Field(default=3, ge=1, le=100)
    allowed_origins: Annotated[list[str], NoDecode] = []
    test_account_uids: Annotated[set[str], NoDecode] = Field(default_factory=set)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    redis_url: SecretStr | None = None
    worker_id: str | None = None
    max_accuracy_meters: float = Field(100, gt=0, le=1000)
    max_location_age_seconds: int = Field(120, gt=0, le=600)
    # Victim SOS fixes: kept even when coarse ("Approximate" is 1-3 km); geofence matching still needs
    # max_accuracy_meters. The first SOS fix may be a last-known fix up to max_last_known_age_seconds old.
    max_sos_accuracy_meters: float = Field(5000, gt=0, le=10000)
    max_last_known_age_seconds: int = Field(600, gt=0, le=1800)

    radius_meters: int = Field(1000, gt=0, le=1000)
    min_search_interval_seconds: float = Field(2, ge=0, le=30)
    movement_threshold_meters: float = Field(30, gt=0, le=1000)
    reevaluation_seconds: int = Field(30, ge=1, le=600)
    invitation_ttl_seconds: int = Field(300, ge=1, le=3600)
    closed_location_hours: int = Field(24, ge=1)
    expired_invitation_days: int = Field(7, ge=1)
    expired_grant_days: int = Field(7, ge=1)
    audit_days: int = Field(30, ge=1)
    # ACTIVE incidents with no fix and no responder activity for this long are closed (orphaned sessions).
    orphaned_incident_hours: int = Field(12, ge=1, le=168)
    # Background loops touch the database only while the API had a request in the last db_idle_seconds,
    # plus one sweep per idle_sweep_seconds, so a free Neon compute can suspend when nobody uses Maitri.
    db_idle_seconds: int = Field(900, ge=60, le=86400)
    idle_sweep_seconds: int = Field(3600, ge=300, le=86400)

    # Private file storage: Cloudflare R2 (or any S3-compatible store) through short-lived presigned URLs.
    # Files never pass through, or are written to, this server. Unset = uploads stay disabled (503).
    r2_account_id: str | None = None
    r2_access_key_id: str | None = None
    r2_secret_access_key: SecretStr | None = None
    r2_bucket: str | None = None
    r2_endpoint: str | None = None  # override for other S3 stores; defaults to the R2 account endpoint
    upload_url_seconds: int = Field(300, ge=30, le=3600)
    download_url_seconds: int = Field(300, ge=30, le=3600)
    max_upload_bytes: int = Field(10 * 1024 * 1024, gt=0, le=100 * 1024 * 1024)

    @property
    def storage_configured(self) -> bool:
        return bool(self.r2_access_key_id and self.r2_secret_access_key and self.r2_bucket
                    and (self.r2_endpoint or self.r2_account_id))

    @field_validator("database_url")
    @classmethod
    def postgres_only(cls, value: SecretStr):
        try:
            url = make_url(value.get_secret_value())
        except (ArgumentError, ValueError):
            raise PydanticCustomError("postgres_url", "Invalid PostgreSQL URL") from None
        if url.drivername in {"postgres", "postgresql"}:
            url = url.set(drivername="postgresql+asyncpg")
            value = SecretStr(url.render_as_string(hide_password=False))
        if url.drivername != "postgresql+asyncpg" or not url.database:
            raise PydanticCustomError("postgres_url", "Invalid PostgreSQL URL")
        return value

    @property
    def open_access(self) -> bool:
        """Beta: any verified account may use the service. Every other environment keeps the allowlist."""
        return self.environment == "beta"

    @property
    def real_incidents(self) -> bool:
        return self.environment == "beta"

    def allowed_uids(self) -> list[str] | None:
        """For SQL: None means everyone (beta); otherwise the allowlist."""
        return None if self.open_access else sorted(self.test_account_uids)

    @field_validator("allowed_origins", "test_account_uids", mode="before")
    @classmethod
    def parse_json_array(cls, value):
        # Decode in the validator so malformed environment JSON retains its field location.
        if isinstance(value, str):
            try:
                items = json.loads(value)
            except ValueError:
                raise PydanticCustomError("json_invalid", "Invalid JSON") from None
            if not isinstance(items, list):
                raise PydanticCustomError("json_array", "Expected a JSON array")
            return items
        return value

    @field_validator("redis_url")
    @classmethod
    def redis_endpoint(cls, value: SecretStr | None):
        if value is None or not value.get_secret_value():
            return None
        try:
            url = urlsplit(value.get_secret_value())
            if url.scheme == "unix" and url.path:
                return value
            if url.scheme in {"redis", "rediss"} and url.hostname and url.port != 0:
                return value
        except ValueError:
            pass
        raise PydanticCustomError("redis_url", "Invalid Redis URL")


def load_settings() -> Settings:
    try:
        return Settings()
    except ValidationError as error:
        problems = []
        for item in error.errors(include_input=False, include_context=False, include_url=False):
            field, *indices = item["loc"]
            # Only schema-defined names and numeric positions belong in deployment logs.
            location = field.upper() if field in Settings.model_fields else "SETTINGS"
            location += "".join(f"[{i}]" for i in indices if isinstance(i, int))
            problems.append(f"{location} ({item['type']})")
        raise ConfigurationError("Invalid settings: " + ", ".join(problems)) from None
