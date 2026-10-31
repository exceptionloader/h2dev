"""WebSocket gateway: one connection per client, server → client events.

Clients authenticate with the session cookie (browsers; Origin is checked) or with
``Authorization: Bot <token>`` (bots). Clients only send "ping"; all actions use REST.
"""

import asyncio
import contextlib
import json
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.api.deps import resolve_bot
from app.core.config import get_settings
from app.core.database import SessionLocal
from app.models import User
from app.realtime import events
from app.realtime.hub import Connection, hub
from app.services import auth_service

router = APIRouter()
log = logging.getLogger("nova.gateway")


@router.websocket("/gateway")
async def gateway(ws: WebSocket) -> None:
    settings = get_settings()
    origin = ws.headers.get("origin")
    if origin and origin not in settings.allowed_origins:
        await ws.close(code=4403)
        return

    user: User | None = None
    session_id = None
    async with SessionLocal() as db:
        authorization = ws.headers.get("authorization")
        if authorization:
            user = await resolve_bot(db, authorization)
        else:
            token = ws.cookies.get(settings.session_cookie_name)
            resolved = await auth_service.resolve_session(db, token) if token else None
            if resolved:
                session, user = resolved
                session_id = session.id
    if user is None:
        await ws.close(code=4401)
        return

    await ws.accept()
    conn = Connection(user.id, ws, session_id)
    first = hub.register(conn)
    sender = asyncio.create_task(conn.sender())
    conn.push(json.dumps({"type": "ready", "data": {"user_id": str(user.id)}}))
    if first:
        async with SessionLocal() as db:
            await events.presence_changed(db, user)

    try:
        while not conn.closed:
            text = await ws.receive_text()
            if text == "ping":
                conn.push('{"type":"pong","data":{}}')
    except WebSocketDisconnect:
        pass
    except Exception:  # pragma: no cover - defensive: never kill the server task
        log.exception("gateway.error", extra={"user_id": str(user.id)})
    finally:
        sender.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await sender
        if hub.unregister(conn):
            await _went_offline(user.id)


async def _went_offline(user_id) -> None:  # noqa: ANN001
    async with SessionLocal() as db:
        user = await db.get(User, user_id)
        voice = hub.voice.pop(user_id, None)
        if voice:
            await events.to_server(
                db,
                voice.server_id,
                "voice.state",
                {"user_id": user_id, "server_id": voice.server_id, "channel_id": None},
            )
        if user:
            await events.presence_changed(db, user)
