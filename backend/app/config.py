from typing import Literal
from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import make_url


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", hide_input_in_errors=True)
    database_url: SecretStr
    firebase_project_id: str = Field(min_length=1)
    google_application_credentials: str | None = None
    environment: Literal["development", "test", "production"] = "development"
    allowed_origins: list[str] = []
    test_account_uids: set[str] = Field(default_factory=set)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    redis_url: SecretStr | None = None
    worker_id: str | None = None
    max_accuracy_meters: float = Field(100, gt=0, le=1000)
    max_location_age_seconds: int = Field(120, gt=0, le=600)

    radius_meters: int = Field(1000, gt=0, le=1000)
    min_search_interval_seconds: float = Field(2, ge=0, le=30)
    movement_threshold_meters: float = Field(30, gt=0, le=1000)
    reevaluation_seconds: int = Field(30, ge=1, le=600)
    invitation_ttl_seconds: int = Field(300, ge=1, le=3600)
    closed_location_hours: int = Field(24, ge=1)
    expired_invitation_days: int = Field(7, ge=1)
    expired_grant_days: int = Field(7, ge=1)
    audit_days: int = Field(30, ge=1)

    @field_validator("database_url")
    @classmethod
    def postgres_only(cls, value: SecretStr):
        url = make_url(value.get_secret_value())
        if url.drivername in {"postgres", "postgresql"}:
            url = url.set(drivername="postgresql+asyncpg")
            value = SecretStr(url.render_as_string(hide_password=False))
        if url.drivername != "postgresql+asyncpg" or not url.database:
            raise ValueError("Configure a PostgreSQL asyncpg database")
        return value
