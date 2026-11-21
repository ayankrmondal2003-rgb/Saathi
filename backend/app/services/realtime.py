"""Secure real-time WebSocket connection management, ticket authentication, and distributed event broadcasting.

Architecture note:
This implementation provides a distributed, thread-safe asynchronous RealtimeHub
supporting both single-instance in-memory operation and multi-worker deployments backed
by a Redis Pub/Sub event bus.
In a multi-worker production or containerized deployment, events (location fixes, responder joins,
revocations, closures) are distributed across worker nodes using Redis Pub/Sub.
PostgreSQL remains the authoritative incident database and access-control ground truth.
All workers verify database authorization before delivering sensitive GPS coordinates to subscribers.
"""
import asyncio
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
import logging
import secrets
from typing import Any, Callable, Coroutine
from uuid import UUID
from fastapi import WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ConfigDict, Field

logger = logging.getLogger("surakshanet.realtime")

PROTOCOL_VERSION = "1.0"
REDIS_CHANNEL = "surakshanet:incident_events"


class WsTicketData(BaseModel):
    model_config = ConfigDict(extra="forbid")
    ticket: str
    user_id: UUID
    incident_id: UUID
    role: str  # "OWNER" or "RESPONDER"
    created_at: datetime
    expires_at: datetime


class RealtimeEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol_version: str = PROTOCOL_VERSION
    event_type: str
    incident_id: str
    timestamp: str
    sequence: int
    payload: dict[str, Any] = Field(default_factory=dict)


class DistributedMessage(BaseModel):
    model_config = ConfigDict(extra="ignore")
    origin_worker_id: str
    incident_id: str
    event_type: str
    payload: dict[str, Any] = Field(default_factory=dict)
    timestamp: str
    sequence: int
    user_id: str | None = None
    reason: str | None = None


@dataclass
class ActiveSubscriber:
    connection_id: str
    user_id: UUID
    role: str
    websocket: WebSocket
    connected_at: datetime


class TicketStore:
    """Manages short-lived, single-use WebSocket authentication tickets.

    Supports Redis key-value storage with automatic TTL for multi-worker deployments,
    and falls back to thread-safe in-memory storage for single-instance or isolated testing.
    """

    def __init__(self, ttl_seconds: int = 60):
        self.ttl_seconds = ttl_seconds
        self._tickets: dict[str, WsTicketData] = {}
        self._lock = asyncio.Lock()
        self._redis = None

    def set_redis(self, redis_client):
        self._redis = redis_client

    def clear_redis(self):
        self._redis = None

    async def issue_ticket(self, user_id: UUID, incident_id: UUID, role: str) -> WsTicketData:
        token = secrets.token_urlsafe(32)
        now = datetime.now(timezone.utc)
        data = WsTicketData(
            ticket=token,
            user_id=user_id,
            incident_id=incident_id,
            role=role,
            created_at=now,
            expires_at=now + timedelta(seconds=self.ttl_seconds)
        )

        if self._redis is not None:
            key = f"surakshanet:ticket:{token}"
            await self._redis.setex(key, self.ttl_seconds, data.model_dump_json())
            return data

        async with self._lock:
            self._purge_expired()
            self._tickets[token] = data
            return data

    async def consume_ticket(self, token: str, incident_id: UUID) -> WsTicketData | None:
        if self._redis is not None:
            try:
                key = f"surakshanet:ticket:{token}"
                raw = await self._redis.getdel(key)
                if raw:
                    data = WsTicketData.model_validate_json(raw)
                    if data.incident_id != incident_id or data.expires_at <= datetime.now(timezone.utc):
                        return None
                    return data
            except Exception:
                logger.warning("Redis ticket consumption unavailable")
            return None

        async with self._lock:
            self._purge_expired()
            data = self._tickets.pop(token, None)
            if data is None:
                return None
            if data.incident_id != incident_id:
                return None
            if data.expires_at <= datetime.now(timezone.utc):
                return None
            return data

    def _purge_expired(self):
        now = datetime.now(timezone.utc)
        expired = [k for k, v in self._tickets.items() if v.expires_at <= now]
        for k in expired:
            self._tickets.pop(k, None)


class RealtimeHub:
    """Distributed real-time event hub managing WebSocket connections and cross-worker broadcast."""

    def __init__(self, worker_id: str | None = None):
        self.worker_id = worker_id or secrets.token_hex(6)
        self._lock = asyncio.Lock()
        self._incidents: dict[UUID, dict[str, ActiveSubscriber]] = {}
        self._sequences: dict[UUID, int] = {}
        self.ticket_store = TicketStore(ttl_seconds=60)

        # Distributed Redis Pub/Sub state
        self._redis = None
        self._pubsub = None
        self._listener_task: asyncio.Task | None = None
        self._stopping = False
        self._sessions = None  # Async sessionmaker for DB-backed authorization
        self._channel = REDIS_CHANNEL

        # Optional custom in-memory bus for multi-worker testing
        self._custom_bus = None
        self._worker_loop: asyncio.AbstractEventLoop | None = None

    def _next_sequence(self, incident_id: UUID) -> int:
        seq = self._sequences.get(incident_id, 0) + 1
        self._sequences[incident_id] = seq
        return seq

    async def start_distributed(self, redis_url: str | None, sessions=None, worker_id: str | None = None):
        """Initializes distributed Redis connection and starts background subscriber loop."""
        if worker_id:
            self.worker_id = worker_id
        if sessions is not None:
            self._sessions = sessions

        if not redis_url:
            return

        try:
            import redis.asyncio as aioredis
            self._redis = aioredis.from_url(redis_url, decode_responses=True, socket_connect_timeout=3, socket_timeout=5)
            self.ticket_store.set_redis(self._redis)
            self._stopping = False
            self._listener_task = asyncio.create_task(self._listen_redis_loop())
            logger.info(f"RealtimeHub [{self.worker_id}] started Redis listener")
        except Exception as e:
            logger.warning(f"Failed to initialize Redis bus: operating in standalone mode.")

    async def redis_available(self) -> bool:
        if self._redis is None or self._pubsub is None or self._listener_task is None or self._listener_task.done():
            return False
        from redis.exceptions import RedisError
        try:
            return bool(await asyncio.wait_for(self._redis.ping(), timeout=2))
        except (RedisError, TimeoutError, OSError):
            return False

    async def stop_distributed(self):
        """Shuts down Redis listener task and closes connections cleanly."""
        self._stopping = True
        if self._listener_task is not None:
            self._listener_task.cancel()
            with suppress(asyncio.CancelledError):
                await self._listener_task
            self._listener_task = None

        if self._pubsub is not None:
            try:
                await self._pubsub.close()
            except Exception:
                pass
            self._pubsub = None

        if self._redis is not None:
            try:
                await self._redis.aclose()
            except Exception:
                pass
            self._redis = None

        self.ticket_store.clear_redis()
        if self._custom_bus is not None:
            self._custom_bus.unsubscribe(self.worker_id)
            self._custom_bus = None

    def attach_bus(self, bus, sessions=None):
        """Attaches a test in-memory bus for deterministic multi-worker tests without Redis server."""
        self._custom_bus = bus
        if sessions is not None:
            self._sessions = sessions
        bus.subscribe(self.worker_id, self._handle_remote_message)

    async def register(self, incident_id: UUID, user_id: UUID, role: str, websocket: WebSocket) -> ActiveSubscriber:
        try:
            self._worker_loop = asyncio.get_running_loop()
        except RuntimeError:
            pass
        connection_id = secrets.token_hex(8)
        subscriber = ActiveSubscriber(
            connection_id=connection_id,
            user_id=user_id,
            role=role,
            websocket=websocket,
            connected_at=datetime.now(timezone.utc)
        )
        async with self._lock:
            if incident_id not in self._incidents:
                self._incidents[incident_id] = {}
            self._incidents[incident_id][connection_id] = subscriber
        return subscriber

    async def unregister(self, incident_id: UUID, connection_id: str):
        async with self._lock:
            if incident_id in self._incidents:
                self._incidents[incident_id].pop(connection_id, None)
                if not self._incidents[incident_id]:
                    self._incidents.pop(incident_id, None)

    async def broadcast(
        self,
        incident_id: UUID,
        event_type: str,
        payload: dict[str, Any],
        authorizer: Callable[[UUID, UUID], Coroutine[Any, Any, bool]] | None = None
    ):
        """Broadcast an event locally and publish to cross-worker bus."""
        subscribers: list[ActiveSubscriber] = []
        async with self._lock:
            subs_map = self._incidents.get(incident_id, {})
            subscribers = list(subs_map.values())
            seq = self._next_sequence(incident_id)

        now_iso = datetime.now(timezone.utc).isoformat()
        event = RealtimeEvent(
            protocol_version=PROTOCOL_VERSION,
            event_type=event_type,
            incident_id=str(incident_id),
            timestamp=now_iso,
            sequence=seq,
            payload=payload
        )
        msg_text = event.model_dump_json()

        # 1. Deliver to local subscribers on this worker process
        for sub in subscribers:
            if authorizer is not None:
                is_authorized = False
                try:
                    is_authorized = await authorizer(incident_id, sub.user_id)
                except Exception:
                    is_authorized = False
                if not is_authorized:
                    await self._notify_and_disconnect(
                        sub, incident_id, "ACCESS_REVOKED", {"reason": "Authorization expired or revoked"}
                    )
                    continue

            try:
                await sub.websocket.send_text(msg_text)
            except Exception:
                await self.unregister(incident_id, sub.connection_id)

        # 2. Publish to distributed message bus for other workers
        dist_msg = DistributedMessage(
            origin_worker_id=self.worker_id,
            incident_id=str(incident_id),
            event_type=event_type,
            payload=payload,
            timestamp=now_iso,
            sequence=seq
        )
        await self._publish_to_bus(dist_msg)

    async def send_to_user(
        self,
        incident_id: UUID,
        user_id: UUID,
        event_type: str,
        payload: dict[str, Any]
    ):
        """Send a direct event to a specific user's connected sessions for this incident."""
        subscribers: list[ActiveSubscriber] = []
        async with self._lock:
            subs_map = self._incidents.get(incident_id, {})
            subscribers = [s for s in subs_map.values() if s.user_id == user_id]
            seq = self._next_sequence(incident_id)

        now_iso = datetime.now(timezone.utc).isoformat()
        event = RealtimeEvent(
            protocol_version=PROTOCOL_VERSION,
            event_type=event_type,
            incident_id=str(incident_id),
            timestamp=now_iso,
            sequence=seq,
            payload=payload
        )
        msg_text = event.model_dump_json()

        for sub in subscribers:
            try:
                await sub.websocket.send_text(msg_text)
            except Exception:
                await self.unregister(incident_id, sub.connection_id)

        # Cross-worker delivery for direct user events
        dist_msg = DistributedMessage(
            origin_worker_id=self.worker_id,
            incident_id=str(incident_id),
            event_type=event_type,
            payload=payload,
            timestamp=now_iso,
            sequence=seq,
            user_id=str(user_id)
        )
        await self._publish_to_bus(dist_msg)

    async def disconnect_user(self, incident_id: UUID, user_id: UUID, reason: str = "Access revoked"):
        """Notify with ACCESS_REVOKED and disconnect all sessions for a specific user across workers."""
        subscribers: list[ActiveSubscriber] = []
        async with self._lock:
            subs_map = self._incidents.get(incident_id, {})
            subscribers = [s for s in subs_map.values() if s.user_id == user_id]

        for sub in subscribers:
            await self._notify_and_disconnect(sub, incident_id, "ACCESS_REVOKED", {"reason": reason})

        dist_msg = DistributedMessage(
            origin_worker_id=self.worker_id,
            incident_id=str(incident_id),
            event_type="REVOKE_USER",
            payload={"reason": reason},
            timestamp=datetime.now(timezone.utc).isoformat(),
            sequence=0,
            user_id=str(user_id),
            reason=reason
        )
        await self._publish_to_bus(dist_msg)

    async def close_incident(self, incident_id: UUID, reason: str = "Incident resolved"):
        """Broadcast INCIDENT_CLOSED and terminate all active connections across workers."""
        subscribers: list[ActiveSubscriber] = []
        async with self._lock:
            subs_map = self._incidents.pop(incident_id, {})
            subscribers = list(subs_map.values())
            seq = self._next_sequence(incident_id)

        now_iso = datetime.now(timezone.utc).isoformat()
        event = RealtimeEvent(
            protocol_version=PROTOCOL_VERSION,
            event_type="INCIDENT_CLOSED",
            incident_id=str(incident_id),
            timestamp=now_iso,
            sequence=seq,
            payload={"reason": reason}
        )
        msg_text = event.model_dump_json()

        for sub in subscribers:
            try:
                await sub.websocket.send_text(msg_text)
                await sub.websocket.close(code=1000, reason=reason)
            except Exception:
                pass

        dist_msg = DistributedMessage(
            origin_worker_id=self.worker_id,
            incident_id=str(incident_id),
            event_type="INCIDENT_CLOSED",
            payload={"reason": reason},
            timestamp=now_iso,
            sequence=seq,
            reason=reason
        )
        await self._publish_to_bus(dist_msg)

    async def _notify_and_disconnect(
        self,
        sub: ActiveSubscriber,
        incident_id: UUID,
        event_type: str,
        payload: dict[str, Any]
    ):
        try:
            seq = self._next_sequence(incident_id)
            event = RealtimeEvent(
                protocol_version=PROTOCOL_VERSION,
                event_type=event_type,
                incident_id=str(incident_id),
                timestamp=datetime.now(timezone.utc).isoformat(),
                sequence=seq,
                payload=payload
            )
            await sub.websocket.send_text(event.model_dump_json())
            await sub.websocket.close(code=1008, reason="Policy Violation")
        except Exception:
            pass
        finally:
            await self.unregister(incident_id, sub.connection_id)

    async def _publish_to_bus(self, msg: DistributedMessage):
        data = msg.model_dump_json()
        if self._redis is not None:
            try:
                await self._redis.publish(self._channel, data)
            except Exception as e:
                logger.warning(f"Failed to publish distributed event to Redis")

        if self._custom_bus is not None:
            try:
                await self._custom_bus.publish(self.worker_id, data)
            except Exception as e:
                logger.warning(f"Failed to publish to custom bus")

    async def _listen_redis_loop(self):
        """Continuous background listener for Redis Pub/Sub with automatic reconnect."""
        backoff = 1.0
        while not self._stopping:
            try:
                if self._redis is None:
                    await asyncio.sleep(1.0)
                    continue

                async with self._redis.pubsub() as pubsub:
                    await pubsub.subscribe(self._channel)
                    self._pubsub = pubsub
                    backoff = 1.0
                    logger.info(f"Worker [{self.worker_id}] subscribed to Redis channel {self._channel}")

                    while not self._stopping:
                        try:
                            msg = await pubsub.get_message(ignore_subscribe_messages=True, timeout=1.0)
                            if msg and msg.get("type") == "message":
                                data = msg.get("data")
                                if data:
                                    await self._handle_remote_message(data)
                        except asyncio.TimeoutError:
                            continue
            except (asyncio.CancelledError, GeneratorExit):
                break
            except Exception:
                self._pubsub = None
                if not self._stopping:
                    logger.warning(f"Redis Pub/Sub error. Reconnecting in {backoff:.1f}s...")
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, 10.0)

    async def _handle_remote_message(self, raw_data: str):
        """Dispatches an incoming distributed event to the local worker event loop."""
        try:
            current_loop = asyncio.get_running_loop()
        except RuntimeError:
            current_loop = None

        if self._worker_loop is not None and self._worker_loop.is_running() and self._worker_loop != current_loop:
            future = asyncio.run_coroutine_threadsafe(self._do_handle_remote_message(raw_data), self._worker_loop)
            try:
                future.result(timeout=5.0)
            except Exception:
                pass
            return

        await self._do_handle_remote_message(raw_data)

    async def _do_handle_remote_message(self, raw_data: str):
        """Processes an incoming distributed event originating from another worker process."""
        try:
            msg = DistributedMessage.model_validate_json(raw_data)
        except Exception:
            return

        if msg.origin_worker_id == self.worker_id:
            # Skip messages that originated locally
            return

        try:
            inc_uuid = UUID(msg.incident_id)
        except Exception:
            return

        # 1. Cross-Worker Revocation: Disconnect local sessions immediately
        if msg.event_type == "REVOKE_USER":
            if msg.user_id:
                try:
                    u_uuid = UUID(msg.user_id)
                    async with self._lock:
                        subs_map = self._incidents.get(inc_uuid, {})
                        targets = [s for s in subs_map.values() if s.user_id == u_uuid]
                    for sub in targets:
                        await self._notify_and_disconnect(sub, inc_uuid, "ACCESS_REVOKED", {"reason": msg.reason or "Revoked"})
                except Exception:
                    pass
            return

        # 2. Cross-Worker Incident Closure: Close all local sessions immediately
        if msg.event_type == "INCIDENT_CLOSED":
            async with self._lock:
                subs_map = self._incidents.pop(inc_uuid, {})
                subscribers = list(subs_map.values())

            event = RealtimeEvent(
                protocol_version=PROTOCOL_VERSION,
                event_type="INCIDENT_CLOSED",
                incident_id=str(inc_uuid),
                timestamp=msg.timestamp,
                sequence=msg.sequence,
                payload=msg.payload
            )
            msg_text = event.model_dump_json()
            for sub in subscribers:
                try:
                    await sub.websocket.send_text(msg_text)
                    await sub.websocket.close(code=1000, reason=msg.reason or "Incident resolved")
                except Exception:
                    pass
            return

        # 3. Standard Event Delivery to Local Subscribers with Database-backed Authorization
        async with self._lock:
            subs_map = self._incidents.get(inc_uuid, {})
            subscribers = list(subs_map.values())

        if not subscribers:
            return

        event = RealtimeEvent(
            protocol_version=PROTOCOL_VERSION,
            event_type=msg.event_type,
            incident_id=str(inc_uuid),
            timestamp=msg.timestamp,
            sequence=self._next_sequence(inc_uuid),
            payload=msg.payload
        )
        msg_text = event.model_dump_json()

        for sub in subscribers:
            # If target user specified for direct message, filter
            if msg.user_id and str(sub.user_id) != msg.user_id:
                continue

            # Ground-truth authorization check via PostgreSQL database
            is_authorized = False
            if self._sessions is not None:
                try:
                    async with self._sessions() as db:
                        from app.services.incidents import is_authorized_participant
                        is_authorized = await is_authorized_participant(db, inc_uuid, sub.user_id)
                except Exception:
                    logger.warning("Database authorization check unavailable")
                    import traceback
                    traceback.print_exc()
                    is_authorized = False

            if not is_authorized:
                await self._notify_and_disconnect(
                    sub, inc_uuid, "ACCESS_REVOKED", {"reason": "Authorization expired or revoked"}
                )
                continue

            try:
                await sub.websocket.send_text(msg_text)
            except Exception:
                await self.unregister(inc_uuid, sub.connection_id)


hub = RealtimeHub()


