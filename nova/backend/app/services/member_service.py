"""Membership: invites, joining, leaving, kick/ban, roles and ownership transfer."""

import secrets
import uuid
from datetime import timedelta

from sqlalchemy import delete, func, or_, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import permissions as perms
from app.core.errors import AppError, Conflict, Forbidden, NotFound
from app.core.permissions import Permission, RoleInfo
from app.core.security import verify_password
from app.models import (
    GamingSession,
    GamingSessionMember,
    MemberRole,
    Server,
    ServerBan,
    ServerInvite,
    ServerMember,
    ServerRole,
    User,
)
from app.models.base import utcnow
from app.realtime.hub import hub
from app.services import audit_service
from app.services.server_service import ServerAccess, get_access

MAX_ROLES_PER_SERVER = 50


def to_bits(names: list[str]) -> int:
    bits = 0
    for name in names:
        try:
            bits |= Permission[name]
        except KeyError as exc:
            raise AppError(f"Unknown permission {name}", code="unknown_permission") from exc
    return bits


def _role_info(role: ServerRole) -> RoleInfo:
    return RoleInfo(role.id, role.position, role.permissions, role.is_default)


# --- Joining --------------------------------------------------------------------------------


async def _ensure_not_banned(db: AsyncSession, server_id: uuid.UUID, user_id: uuid.UUID) -> None:
    if await db.get(ServerBan, (server_id, user_id)):
        raise Forbidden("You are banned from this server", code="banned")


async def add_member(db: AsyncSession, server: Server, user: User, via: str) -> ServerMember:
    await _ensure_not_banned(db, server.id, user.id)
    member = ServerMember(server_id=server.id, user_id=user.id)
    db.add(member)
    try:
        async with db.begin_nested():
            await db.flush()
    except IntegrityError as exc:
        raise Conflict("Already a member", code="already_member") from exc
    audit_service.record(
        db,
        server_id=server.id,
        actor_id=user.id,
        action="member.join",
        target_type="user",
        target_id=user.id,
        details={"via": via},
    )
    return member


async def create_invite(
    db: AsyncSession, access: ServerAccess, actor: User, max_uses: int | None, hours: int | None
) -> ServerInvite:
    access.require(Permission.CREATE_INVITES)
    invite = ServerInvite(
        code=secrets.token_urlsafe(6),
        server_id=access.server.id,
        creator_id=actor.id,
        max_uses=max_uses,
        expires_at=utcnow() + timedelta(hours=hours) if hours else None,
    )
    db.add(invite)
    await db.flush()
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="invite.create",
        target_type="invite",
        details={"code": invite.code, "max_uses": max_uses},
    )
    return invite


async def list_invites(db: AsyncSession, access: ServerAccess, actor: User) -> list[ServerInvite]:
    from sqlalchemy.orm import selectinload

    stmt = (
        select(ServerInvite)
        .where(ServerInvite.server_id == access.server.id)
        .options(selectinload(ServerInvite.creator))
        .order_by(ServerInvite.created_at.desc())
    )
    # Members see their own invites; managers see all.
    if not perms.has(access.permissions, Permission.MANAGE_SERVER):
        stmt = stmt.where(ServerInvite.creator_id == actor.id)
    return list(await db.scalars(stmt))


async def delete_invite(db: AsyncSession, code: str, actor: User) -> uuid.UUID:
    invite = await db.scalar(select(ServerInvite).where(ServerInvite.code == code))
    if invite is None:
        raise NotFound("Invite not found", code="invite_not_found")
    access = await get_access(db, invite.server_id, actor.id)
    if invite.creator_id != actor.id:
        access.require(Permission.MANAGE_SERVER)
    await db.delete(invite)
    audit_service.record(
        db,
        server_id=invite.server_id,
        actor_id=actor.id,
        action="invite.delete",
        target_type="invite",
        details={"code": code},
    )
    return invite.server_id


def _invite_valid(invite: ServerInvite) -> bool:
    if invite.expires_at and invite.expires_at <= utcnow():
        return False
    return not (invite.max_uses is not None and invite.uses >= invite.max_uses)


async def get_invite(db: AsyncSession, code: str) -> tuple[ServerInvite, Server]:
    row = (
        await db.execute(
            select(ServerInvite, Server)
            .join(Server, Server.id == ServerInvite.server_id)
            .where(ServerInvite.code == code)
        )
    ).first()
    if row is None or not _invite_valid(row[0]):
        raise NotFound("Invite invalid or expired", code="invite_not_found")
    return row[0], row[1]


async def accept_invite(db: AsyncSession, code: str, user: User) -> Server:
    invite = await db.scalar(
        select(ServerInvite).where(ServerInvite.code == code).with_for_update()
    )
    if invite is None or not _invite_valid(invite):
        raise NotFound("Invite invalid or expired", code="invite_not_found")
    server = await db.get(Server, invite.server_id)
    assert server is not None
    existing = await db.scalar(
        select(ServerMember).where(
            ServerMember.server_id == server.id, ServerMember.user_id == user.id
        )
    )
    if existing:
        return server
    await add_member(db, server, user, via=f"invite:{code}")
    invite.uses += 1
    return server


async def join_public(db: AsyncSession, server_id: uuid.UUID, user: User) -> Server:
    server = await db.get(Server, server_id)
    if server is None or server.visibility != "public":
        raise NotFound("Server not found", code="server_not_found")
    await add_member(db, server, user, via="discover")
    return server


async def discover(db: AsyncSession, user: User, query: str | None, limit: int = 30):
    member_count = (
        select(func.count())
        .where(ServerMember.server_id == Server.id)
        .correlate(Server)
        .scalar_subquery()
    )
    is_member = (
        select(ServerMember.id)
        .where(ServerMember.server_id == Server.id, ServerMember.user_id == user.id)
        .correlate(Server)
        .exists()
    )
    stmt = select(Server, member_count, is_member).where(Server.visibility == "public")
    if query:
        like = f"%{query.strip()[:64].replace('%', '').replace('_', '')}%"
        stmt = stmt.where(or_(Server.name.ilike(like), Server.description.ilike(like)))
    stmt = stmt.order_by(member_count.desc(), Server.created_at).limit(limit)
    return list(await db.execute(stmt))


# --- Leaving, kicking, banning --------------------------------------------------------------


async def remove_member(db: AsyncSession, server_id: uuid.UUID, user_id: uuid.UUID) -> None:
    """Remove membership and everything tied to it inside the server."""
    await db.execute(
        delete(ServerMember).where(
            ServerMember.server_id == server_id, ServerMember.user_id == user_id
        )
    )
    await db.execute(
        delete(GamingSessionMember).where(
            GamingSessionMember.user_id == user_id,
            GamingSessionMember.session_id.in_(
                select(GamingSession.id).where(GamingSession.server_id == server_id)
            ),
        )
    )
    state = hub.voice.get(user_id)
    if state and state.server_id == server_id:
        del hub.voice[user_id]


async def _target_access(db: AsyncSession, server: Server, user_id: uuid.UUID) -> ServerAccess:
    try:
        return await get_access(db, server.id, user_id)
    except NotFound as exc:
        raise NotFound("Member not found", code="member_not_found") from exc


async def leave(db: AsyncSession, access: ServerAccess, user: User) -> None:
    if access.ctx.is_owner:
        raise AppError("Transfer ownership before leaving", code="owner_cannot_leave")
    await remove_member(db, access.server.id, user.id)
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=user.id,
        action="member.leave",
        target_type="user",
        target_id=user.id,
    )


async def kick(db: AsyncSession, access: ServerAccess, actor: User, user_id: uuid.UUID) -> None:
    access.require(Permission.KICK_MEMBERS)
    target = await _target_access(db, access.server, user_id)
    if not perms.can_moderate_member(access.ctx, target.ctx):
        raise Forbidden("Target is not below you", code="hierarchy")
    await remove_member(db, access.server.id, user_id)
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="member.kick",
        target_type="user",
        target_id=user_id,
    )


async def ban(
    db: AsyncSession, access: ServerAccess, actor: User, user_id: uuid.UUID, reason: str | None
) -> None:
    access.require(Permission.BAN_MEMBERS)
    if user_id == actor.id:
        raise AppError("You cannot ban yourself", code="hierarchy")
    if user_id == access.server.owner_id:
        raise Forbidden("The owner cannot be banned", code="hierarchy")
    if await db.get(User, user_id) is None:
        raise NotFound("User not found", code="user_not_found")
    is_member = await db.scalar(
        select(ServerMember.id).where(
            ServerMember.server_id == access.server.id, ServerMember.user_id == user_id
        )
    )
    if is_member:
        target = await _target_access(db, access.server, user_id)
        if not perms.can_moderate_member(access.ctx, target.ctx):
            raise Forbidden("Target is not below you", code="hierarchy")
        await remove_member(db, access.server.id, user_id)
    if await db.get(ServerBan, (access.server.id, user_id)) is None:
        db.add(
            ServerBan(
                server_id=access.server.id, user_id=user_id, reason=reason, banned_by=actor.id
            )
        )
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="member.ban",
        target_type="user",
        target_id=user_id,
        details={"reason": reason},
    )
    await db.flush()


async def unban(db: AsyncSession, access: ServerAccess, actor: User, user_id: uuid.UUID) -> None:
    access.require(Permission.BAN_MEMBERS)
    ban_row = await db.get(ServerBan, (access.server.id, user_id))
    if ban_row is None:
        raise NotFound("Not banned", code="ban_not_found")
    await db.delete(ban_row)
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="member.unban",
        target_type="user",
        target_id=user_id,
    )


async def list_bans(db: AsyncSession, access: ServerAccess) -> list[ServerBan]:
    from sqlalchemy.orm import selectinload

    access.require(Permission.BAN_MEMBERS)
    return list(
        await db.scalars(
            select(ServerBan)
            .where(ServerBan.server_id == access.server.id)
            .options(selectinload(ServerBan.user))
            .order_by(ServerBan.created_at.desc())
        )
    )


# --- Roles ----------------------------------------------------------------------------------


async def _get_role(db: AsyncSession, server_id: uuid.UUID, role_id: uuid.UUID) -> ServerRole:
    role = await db.get(ServerRole, role_id)
    if role is None or role.server_id != server_id:
        raise NotFound("Role not found", code="role_not_found")
    return role


def _require_manage(access: ServerAccess, role: ServerRole) -> None:
    if not perms.can_manage_role(access.ctx, _role_info(role)):
        raise Forbidden("Role is not below your highest role", code="hierarchy")


def _require_grantable(access: ServerAccess, bits: int) -> None:
    if not perms.can_grant_permissions(access.ctx, bits):
        raise Forbidden("You cannot grant permissions you do not have", code="escalation")


async def create_role(
    db: AsyncSession,
    access: ServerAccess,
    actor: User,
    name: str,
    color: str | None,
    permission_names: list[str],
    managed: bool = False,
) -> ServerRole:
    access.require(Permission.MANAGE_ROLES)
    bits = to_bits(permission_names)
    _require_grantable(access, bits)
    count = await db.scalar(select(func.count()).where(ServerRole.server_id == access.server.id))
    if (count or 0) >= MAX_ROLES_PER_SERVER:
        raise AppError("Role limit reached", code="role_limit_reached")
    if await db.scalar(
        select(ServerRole.id).where(
            ServerRole.server_id == access.server.id, func.lower(ServerRole.name) == name.lower()
        )
    ):
        raise Conflict("Role name already used", code="role_name_taken")
    # New roles go directly above the default role; everything above shifts up, so the
    # relative order (and therefore everyone's authority) is unchanged.
    await db.execute(
        update(ServerRole)
        .where(ServerRole.server_id == access.server.id, ServerRole.is_default.is_(False))
        .values(position=ServerRole.position + 1)
    )
    role = ServerRole(
        server_id=access.server.id,
        name=name,
        color=color,
        position=1,
        permissions=bits,
        managed=managed,
    )
    db.add(role)
    await db.flush()
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="role.create",
        target_type="role",
        target_id=role.id,
        details={"name": name, "permissions": perms.permission_names(bits)},
    )
    return role


async def update_role(
    db: AsyncSession,
    access: ServerAccess,
    actor: User,
    role_id: uuid.UUID,
    name: str | None,
    color: str | None,
    color_set: bool,
    permission_names: list[str] | None,
) -> ServerRole:
    access.require(Permission.MANAGE_ROLES)
    role = await _get_role(db, access.server.id, role_id)
    if role.managed:
        raise Forbidden("Bot roles are managed by the bot installation", code="managed_role")
    if not role.is_default:
        _require_manage(access, role)
    changes: dict[str, object] = {}
    if name is not None and name != role.name:
        if role.is_default:
            raise AppError("The default role cannot be renamed", code="default_role")
        changes["name"] = {"from": role.name, "to": name}
        role.name = name
    if color_set and color != role.color:
        changes["color"] = {"from": role.color, "to": color}
        role.color = color
    if permission_names is not None:
        bits = to_bits(permission_names)
        # Only permissions you hold can be added *or removed*.
        _require_grantable(access, bits ^ role.permissions)
        if bits != role.permissions:
            changes["permissions"] = {
                "from": perms.permission_names(role.permissions),
                "to": perms.permission_names(bits),
            }
            role.permissions = bits
    if changes:
        audit_service.record(
            db,
            server_id=access.server.id,
            actor_id=actor.id,
            action="role.update",
            target_type="role",
            target_id=role.id,
            details=changes,
        )
    await db.flush()
    return role


async def delete_role(
    db: AsyncSession, access: ServerAccess, actor: User, role_id: uuid.UUID
) -> None:
    access.require(Permission.MANAGE_ROLES)
    role = await _get_role(db, access.server.id, role_id)
    if role.is_default or role.managed:
        raise AppError("This role cannot be deleted", code="default_role")
    _require_manage(access, role)
    await db.delete(role)
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="role.delete",
        target_type="role",
        target_id=role.id,
        details={"name": role.name},
    )


async def move_role(
    db: AsyncSession, access: ServerAccess, actor: User, role_id: uuid.UUID, direction: str
) -> None:
    access.require(Permission.MANAGE_ROLES)
    role = await _get_role(db, access.server.id, role_id)
    if role.is_default:
        raise AppError("The default role is always lowest", code="default_role")
    if direction == "up":
        op, order = ServerRole.position > role.position, ServerRole.position.asc()
    else:
        op, order = ServerRole.position < role.position, ServerRole.position.desc()
    neighbour = await db.scalar(
        select(ServerRole)
        .where(ServerRole.server_id == access.server.id, ServerRole.is_default.is_(False), op)
        .order_by(order)
        .limit(1)
    )
    if neighbour is None:
        return
    # Both roles end up in each other's place, so both must be manageable.
    _require_manage(access, role)
    _require_manage(access, neighbour)
    role.position, neighbour.position = neighbour.position, role.position
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="role.move",
        target_type="role",
        target_id=role.id,
        details={"name": role.name, "direction": direction},
    )
    await db.flush()


async def set_member_role(
    db: AsyncSession,
    access: ServerAccess,
    actor: User,
    user_id: uuid.UUID,
    role_id: uuid.UUID,
    assign: bool,
) -> None:
    access.require(Permission.MANAGE_ROLES)
    role = await _get_role(db, access.server.id, role_id)
    if role.is_default or role.managed:
        raise AppError("This role cannot be assigned manually", code="default_role")
    _require_manage(access, role)
    # Prevent escalation by handing yourself (or a friend) a role with powers you lack.
    _require_grantable(access, role.permissions)
    target = await _target_access(db, access.server, user_id)
    if user_id != actor.id and not perms.can_moderate_member(access.ctx, target.ctx):
        raise Forbidden("Target is not below you", code="hierarchy")
    existing = await db.get(MemberRole, (target.member.id, role.id))
    if assign and existing is None:
        db.add(MemberRole(member_id=target.member.id, role_id=role.id))
    elif not assign and existing is not None:
        await db.delete(existing)
    else:
        return
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="member.role_add" if assign else "member.role_remove",
        target_type="user",
        target_id=user_id,
        details={"role": role.name},
    )
    await db.flush()


# --- Ownership ------------------------------------------------------------------------------


async def transfer_ownership(
    db: AsyncSession, access: ServerAccess, actor: User, new_owner_id: uuid.UUID, password: str
) -> None:
    access.require_owner()
    if not verify_password(actor.password_hash, password):
        raise AppError("Password is wrong", code="invalid_current_password")
    if new_owner_id == actor.id:
        raise AppError("You already own this server", code="already_owner")
    target = await _target_access(db, access.server, new_owner_id)
    target_user = await db.get(User, new_owner_id)
    if target_user is None or target_user.is_bot:
        raise AppError("Bots cannot own servers", code="invalid_owner")
    server = await db.scalar(select(Server).where(Server.id == access.server.id).with_for_update())
    assert server is not None
    if server.owner_id != actor.id:  # changed concurrently
        raise Forbidden("Only the server owner can do this", code="owner_only")
    server.owner_id = target.member.user_id
    audit_service.record(
        db,
        server_id=server.id,
        actor_id=actor.id,
        action="ownership.transfer",
        target_type="user",
        target_id=new_owner_id,
        details={"from": str(actor.id), "to": str(new_owner_id)},
    )
    await db.flush()
