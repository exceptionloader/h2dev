"""Publishing events to connected clients. Always call *after* the DB commit."""

import uuid
from typing import Any

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Channel, Friendship, ServerMember
from app.realtime.hub import hub
from app.services import server_service


async def to_channel(
    db: AsyncSession, channel: Channel, event_type: str, data: dict[str, Any]
) -> None:
    hub.send(await server_service.channel_viewer_ids(db, channel), event_type, data)


async def to_server(
    db: AsyncSession, server_id: uuid.UUID, event_type: str, data: dict[str, Any]
) -> None:
    hub.send(await server_service.server_member_ids(db, server_id), event_type, data)


async def server_changed(db: AsyncSession, server_id: uuid.UUID, *scopes: str) -> None:
    """Tell members to refetch parts of a server (members, roles, channels, server, spaces)."""
    await to_server(db, server_id, "server.changed", {"server_id": server_id, "scopes": scopes})


def to_users(user_ids: list[uuid.UUID], event_type: str, data: dict[str, Any]) -> None:
    hub.send(user_ids, event_type, data)


async def presence_audience(db: AsyncSession, user_id: uuid.UUID) -> set[uuid.UUID]:
    """Everyone who can see this user's presence: server co-members and friends."""
    my_servers = select(ServerMember.server_id).where(ServerMember.user_id == user_id)
    co_members = set(
        await db.scalars(
            select(ServerMember.user_id).where(ServerMember.server_id.in_(my_servers)).distinct()
        )
    )
    friends = await db.execute(
        select(Friendship.requester_id, Friendship.addressee_id).where(
            Friendship.status == "accepted",
            or_(Friendship.requester_id == user_id, Friendship.addressee_id == user_id),
        )
    )
    for a, b in friends:
        co_members.update((a, b))
    co_members.discard(user_id)
    return co_members


async def presence_changed(db: AsyncSession, user) -> None:  # noqa: ANN001 - User
    audience = await presence_audience(db, user.id)
    audience.add(user.id)
    hub.send(audience, "presence.updated", {"user_id": user.id, "presence": hub.presence(user)})
