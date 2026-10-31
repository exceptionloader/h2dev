"""In-process realtime hub: WebSocket connections, presence and voice states.

All state lives in this process. That is fine for the private beta (one backend process).
Before running several processes, move fan-out and these maps to Redis pub/sub.
"""

import asyncio
import json
import logging
import uuid
from collections import defaultdict
from dataclasses import asdict, dataclass, field
from datetime import datetime
from typing import Any, Protocol

from app.models import User
from app.models.base import utcnow
from app.schemas.user import UserPublic

log = logging.getLogger("nova.realtime")

QUEUE_SIZE = 256


class _Socket(Protocol):
    async def send_text(self, data: str) -> None: ...
    async def close(self, code: int = 1000) -> None: ...


class Connection:
    """One WebSocket with its own bounded send queue (slow clients get disconnected)."""

    def __init__(
        self, user_id: uuid.UUID, socket: _Socket, session_id: uuid.UUID | None = None
    ) -> None:
        self.user_id = user_id
        self.session_id = session_id
        self.socket = socket
        self.queue: asyncio.Queue[str] = asyncio.Queue(maxsize=QUEUE_SIZE)
        self.closed = False

    def push(self, payload: str) -> None:
        if self.closed:
            return
        try:
            self.queue.put_nowait(payload)
        except asyncio.QueueFull:
            log.warning("realtime.slow_consumer", extra={"user_id": str(self.user_id)})
            self.closed = True
            asyncio.get_running_loop().create_task(self.socket.close(code=4008))

    async def sender(self) -> None:
        while not self.closed:
            payload = await self.queue.get()
            await self.socket.send_text(payload)


@dataclass(slots=True)
class VoiceState:
    user_id: uuid.UUID
    server_id: uuid.UUID
    channel_id: uuid.UUID
    muted: bool = False
    deafened: bool = False
    joined_at: datetime = field(default_factory=utcnow)

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        return {k: str(v) if isinstance(v, uuid.UUID | datetime) else v for k, v in data.items()}


class Hub:
    def __init__(self) -> None:
        self._connections: dict[uuid.UUID, set[Connection]] = defaultdict(set)
        self.voice: dict[uuid.UUID, VoiceState] = {}

    # --- connections & presence ---------------------------------------------------------

    def register(self, conn: Connection) -> bool:
        """Returns True if this is the user's first connection (they came online)."""
        conns = self._connections[conn.user_id]
        conns.add(conn)
        return len(conns) == 1

    def unregister(self, conn: Connection) -> bool:
        """Returns True if this was the user's last connection (they went offline)."""
        conn.closed = True
        conns = self._connections.get(conn.user_id)
        if not conns:
            return False
        conns.discard(conn)
        if not conns:
            del self._connections[conn.user_id]
            return True
        return False

    def is_connected(self, user_id: uuid.UUID) -> bool:
        return bool(self._connections.get(user_id))

    def presence(self, user: User) -> str:
        if not self.is_connected(user.id) or user.status == "invisible":
            return "offline"
        return user.status

    def send(self, user_ids: object, event_type: str, data: dict[str, Any]) -> None:
        payload = json.dumps({"type": event_type, "data": data}, default=str)
        for user_id in set(user_ids):  # type: ignore[call-overload]
            for conn in list(self._connections.get(user_id, ())):
                conn.push(payload)

    def disconnect_sessions(
        self,
        user_id: uuid.UUID,
        session_id: uuid.UUID | None = None,
        keep_session_id: uuid.UUID | None = None,
        code: int = 4001,
    ) -> None:
        """Close sockets of revoked sessions: one session, or all except ``keep_session_id``."""
        for conn in list(self._connections.get(user_id, ())):
            if session_id is not None and conn.session_id != session_id:
                continue
            if keep_session_id is not None and conn.session_id == keep_session_id:
                continue
            conn.closed = True
            asyncio.get_running_loop().create_task(conn.socket.close(code=code))

    # --- voice ----------------------------------------------------------------------------

    def voice_states_for_server(self, server_id: uuid.UUID) -> list[VoiceState]:
        return [v for v in self.voice.values() if v.server_id == server_id]

    def reset(self) -> None:
        self._connections.clear()
        self.voice.clear()


hub = Hub()


def public_user(user: User) -> UserPublic:
    out = UserPublic.model_validate(user)
    out.presence = hub.presence(user)  # type: ignore[assignment]
    return out
