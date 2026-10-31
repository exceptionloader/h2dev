"""Servers, membership, roles and channels."""

import uuid
from collections import defaultdict
from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core import permissions as perms
from app.core.errors import AppError, Forbidden, NotFound
from app.core.permissions import MemberContext, OverrideInfo, Permission, RoleInfo
from app.models import (
    Channel,
    ChannelPermissionOverride,
    MemberRole,
    Server,
    ServerMember,
    ServerRole,
    User,
)
from app.schemas.server import ChannelCreate, ServerCreate, ServerUpdate
from app.services import audit_service

MAX_SERVERS_PER_OWNER = 25
MAX_CHANNELS_PER_SERVER = 200


@dataclass(slots=True)
class ServerAccess:
    """The current user's membership in a server plus their permission context."""

    server: Server
    member: ServerMember
    ctx: MemberContext

    @property
    def permissions(self) -> Permission:
        return perms.base_permissions(self.ctx)

    def require(self, permission: Permission) -> None:
        if not perms.has(self.permissions, permission):
            raise Forbidden(f"Missing permission {permission.name}", code="missing_permission")

    def require_owner(self) -> None:
        if not self.ctx.is_owner:
            raise Forbidden("Only the server owner can do this", code="owner_only")


def _role_info(role: ServerRole) -> RoleInfo:
    return RoleInfo(
        id=role.id, position=role.position, permissions=role.permissions, is_default=role.is_default
    )


async def get_access(db: AsyncSession, server_id: uuid.UUID, user_id: uuid.UUID) -> ServerAccess:
    """Load membership; non-members get 404 so server existence is not leaked."""
    row = (
        await db.execute(
            select(Server, ServerMember)
            .join(ServerMember, ServerMember.server_id == Server.id)
            .where(Server.id == server_id, ServerMember.user_id == user_id)
        )
    ).first()
    if row is None:
        raise NotFound("Server not found", code="server_not_found")
    server, member = row

    roles = list(
        await db.scalars(
            select(ServerRole).where(
                ServerRole.server_id == server_id,
                (ServerRole.is_default.is_(True))
                | ServerRole.id.in_(
                    select(MemberRole.role_id).where(MemberRole.member_id == member.id)
                ),
            )
        )
    )
    default = next(r for r in roles if r.is_default)
    ctx = MemberContext(
        member_id=member.id,
        is_owner=server.owner_id == user_id,
        default_role=_role_info(default),
        roles=tuple(_role_info(r) for r in roles if not r.is_default),
    )
    return ServerAccess(server=server, member=member, ctx=ctx)


# --- Servers ---------------------------------------------------------------------------


async def create_server(db: AsyncSession, owner: User, data: ServerCreate) -> Server:
    owned = await db.scalar(select(func.count()).where(Server.owner_id == owner.id))
    if (owned or 0) >= MAX_SERVERS_PER_OWNER:
        raise AppError("Server limit reached", code="server_limit_reached")

    server = Server(
        name=data.name, description=data.description, visibility=data.visibility, owner_id=owner.id
    )
    db.add(server)
    await db.flush()

    member = ServerMember(server_id=server.id, user_id=owner.id)
    default_role = ServerRole(
        server_id=server.id,
        name="Mitglied",
        position=0,
        is_default=True,
        permissions=int(perms.DEFAULT_MEMBER_PERMISSIONS),
    )
    db.add_all(
        [
            member,
            default_role,
            ServerRole(
                server_id=server.id,
                name="Moderator",
                color="#22D3EE",
                position=1,
                permissions=int(perms.DEFAULT_MODERATOR_PERMISSIONS),
            ),
            ServerRole(
                server_id=server.id,
                name="Admin",
                color="#8B5CF6",
                position=2,
                permissions=int(perms.DEFAULT_ADMIN_PERMISSIONS),
            ),
        ]
    )
    await db.flush()

    text_cat = Channel(server_id=server.id, type="category", name="Textkanäle", position=0)
    voice_cat = Channel(server_id=server.id, type="category", name="Sprachkanäle", position=1)
    db.add_all([text_cat, voice_cat])
    await db.flush()
    announcements = Channel(
        server_id=server.id,
        parent_id=text_cat.id,
        type="announcement",
        name="ankündigungen",
        position=1,
    )
    db.add_all(
        [
            Channel(
                server_id=server.id,
                parent_id=text_cat.id,
                type="text",
                name="allgemein",
                position=0,
            ),
            announcements,
            Channel(
                server_id=server.id, parent_id=voice_cat.id, type="voice", name="Lobby", position=0
            ),
        ]
    )
    await db.flush()
    # Announcement channels: ordinary members can read but not post.
    db.add(
        ChannelPermissionOverride(
            channel_id=announcements.id,
            role_id=default_role.id,
            deny=int(Permission.SEND_MESSAGES),
        )
    )
    audit_service.record(
        db,
        server_id=server.id,
        actor_id=owner.id,
        action="server.create",
        target_type="server",
        target_id=server.id,
        details={"name": server.name},
    )
    await db.flush()
    return server


async def list_user_servers(db: AsyncSession, user_id: uuid.UUID) -> list[Server]:
    rows = await db.scalars(
        select(Server)
        .join(ServerMember, ServerMember.server_id == Server.id)
        .where(ServerMember.user_id == user_id)
        .order_by(ServerMember.joined_at)
    )
    return list(rows)


async def member_count(db: AsyncSession, server_id: uuid.UUID) -> int:
    return (await db.scalar(select(func.count()).where(ServerMember.server_id == server_id))) or 0


async def update_server(
    db: AsyncSession, access: ServerAccess, actor: User, data: ServerUpdate
) -> Server:
    access.require(Permission.MANAGE_SERVER)
    server = access.server
    changes: dict[str, object] = {}
    for field, value in data.model_dump(exclude_unset=True).items():
        if field == "name" and value is None:
            continue
        if getattr(server, field) != value:
            changes[field] = {"from": getattr(server, field), "to": value}
            setattr(server, field, value)
    if changes:
        audit_service.record(
            db,
            server_id=server.id,
            actor_id=actor.id,
            action="server.update",
            target_type="server",
            target_id=server.id,
            details=changes,
        )
    await db.flush()
    return server


async def delete_server(db: AsyncSession, access: ServerAccess, confirm_name: str) -> None:
    access.require_owner()
    if confirm_name.strip() != access.server.name:
        raise AppError("Confirmation name does not match", code="confirm_name_mismatch")
    await db.delete(access.server)
    await db.flush()


# --- Members & roles -------------------------------------------------------------------


async def list_members(
    db: AsyncSession, server: Server
) -> list[tuple[ServerMember, list[uuid.UUID]]]:
    members = list(
        await db.scalars(
            select(ServerMember)
            .where(ServerMember.server_id == server.id)
            .options(selectinload(ServerMember.user))
            .order_by(ServerMember.joined_at)
        )
    )
    role_map: dict[uuid.UUID, list[uuid.UUID]] = defaultdict(list)
    if members:
        for member_id, role_id in await db.execute(
            select(MemberRole.member_id, MemberRole.role_id).where(
                MemberRole.member_id.in_([m.id for m in members])
            )
        ):
            role_map[member_id].append(role_id)
    return [(m, role_map[m.id]) for m in members]


async def list_roles(db: AsyncSession, server_id: uuid.UUID) -> list[ServerRole]:
    rows = await db.scalars(
        select(ServerRole)
        .where(ServerRole.server_id == server_id)
        .order_by(ServerRole.position.desc())
    )
    return list(rows)


# --- Channels --------------------------------------------------------------------------


async def _overrides_by_channel(
    db: AsyncSession, channel_ids: list[uuid.UUID]
) -> dict[uuid.UUID, list[ChannelPermissionOverride]]:
    result: dict[uuid.UUID, list[ChannelPermissionOverride]] = defaultdict(list)
    if channel_ids:
        for ov in await db.scalars(
            select(ChannelPermissionOverride).where(
                ChannelPermissionOverride.channel_id.in_(channel_ids)
            )
        ):
            result[ov.channel_id].append(ov)
    return result


def _to_info(overrides: list[ChannelPermissionOverride]) -> list[OverrideInfo]:
    return [
        OverrideInfo(allow=o.allow, deny=o.deny, role_id=o.role_id, member_id=o.member_id)
        for o in overrides
    ]


def _is_private(overrides: list[ChannelPermissionOverride], default_role_id: uuid.UUID) -> bool:
    return any(o.role_id == default_role_id and o.deny & Permission.VIEW_CHANNEL for o in overrides)


async def list_visible_channels(
    db: AsyncSession, access: ServerAccess
) -> list[tuple[Channel, Permission, bool]]:
    channels = list(
        await db.scalars(
            select(Channel)
            .where(Channel.server_id == access.server.id)
            .order_by(Channel.position, Channel.created_at)
        )
    )
    overrides = await _overrides_by_channel(db, [c.id for c in channels])
    visible = []
    for ch in channels:
        ch_overrides = overrides.get(ch.id, [])
        effective = perms.channel_permissions(access.ctx, _to_info(ch_overrides))
        if Permission.VIEW_CHANNEL in effective:
            visible.append((ch, effective, _is_private(ch_overrides, access.ctx.default_role.id)))
    return visible


async def create_channel(
    db: AsyncSession, access: ServerAccess, actor: User, data: ChannelCreate
) -> tuple[Channel, Permission, bool]:
    access.require(Permission.MANAGE_CHANNELS)
    server_id = access.server.id

    count = await db.scalar(select(func.count()).where(Channel.server_id == server_id))
    if (count or 0) >= MAX_CHANNELS_PER_SERVER:
        raise AppError("Channel limit reached", code="channel_limit_reached")

    if data.parent_id is not None:
        if data.type == "category":
            raise AppError("Categories cannot be nested", code="invalid_parent")
        parent = await db.get(Channel, data.parent_id)
        if parent is None or parent.server_id != server_id or parent.type != "category":
            raise AppError("Parent must be a category of this server", code="invalid_parent")

    position_q = select(func.coalesce(func.max(Channel.position) + 1, 0)).where(
        Channel.server_id == server_id
    )
    position_q = position_q.where(
        Channel.parent_id == data.parent_id if data.parent_id else Channel.parent_id.is_(None)
    )
    position = await db.scalar(position_q) or 0

    channel = Channel(
        server_id=server_id,
        parent_id=data.parent_id,
        type=data.type,
        name=data.name,
        topic=data.topic,
        position=position,
    )
    db.add(channel)
    await db.flush()

    # Private channels hide from the default role; announcement channels are read-only for it.
    default_deny = 0
    if data.private:
        default_deny |= int(Permission.VIEW_CHANNEL)
    if data.type == "announcement":
        default_deny |= int(Permission.SEND_MESSAGES)

    overrides: list[ChannelPermissionOverride] = []
    if default_deny:
        overrides.append(
            ChannelPermissionOverride(
                channel_id=channel.id, role_id=access.ctx.default_role.id, deny=default_deny
            )
        )
    # Make sure a non-admin creator does not lock themselves out of a private channel.
    if data.private and Permission.ADMINISTRATOR not in access.permissions:
        overrides.append(
            ChannelPermissionOverride(
                channel_id=channel.id,
                member_id=access.member.id,
                allow=int(Permission.VIEW_CHANNEL),
            )
        )
    db.add_all(overrides)

    audit_service.record(
        db,
        server_id=server_id,
        actor_id=actor.id,
        action="channel.create",
        target_type="channel",
        target_id=channel.id,
        details={"name": channel.name, "type": channel.type, "private": data.private},
    )
    await db.flush()
    effective = perms.channel_permissions(access.ctx, _to_info(overrides))
    return channel, effective, data.private
