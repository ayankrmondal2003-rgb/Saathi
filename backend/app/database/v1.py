"""Immutable schema snapshot used by the initial migration and Core queries."""
from geoalchemy2 import Geography
from sqlalchemy import (Boolean, CheckConstraint, Column, DateTime, Float, ForeignKey,
                        Index, Integer, MetaData, String, Table, UniqueConstraint, text)
from sqlalchemy.dialects.postgresql import JSONB, UUID

metadata = MetaData()


def pk():
    return Column("id", UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))


def reference(name, table, **kwargs):
    return Column(name, UUID(as_uuid=True), ForeignKey(f"{table}.id"), nullable=False, **kwargs)


def stamp(name, default=False, nullable=False):
    return Column(name, DateTime(timezone=True), nullable=nullable,
                  server_default=text("now()") if default else None)


def point(name, nullable=False):
    return Column(name, Geography("POINT", srid=4326, spatial_index=False), nullable=nullable)


users = Table("users", metadata, pk(), Column("firebase_uid", String(128), nullable=False, unique=True),
    Column("display_name", String(256), nullable=False), Column("email", String(320), nullable=False),
    Column("email_verified", Boolean, nullable=False),
    Column("phone_verified", Boolean, nullable=False, server_default=text("false")),
    Column("responder_opt_in", Boolean, nullable=False, server_default=text("false")),
    Column("responder_available", Boolean, nullable=False, server_default=text("false")),
    Column("account_status", String, nullable=False, server_default="ACTIVE"),
    Column("phone_number", String(32)), Column("city", String(128)),
    Column("share_phone_in_sos", Boolean, nullable=False, server_default=text("false")),
    stamp("created_at", True), stamp("updated_at", True),
    CheckConstraint("account_status IN ('ACTIVE','SUSPENDED')"),
    CheckConstraint("NOT responder_available OR responder_opt_in"))
devices = Table("devices", metadata, pk(), reference("user_id", "users"),
    Column("device_identifier", String(256), nullable=False), Column("platform", String(32), nullable=False),
    stamp("created_at", True), stamp("last_seen_at", True), UniqueConstraint("user_id", "device_identifier"))
locations = Table("responder_locations", metadata, reference("user_id", "users", primary_key=True),
    point("location"), Column("accuracy_meters", Float, nullable=False), stamp("recorded_at"),
    stamp("received_at", True), Column("is_mock", Boolean, nullable=False),
    CheckConstraint("accuracy_meters >= 0 AND accuracy_meters < 'Infinity'::float8"))
Index("ix_responder_location_gist", locations.c.location, postgresql_using="gist")
incidents = Table("incidents", metadata, pk(), reference("victim_user_id", "users"),
    Column("idempotency_key", UUID(as_uuid=True), nullable=False), Column("request_hash", String(64), nullable=False),
    Column("is_test", Boolean, nullable=False, server_default=text("true")),
    Column("status", String, nullable=False, server_default="ACTIVE"), point("latest_location", True),
    Column("location_accuracy_meters", Float), Column("location_is_mock", Boolean),
    stamp("location_recorded_at", nullable=True), stamp("location_received_at", nullable=True),
    Column("radius_meters", Integer, nullable=False, server_default="1000"),
    stamp("started_at", True), stamp("ended_at", nullable=True), UniqueConstraint("victim_user_id","idempotency_key"),
    CheckConstraint("status IN ('ACTIVE','RESOLUTION_REQUESTED','CLOSED','ESCALATION_REQUIRED')"), CheckConstraint("radius_meters = 1000"),
    CheckConstraint("location_accuracy_meters >= 0 AND location_accuracy_meters < 'Infinity'::float8"))
Index("uq_open_incident_owner", incidents.c.victim_user_id, unique=True, postgresql_where=text("status <> 'CLOSED'"))
invitations = Table("incident_invitations", metadata, pk(), reference("incident_id","incidents"),
    reference("recipient_user_id","users", index=True), Column("status", String, nullable=False, server_default="PENDING"),
    stamp("invited_at", True), stamp("expires_at"), stamp("responded_at", nullable=True),
    UniqueConstraint("incident_id","recipient_user_id"), CheckConstraint("status IN ('PENDING','ACCEPTED','DECLINED','EXPIRED')"))
responders = Table("incident_responders", metadata, pk(), reference("incident_id","incidents"),
    reference("responder_user_id","users"), Column("authorization_status", String, nullable=False, server_default="AUTHORIZED"),
    Column("assistance_status", String, nullable=False, server_default="ACCEPTED"),
    stamp("accepted_at", True), stamp("access_expires_at"), stamp("revoked_at", nullable=True),
    stamp("assistance_updated_at", True, nullable=True),
    UniqueConstraint("incident_id","responder_user_id"), CheckConstraint("authorization_status IN ('AUTHORIZED','REVOKED')"),
    CheckConstraint("assistance_status IN ('ACCEPTED','ON_THE_WAY','HELPING','UNABLE_TO_HELP','LEFT_INCIDENT')"))
events = Table("incident_events", metadata, pk(), reference("incident_id","incidents", index=True),
    Column("event_type", String(64), nullable=False), reference("actor_user_id","users"),
    stamp("created_at", True), Column("metadata", JSONB, nullable=False, server_default=text("'{}'::jsonb")))

safety_credentials = Table("safety_credentials", metadata, pk(),
    reference("user_id", "users"),
    Column("credential_type", String(32), nullable=False),
    Column("pin_hash", String(256), nullable=False),
    stamp("created_at", True), stamp("updated_at", True), stamp("last_changed_at", True),
    Column("failed_attempt_count", Integer, nullable=False, server_default="0"),
    stamp("locked_until", nullable=True),
    UniqueConstraint("user_id", "credential_type"),
    CheckConstraint("credential_type IN ('PRIMARY_PIN', 'DURESS_PIN')"),
    CheckConstraint("failed_attempt_count >= 0"))

resolution_requests = Table("incident_resolution_requests", metadata, pk(),
    reference("incident_id", "incidents"),
    reference("requester_user_id", "users"),
    Column("status", String(32), nullable=False, server_default="PENDING"),
    stamp("requested_at", True), stamp("verified_at", nullable=True),
    stamp("completed_at", nullable=True), Column("reason", String(256), nullable=True),
    CheckConstraint("status IN ('PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'DURESS_FLAGGED')"))

resolution_attempts = Table("incident_resolution_attempts", metadata, pk(),
    reference("incident_id", "incidents"),
    reference("actor_user_id", "users"),
    Column("attempt_result", String(32), nullable=False),
    stamp("created_at", True), Column("request_id", String(128), nullable=True),
    Column("metadata", JSONB, nullable=False, server_default=text("'{}'::jsonb")),
    CheckConstraint("attempt_result IN ('SUCCESS', 'INCORRECT_PIN', 'RATE_LIMITED', 'DURESS_SIMULATION', 'UNAUTHORIZED', 'USER_NOT_ENROLLED')"))

trusted_contacts = Table("trusted_contacts", metadata, pk(),
    reference("owner_user_id", "users"),
    Column("display_name", String(256), nullable=False),
    Column("phone_number", String(32), nullable=False),
    Column("normalized_phone_number", String(32), nullable=False),
    Column("label", String(64), nullable=False, server_default="Emergency Contact"),
    Column("is_primary", Boolean, nullable=False, server_default=text("false")),
    Column("is_verified", Boolean, nullable=False, server_default=text("false")),
    Column("source", String(32), nullable=False, server_default="MANUAL"),
    Column("notify_on_sos", Boolean, nullable=False, server_default=text("true")),
    Column("include_location_link", Boolean, nullable=False, server_default=text("true")),
    Column("notify_on_closure", Boolean, nullable=False, server_default=text("true")),
    Column("allow_live_tracking", Boolean, nullable=False, server_default=text("true")),
    Column("preferred_channel", String(32), nullable=False, server_default="SMS"),
    stamp("created_at", True), stamp("updated_at", True),
    UniqueConstraint("owner_user_id", "normalized_phone_number"))

trusted_contact_grants = Table("trusted_contact_grants", metadata, pk(),
    reference("incident_id", "incidents"),
    reference("contact_id", "trusted_contacts"),
    Column("recipient_user_id", UUID(as_uuid=True), ForeignKey("users.id"), nullable=True),
    Column("authorization_status", String(32), nullable=False, server_default="AUTHORIZED"),
    stamp("access_expires_at"),
    stamp("granted_at", True),
    stamp("revoked_at", nullable=True),
    UniqueConstraint("incident_id", "contact_id"),
    CheckConstraint("authorization_status IN ('AUTHORIZED', 'REVOKED')"))

notification_outbox = Table("notification_outbox", metadata, pk(),
    Column("invitation_id", UUID(as_uuid=True), ForeignKey("incident_invitations.id"), nullable=True),
    reference("incident_id", "incidents"),
    reference("recipient_user_id", "users"),
    Column("contact_id", UUID(as_uuid=True), ForeignKey("trusted_contacts.id"), nullable=True),
    Column("event_type", String(64), nullable=False),
    Column("delivery_status", String(32), nullable=False, server_default="PENDING"),
    stamp("created_at", True),
    Column("attempt_count", Integer, nullable=False, server_default="0"),
    # Created by migration 0003 (ADD COLUMN IF NOT EXISTS); declared here so services can write it.
    Column("failure_reason", String))
