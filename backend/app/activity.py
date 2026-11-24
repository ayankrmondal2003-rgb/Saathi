"""Lets the database sleep when nobody is using Maitri.

Neon Free suspends its compute after 5 idle minutes and allows 100 CU-hours a month; polling it every
few seconds around the clock would keep it awake (~180 CU-hours). Background loops therefore only touch
the database while the API has seen a request recently, plus one sweep an hour (expiry, retention,
any outbox stragglers). An SOS keeps the API busy by itself: the phone uploads a fix every few seconds
and screen 08 polls the incident, so nothing time-critical waits for the hourly sweep.
"""
import time


class DbActivity:
    def __init__(self, idle_seconds: float, sweep_seconds: float, clock=time.monotonic):
        self._clock = clock
        self.idle_seconds = idle_seconds
        self.sweep_seconds = sweep_seconds
        self._last_request = clock()  # startup counts as activity: migrations just ran
        self._started = clock()
        self._last_sweep: dict[str, float] = {}

    def touch(self) -> None:
        """Called for every API request."""
        self._last_request = self._clock()

    def busy(self) -> bool:
        return self._clock() - self._last_request < self.idle_seconds

    def should_poll(self, loop: str) -> bool:
        """True while busy, or once per sweep interval per loop when idle (which then counts as its sweep)."""
        now = self._clock()
        if now - self._last_request < self.idle_seconds:
            return True
        if now - self._last_sweep.get(loop, self._started) >= self.sweep_seconds:
            self._last_sweep[loop] = now
            return True
        return False
