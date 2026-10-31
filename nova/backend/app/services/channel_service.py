"""Channel editing, deletion and permission overrides."""

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import permissions as perms
from app.core.errors import AppError, Forbidden, NotFound
from app.core.permissions import Permission
from app.models import Channel, ChannelPermissionOverride, ServerRole, User
from app.services import audit_service
from app.services.member_service import to_bits
from app.services.server_service import ServerAccess, channel_permissions_for


async def _manageable_channel(
    db: AsyncSession, access: ServerAccess, channel_id: uuid.UUID
) -> tuple[Channel, Permission]:
    channel = await db.get(Channel, channel_id)
    if channel is None or channel.server_id != access.server.id:
        raise NotFound("Channel not found", code="channel_not_found")
    effective, _ = await channel_permissions_for(db, access, channel)
    if Permission.VIEW_CHANNEL not in effective:
        raise NotFound("Channel not found", code="channel_not_found")
    if not perms.has(effective, Permission.MANAGE_CHANNELS):
        raise Forbidden("Missing permission MANAGE_CHANNELS", code="missing_permission")
    return channel, effective


async def update_channel(
    db: AsyncSession, access: ServerAccess, actor: User, channel_id: uuid.UUID, values: dict
) -> Channel:
    channel, _ = await _manageable_channel(db, access, channel_id)
    changes: dict[str, object] = {}
    if "parent_id" in values:
        parent_id = values["parent_id"]
        if parent_id is not None:
            if channel.type == "category":
                raise AppError("Categories cannot be nested", code="invalid_parent")
            parent = await db.get(Channel, parent_id)
            if parent is None or parent.server_id != channel.server_id or parent.type != "category":
                raise AppError("Parent must be a category of this server", code="invalid_parent")
    for field in ("name", "topic", "parent_id"):
        if field in values and values[field] != getattr(channel, field):
            if field == "name" and not values[field]:
                continue
            changes[field] = {"from": str(getattr(channel, field)), "to": str(values[field])}
            setattr(channel, field, values[field] or None if field != "name" else values[field])
    if changes:
        audit_service.record(
            db,
            server_id=channel.server_id,
            actor_id=actor.id,
            action="channel.update",
            target_type="channel",
            target_id=channel.id,
            details=changes,
        )
    await db.flush()
    return channel


async def delete_channel(
    db: AsyncSession, access: ServerAccess, actor: User, channel_id: uuid.UUID
) -> Channel:
    channel, _ = await _manageable_channel(db, access, channel_id)
    await db.delete(channel)
    audit_service.record(
        db,
        server_id=channel.server_id,
        actor_id=actor.id,
        action="channel.delete",
        target_type="channel",
        target_id=channel.id,
        details={"name": channel.name, "type": channel.type},
    )
    await db.flush()
    return channel


async def list_overrides(
    db: AsyncSession, access: ServerAccess, channel_id: uuid.UUID
) -> list[ChannelPermissionOverride]:
    channel, _ = await _manageable_channel(db, access, channel_id)
    return list(
        await db.scalars(
            select(ChannelPermissionOverride).where(
                ChannelPermissionOverride.channel_id == channel.id
            )
        )
    )


async def set_role_override(
    db: AsyncSession,
    access: ServerAccess,
    actor: User,
    channel_id: uuid.UUID,
    role_id: uuid.UUID,
    allow_names: list[str],
    deny_names: list[str],
) -> Channel:
    """Create, replace or (if both lists are empty) remove a role's channel override."""
    channel, _ = await _manageable_channel(db, access, channel_id)
    access.require(Permission.MANAGE_ROLES)
    role = await db.get(ServerRole, role_id)
    if role is None or role.server_id != channel.server_id:
        raise NotFound("Role not found", code="role_not_found")
    if not role.is_default and not perms.can_manage_role(
        access.ctx, perms.RoleInfo(role.id, role.position, role.permissions)
    ):
        raise Forbidden("Role is not below your highest role", code="hierarchy")
    allow, deny = to_bits(allow_names), to_bits(deny_names)
    if allow & deny:
        raise AppError("A permission cannot be allowed and denied", code="override_conflict")
    if (allow | deny) & ~int(perms.CHANNEL_SCOPED):
        raise AppError("Only channel permissions can be overridden", code="override_scope")
    existing = await db.scalar(
        select(ChannelPermissionOverride).where(
            ChannelPermissionOverride.channel_id == channel.id,
            ChannelPermissionOverride.role_id == role.id,
        )
    )
    previous = (existing.allow, existing.deny) if existing else (0, 0)
    # Allowing *or denying* a permission requires holding it (no escalation, no sabotage
    # of powers above your own).
    if not perms.can_grant_permissions(access.ctx, (allow | deny) ^ (previous[0] | previous[1])):
        raise Forbidden("You cannot change permissions you do not have", code="escalation")
    if not allow and not deny:
        if existing:
            await db.delete(existing)
    elif existing:
        existing.allow, existing.deny = allow, deny
    else:
        db.add(
            ChannelPermissionOverride(
                channel_id=channel.id, role_id=role.id, allow=allow, deny=deny
            )
        )
    audit_service.record(
        db,
        server_id=channel.server_id,
        actor_id=actor.id,
        action="channel.override",
        target_type="channel",
        target_id=channel.id,
        details={
            "role": role.name,
            "allow": perms.permission_names(allow),
            "deny": perms.permission_names(deny),
        },
    )
    await db.flush()
    return channel
