from datetime import datetime, timezone
from typing import Literal
from uuid import UUID
from fastapi import HTTPException
import re
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Fix(Input):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_meters: float = Field(ge=0)
    recorded_at: AwareDatetime
    is_mock: bool

    def check(self, settings, now=None, *, max_accuracy=None, max_age=None):
        """Rejects with a 422 whose detail carries a reason code the app logs. Limits default to the precise
        rules (responders, trip sharing); the SOS owner passes its own, looser limits."""
        age = ((now or datetime.now(timezone.utc)) - self.recorded_at).total_seconds()
        if age < -5:
            raise location_rejected("LOCATION_FUTURE_DATED", "Location is future-dated")
        if age > (max_age or settings.max_location_age_seconds):
            raise location_rejected("LOCATION_TOO_OLD", "Location is too old")
        if self.accuracy_meters > (max_accuracy or settings.max_accuracy_meters):
            raise location_rejected("LOCATION_TOO_INACCURATE", "Location is too inaccurate")
        if self.is_mock and settings.environment == "production":
            raise location_rejected("MOCK_LOCATION_DISABLED", "Mock locations disabled")


def location_rejected(code, message):
    return HTTPException(422, {"code": code, "message": message}, headers={"X-Error-Code": code})


class ProfileEdit(Input):
    display_name: str = Field(min_length=1, max_length=256, pattern=r"\S")
    responder_opt_in: bool
    responder_available: bool


PHONE_PATTERN = re.compile(r"^\+?[0-9][0-9 ()-]{5,30}$")


class ProfileDetailsEdit(Input):
    """Partial contact-detail update. Responder flags are deliberately excluded."""
    display_name: str | None = Field(default=None, min_length=1, max_length=256)
    phone_number: str | None = Field(default=None, max_length=32)
    city: str | None = Field(default=None, max_length=128)
    # Accepted responders may call this number during an active SOS (screen 07 "Call").
    share_phone_in_sos: bool | None = None

    @field_validator("display_name")
    @classmethod
    def name_required(cls, value):
        if value is None or not value.strip():
            raise ValueError("Name is required")
        return value.strip()

    @field_validator("phone_number")
    @classmethod
    def phone(cls, value):
        value = (value or "").strip()
        if not value:
            return None
        digits = sum(c.isdigit() for c in value)
        if not PHONE_PATTERN.fullmatch(value) or not 7 <= digits <= 15:
            raise ValueError("Enter a valid phone number")
        return value

    @field_validator("city")
    @classmethod
    def place(cls, value):
        value = (value or "").strip()
        if any(ord(c) < 32 for c in value):
            raise ValueError("City contains unsupported characters")
        return value or None


class Profile(ProfileEdit):
    id: UUID
    email: str
    email_verified: bool
    phone_number: str | None = None
    city: str | None = None
    share_phone_in_sos: bool = False


class CreateIncident(Input):
    # False (a real SOS) is accepted only in the beta environment; see services.incidents.create.
    is_test: bool
    location: Fix | None = None


class Incident(BaseModel):
    id: UUID
    status: str
    is_test: bool


class ActiveIncident(BaseModel):
    id: UUID
    status: str
    started_at: datetime
    idempotency_key: UUID


class Decision(Input):
    decision: Literal["ACCEPTED", "DECLINED"]


class DeviceRegister(Input):
    fcm_token: str = Field(min_length=10, max_length=4096)
    platform: Literal["android", "ANDROID"] = "android"
    device_identifier: str = Field(default="", max_length=256)


class TestAlertResponse(BaseModel):
    status: str
    device_count: int


class WsTicketResponse(BaseModel):
    ticket: str
    expires_in: int
    expires_at: AwareDatetime
    role: Literal["OWNER", "RESPONDER"]


class ResponderStatusUpdate(Input):
    status: Literal["ACCEPTED", "ON_THE_WAY", "HELPING", "UNABLE_TO_HELP", "LEFT_INCIDENT"]


class IncidentLocationDetail(BaseModel):
    latitude: float
    longitude: float
    accuracy_meters: float
    recorded_at: AwareDatetime
    received_at: AwareDatetime
    is_mock: bool


class ResponderSummary(BaseModel):
    invited_count: int
    accepted_count: int
    active_responders_count: int


class RequesterMembership(BaseModel):
    role: Literal["OWNER", "RESPONDER"]
    authorization_status: Literal["AUTHORIZED", "REVOKED"]
    assistance_status: str | None = None


class ActiveResponderPublic(BaseModel):
    responder_id: str
    assistance_status: str
    updated_at: AwareDatetime | None = None
    # First name only. Distance and position only for the person in need, from a fix under 10 minutes old.
    display_name: str | None = None
    distance_meters: float | None = None
    latitude: float | None = None
    longitude: float | None = None


class IncidentSnapshot(BaseModel):
    incident_id: UUID
    status: str
    is_test: bool
    started_at: AwareDatetime
    latest_location: IncidentLocationDetail | None
    responder_summary: ResponderSummary
    requester_membership: RequesterMembership
    active_responders: list[ActiveResponderPublic]
    # For accepted responders only, and only when the person in need agreed to share it.
    victim_phone: str | None = None
    # For accepted responders only, while the SOS is active.
    medical_notes: str | None = None


class SafetyPinSetup(Input):
    pin: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    confirm_pin: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    credential_type: Literal["PRIMARY_PIN", "DURESS_PIN"] = "PRIMARY_PIN"


class SafetyPinChange(Input):
    current_pin: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    new_pin: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    confirm_pin: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    credential_type: Literal["PRIMARY_PIN", "DURESS_PIN"] = "PRIMARY_PIN"


class SafetyPinRecovery(Input):
    new_pin: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    confirm_pin: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")


class SafetyPinStatus(BaseModel):
    has_primary_pin: bool
    has_duress_pin: bool
    failed_attempt_count: int
    is_locked: bool
    locked_until: AwareDatetime | None = None
    retry_after_seconds: int = 0


class IncidentResolutionRequest(Input):
    pin: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    reason: str = Field(default="USER_RESOLVED", max_length=128)


class IncidentResolutionResponse(BaseModel):
    incident_id: UUID
    status: str
    resolution_status: Literal["CONFIRMED", "DURESS_SIMULATION", "REJECTED"]
    message: str
    ended_at: AwareDatetime | None = None


class TrustedContactCreate(Input):
    display_name: str = Field(min_length=1, max_length=256)
    phone_number: str = Field(min_length=7, max_length=32)
    label: str = Field(default="Emergency Contact", max_length=64)
    is_primary: bool = False
    notify_on_sos: bool = True
    include_location_link: bool = True
    notify_on_closure: bool = True
    allow_live_tracking: bool = True
    preferred_channel: Literal["SMS", "APP"] = "SMS"


class TrustedContactUpdate(Input):
    display_name: str | None = Field(default=None, min_length=1, max_length=256)
    phone_number: str | None = Field(default=None, min_length=7, max_length=32)
    label: str | None = Field(default=None, max_length=64)
    is_primary: bool | None = None
    notify_on_sos: bool | None = None
    include_location_link: bool | None = None
    notify_on_closure: bool | None = None
    allow_live_tracking: bool | None = None
    preferred_channel: Literal["SMS", "APP"] | None = None


class TrustedContact(BaseModel):
    id: UUID
    owner_user_id: UUID
    display_name: str
    phone_number: str
    normalized_phone_number: str
    label: str
    is_primary: bool
    is_verified: bool
    source: str
    notify_on_sos: bool
    include_location_link: bool
    notify_on_closure: bool
    allow_live_tracking: bool
    preferred_channel: str
    created_at: AwareDatetime
    updated_at: AwareDatetime


class IncidentContactAlertStatus(BaseModel):
    contact_id: UUID
    display_name: str
    masked_phone: str
    is_primary: bool
    delivery_status: str
    message_preview: str
    location_disclosed: bool
    tracking_granted: bool
    updated_at: AwareDatetime


class SmsContact(BaseModel):
    contact_id: UUID
    display_name: str
    phone_number: str


class TrustedContactAlertBatchResponse(BaseModel):
    incident_id: UUID
    alerts_queued: int
    primary_contact_name: str | None = None
    statuses: list[IncidentContactAlertStatus]
    # Read-only page with the live location; valid while the SOS is active.
    live_link: str | None = None
    # SOS-enabled contacts without a linked Maitri account: the app offers to text them from the phone.
    sms_contacts: list[SmsContact] = []


class SmsPrepared(Input):
    contact_ids: list[UUID] = Field(min_length=1, max_length=50)
