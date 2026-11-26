"""A volunteer's own SOS requests: GET /api/responders/history.

Privacy rules (tested in tests/test_responder_history.py):
- Never coordinates, and never the person's name or phone number, whether the SOS is active or over.
- Where it was is only "about N m <direction> of you", from the distance (rounded to 50 m) and compass direction frozen
  when the request was sent (migration 0019), and only to a volunteer who accepted. A volunteer who declined or
  missed it gets the same distance band they saw before answering ("Under 500 m"), nothing more.
- Kept for RESPONDER_HISTORY_DAYS (30) after the request; maintenance.py deletes the rows after that.
"""
import base64
import binascii
from datetime import datetime, timezone
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel
from sqlalchemy import text

from app.core import account, database
from app.routes import distance_band

router = APIRouter(prefix="/api/responders", tags=["responders"])

Response = Literal["PENDING", "ACCEPTED", "DECLINED", "EXPIRED", "LEFT", "CANT_HELP"]
IncidentState = Literal["ACTIVE", "RESOLVED", "CLOSED_ORPHANED", "CLOSED_OTHER"]
Filter = Literal["all", "accepted", "resolved", "declined_missed"]

# The volunteer's own audit events that make up the timeline, as recorded by routes.py.
TIMELINE_EVENTS = {
    "RESPONDER_STATUS_ON_THE_WAY": "ON_THE_WAY",
    "RESPONDER_STATUS_HELPING": "HELPING",
    "RESPONDER_STATUS_UNABLE_TO_HELP": "CANT_HELP",
    "RESPONDER_LEFT_INCIDENT": "LEFT",
}


class Area(BaseModel):
    """Approximate only: straight-line distance rounded to 50 m and an 8-point compass direction from the volunteer."""
    distance_m: int
    direction: Literal["N", "NE", "E", "SE", "S", "SW", "W", "NW"] | None


class TimelineEntry(BaseModel):
    type: Literal["INVITED", "ACCEPTED", "DECLINED", "MISSED", "ON_THE_WAY", "HELPING", "CANT_HELP", "LEFT",
                  "RESOLVED", "ENDED"]
    at: datetime


class HistoryItem(BaseModel):
    invitation_id: UUID
    incident_id: UUID
    is_test: bool
    invited_at: datetime
    accepted_at: datetime | None
    closed_at: datetime | None
    my_response: Response
    incident_state: IncidentState
    distance_band: str | None
    area: Area | None
    duration_seconds: int | None
    responders: int
    timeline: list[TimelineEntry]


class Summary(BaseModel):
    """`helped` and `resolved` count real SOSes only; accepted practice SOSes are counted apart in `practice`."""
    total: int
    helped: int
    resolved: int
    practice: int


class HistoryPage(BaseModel):
    items: list[HistoryItem]
    next_cursor: str | None
    summary: Summary
    days: int


# my_response and incident_state in SQL, so the filters and the summary use exactly what the app shows.
RESPONSE_SQL = """CASE
    WHEN v.status = 'ACCEPTED' AND v.outcome = 'LEFT' THEN 'LEFT'
    WHEN v.status = 'ACCEPTED' AND v.outcome = 'CANT_HELP' THEN 'CANT_HELP'
    WHEN v.status IN ('ACCEPTED', 'DECLINED', 'EXPIRED') THEN v.status
    WHEN v.expires_at <= now() OR i.status = 'CLOSED' THEN 'EXPIRED'
    ELSE 'PENDING' END"""
STATE_SQL = """CASE WHEN i.status <> 'CLOSED' THEN 'ACTIVE'
    WHEN i.close_reason = 'RESOLVED' THEN 'RESOLVED'
    WHEN i.close_reason = 'ORPHANED' THEN 'CLOSED_ORPHANED'
    ELSE 'CLOSED_OTHER' END"""
FILTERS = {
    "all": "TRUE",
    "accepted": "v.status = 'ACCEPTED'",
    "resolved": "v.status = 'ACCEPTED' AND i.status = 'CLOSED' AND i.close_reason = 'RESOLVED'",
    "declined_missed": f"({RESPONSE_SQL}) IN ('DECLINED', 'EXPIRED')",
}


def encode_cursor(invited_at: datetime, invitation_id: UUID) -> str:
    raw = f"{invited_at.isoformat()}|{invitation_id}".encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def decode_cursor(cursor: str) -> tuple[datetime, UUID]:
    try:
        raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode()
        at, ident = raw.split("|")
        when = datetime.fromisoformat(at)
        if when.tzinfo is None:
            raise ValueError
        return when, UUID(ident)
    except (ValueError, binascii.Error, UnicodeDecodeError):
        raise HTTPException(400, "Invalid cursor") from None


@router.get("/history", response_model=HistoryPage)
async def history(request: Request, cursor: str | None = None, limit: int = Query(20, ge=1, le=50),
                  filter: Filter = "all", incident_id: UUID | None = None,
                  user=Depends(account), db=Depends(database)):
    """This volunteer's SOS requests, newest first, from the last RESPONDER_HISTORY_DAYS days. Pass `next_cursor` back as
    `cursor` for the next page. `incident_id` returns just that request (the app uses it when an SOS ends)."""
    days = request.app.state.settings.responder_history_days
    params = {"me": user.id, "days": days, "limit": limit + 1, "incident": incident_id}
    where = [f"({FILTERS[filter]})"]
    if cursor:
        params["c_at"], params["c_id"] = decode_cursor(cursor)
        where.append("(v.invited_at, v.id) < (:c_at, :c_id)")
    if incident_id is not None:
        where.append("v.incident_id = :incident")
    base = """FROM incident_invitations v JOIN incidents i ON i.id = v.incident_id
        WHERE v.recipient_user_id = :me AND v.invited_at >= now() - make_interval(days => :days)"""
    rows = (await db.execute(text(f"""SELECT v.id, v.incident_id, v.invited_at, v.responded_at, v.expires_at, v.status,
            v.invite_distance_m, v.invite_direction, v.outcome_at, i.ended_at, i.is_test,
            {RESPONSE_SQL} AS my_response, {STATE_SQL} AS incident_state,
            (SELECT count(*) FROM incident_invitations o WHERE o.incident_id = v.incident_id
             AND o.status = 'ACCEPTED') AS responders
        {base} AND {' AND '.join(where)}
        ORDER BY v.invited_at DESC, v.id DESC LIMIT :limit"""), params)).mappings().all()
    more = len(rows) > limit
    rows = rows[:limit]
    summary = (await db.execute(text(f"""SELECT count(*) AS total,
            count(*) FILTER (WHERE v.status = 'ACCEPTED' AND NOT i.is_test) AS helped,
            count(*) FILTER (WHERE {FILTERS['resolved']} AND NOT i.is_test) AS resolved,
            count(*) FILTER (WHERE v.status = 'ACCEPTED' AND i.is_test) AS practice {base}"""), params)).mappings().one()
    timeline = await _timelines(db, user.id, [r["incident_id"] for r in rows])
    now = datetime.now(timezone.utc)
    items = [_item(row, timeline.get(row["incident_id"], []), now) for row in rows]
    return HistoryPage(items=items, next_cursor=encode_cursor(rows[-1]["invited_at"], rows[-1]["id"]) if more else None,
                       summary=Summary(**summary), days=days)


async def _timelines(db, user_id, incident_ids):
    if not incident_ids:
        return {}
    rows = (await db.execute(text("""SELECT incident_id, event_type, created_at FROM incident_events
        WHERE actor_user_id = :me AND incident_id = ANY(:ids) AND event_type = ANY(:types) ORDER BY created_at"""),
        {"me": user_id, "ids": incident_ids, "types": list(TIMELINE_EVENTS)})).all()
    result: dict = {}
    for incident, event, at in rows:
        result.setdefault(incident, []).append(TimelineEntry(type=TIMELINE_EVENTS[event], at=at))
    return result


def _item(row, status_changes, now) -> HistoryItem:
    response, state = row["my_response"], row["incident_state"]
    accepted = row["status"] == "ACCEPTED"
    accepted_at = row["responded_at"] if accepted else None
    timeline = [TimelineEntry(type="INVITED", at=row["invited_at"])]
    if row["status"] in ("ACCEPTED", "DECLINED") and row["responded_at"]:
        timeline.append(TimelineEntry(type=row["status"], at=row["responded_at"]))
    elif response == "EXPIRED":
        timeline.append(TimelineEntry(type="MISSED", at=min(row["expires_at"], row["ended_at"] or row["expires_at"])))
    if accepted:
        timeline += status_changes
    if row["ended_at"]:
        timeline.append(TimelineEntry(type="RESOLVED" if state == "RESOLVED" else "ENDED", at=row["ended_at"]))
    timeline.sort(key=lambda entry: entry.at)
    duration = None
    if accepted_at:
        ends = [t for t in (row["outcome_at"] if response in ("LEFT", "CANT_HELP") else None, row["ended_at"]) if t]
        duration = max(0, int(((min(ends) if ends else now) - accepted_at).total_seconds()))
    distance = row["invite_distance_m"]
    return HistoryItem(
        invitation_id=row["id"], incident_id=row["incident_id"], is_test=row["is_test"],
        invited_at=row["invited_at"], accepted_at=accepted_at, closed_at=row["ended_at"],
        my_response=response, incident_state=state, distance_band=distance_band(distance),
        # Only a volunteer who accepted saw where the person was; everyone else keeps just the band.
        area=Area(distance_m=distance, direction=row["invite_direction"]) if accepted and distance is not None else None,
        duration_seconds=duration, responders=row["responders"], timeline=timeline)
