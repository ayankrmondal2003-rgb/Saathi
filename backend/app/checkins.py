"""Check-in reminders and missed check-ins, without polling the database.

The scheduler asks the database for the next due time once at startup (which catches up on anything missed
while the server was asleep or restarting), then sleeps until exactly that time. Creating or stopping a
check-in wakes it to re-read the next due time. With no open check-ins it waits forever, so an idle
server never wakes Neon for this.
"""
import asyncio
import logging
from datetime import datetime, timezone

from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.inbox import add_item

log = logging.getLogger("surakshanet.checkins")
REMINDER_MINUTES = 5
OPEN = "kind='CHECK_IN' AND stopped_at IS NULL AND missed_at IS NULL AND due_at IS NOT NULL"


def name(value):
    return (value or "").strip() or "Someone in your circle"


async def process_due(db) -> int:
    """Sends due reminders and missed-check-in alerts. Each session is handled once (guarded UPDATEs)."""
    reminders = (await db.execute(text(f"""UPDATE circle_sessions SET reminded_at=now()
        WHERE {OPEN} AND reminded_at IS NULL AND due_at - make_interval(mins => :m) <= now() AND due_at > now()
        RETURNING id, owner_id, due_at"""), {"m": REMINDER_MINUTES})).mappings().all()
    for r in reminders:
        await add_item(db, r["owner_id"], "CHECKIN_REMINDER", "Time to check in",
                       "Tap to tell your circle you're safe.",
                       data={"session_id": str(r["id"]), "due_at": r["due_at"].isoformat()})
    missed = (await db.execute(text(f"""UPDATE circle_sessions s SET missed_at=now()
        FROM users u WHERE s.{OPEN.replace(' AND ', ' AND s.')} AND s.due_at <= now() AND u.id = s.owner_id
        RETURNING s.id, s.owner_id, s.due_at, u.display_name"""))).mappings().all()
    for m in missed:
        recipients = (await db.execute(text("""SELECT DISTINCT l.recipient_id FROM circle_session_recipients r
            JOIN circle_links l ON l.id = r.link_id WHERE r.session_id=:s AND l.revoked_at IS NULL"""),
            {"s": m["id"]})).scalars().all()
        for recipient in recipients:
            await add_item(db, recipient, "CHECKIN_MISSED", f"{name(m['display_name'])} missed a check-in",
                           "They haven't checked in on time. Try reaching them, and call 112 if you think they're in danger.",
                           data={"session_id": str(m["id"]), "due_at": m["due_at"].isoformat()})
        await add_item(db, m["owner_id"], "CHECKIN_MISSED", "You missed your check-in",
                       f"Your circle has been told ({len(recipients)} {'person' if len(recipients) == 1 else 'people'}). "
                       "Check in now to let them know you're safe.",
                       data={"session_id": str(m["id"]), "due_at": m["due_at"].isoformat()})
    return len(reminders) + len(missed)


async def next_due(db) -> datetime | None:
    return await db.scalar(text(f"""SELECT min(CASE WHEN reminded_at IS NULL AND due_at > now()
        THEN due_at - make_interval(mins => :m) ELSE due_at END) FROM circle_sessions WHERE {OPEN}"""),
        {"m": REMINDER_MINUTES})


class CheckInScheduler:
    def __init__(self, sessions, on_items=lambda: None):
        self.sessions = sessions
        self.on_items = on_items
        self._wake = asyncio.Event()

    def wake(self):
        self._wake.set()

    async def run_once(self) -> datetime | None:
        async with self.sessions.begin() as db:
            if await process_due(db):
                self.on_items()  # lets the push loop run even if the API is otherwise idle
            return await next_due(db)

    async def run(self):
        while True:
            self._wake.clear()
            try:
                due = await self.run_once()
            except (SQLAlchemyError, OSError):
                log.warning("Check-in scheduler unavailable; retrying in a minute")
                due = None
                await self._sleep(60)
                continue
            delay = None if due is None else max(0.0, (due - datetime.now(timezone.utc)).total_seconds()) + 0.5
            await self._sleep(delay)

    async def _sleep(self, seconds):
        try:
            await asyncio.wait_for(self._wake.wait(), timeout=seconds)
        except asyncio.TimeoutError:
            pass
