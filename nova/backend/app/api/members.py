"""Invites, joining/leaving, kick/ban, roles, ownership transfer and discovery."""

import uuid

from fastapi import APIRouter, Query, status

from app.api.deps import DB, Auth, user_rate_limit
from app.core.config import get_settings
from app.core.permissions import permission_names
from app.models import ServerInvite, ServerRole
from app.realtime import events
from app.realtime.hub import public_user
from app.schemas.server import (
    BanCreate,
    BanOut,
    DiscoverServer,
    InviteCreate,
    InviteOut,
    InvitePreview,
    OwnershipTransfer,
    RoleCreate,
    RoleMove,
    RoleOut,
    RoleUpdate,
    ServerOut,
)
from app.services import member_service, server_service

router = APIRouter(tags=["members"])


def _role_out(role: ServerRole) -> RoleOut:
    return RoleOut(
        id=role.id,
        name=role.name,
        color=role.color,
        icon=role.icon,
        position=role.position,
        permissions=permission_names(role.permissions),
        is_default=role.is_default,
    )


def _invite_out(invite: ServerInvite, with_creator: bool = True) -> InviteOut:
    return InviteOut(
        code=invite.code,
        server_id=invite.server_id,
        creator=public_user(invite.creator) if with_creator and invite.creator else None,
        max_uses=invite.max_uses,
        uses=invite.uses,
        expires_at=invite.expires_at,
        created_at=invite.created_at,
    )


# --- Invites ----------------------------------------------------------------------------


@router.post("/servers/{server_id}/invites", response_model=InviteOut, status_code=201)
async def create_invite(server_id: uuid.UUID, data: InviteCreate, auth: Auth, db: DB):
    user_rate_limit("invites", get_settings().rate_limit_invites, auth.user.id)
    access = await server_service.get_access(db, server_id, auth.user.id)
    invite = await member_service.create_invite(
        db, access, auth.user, data.max_uses, data.expires_in_hours
    )
    await db.commit()
    return _invite_out(invite, with_creator=False)


@router.get("/servers/{server_id}/invites", response_model=list[InviteOut])
async def list_invites(server_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return [_invite_out(i) for i in await member_service.list_invites(db, access, auth.user)]


@router.delete("/invites/{code}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_invite(code: str, auth: Auth, db: DB):
    await member_service.delete_invite(db, code, auth.user)
    await db.commit()


@router.get("/invites/{code}", response_model=InvitePreview)
async def preview_invite(code: str, auth: Auth, db: DB):
    invite, server = await member_service.get_invite(db, code)
    member_ids = await server_service.server_member_ids(db, server.id)
    return InvitePreview(
        code=invite.code,
        server=ServerOut.model_validate(server),
        member_count=len(member_ids),
        already_member=auth.user.id in member_ids,
        expires_at=invite.expires_at,
    )


@router.post("/invites/{code}/accept", response_model=ServerOut)
async def accept_invite(code: str, auth: Auth, db: DB):
    user_rate_limit("invite-accept", get_settings().rate_limit_invites, auth.user.id)
    server = await member_service.accept_invite(db, code, auth.user)
    await db.commit()
    await _member_joined(db, server.id, auth.user)
    return server


async def _member_joined(db: DB, server_id: uuid.UUID, user) -> None:  # noqa: ANN001
    await events.to_server(
        db, server_id, "member.joined", {"server_id": server_id, "user": public_user(user)}
    )
    await events.server_changed(db, server_id, "members")


# --- Discovery & public join --------------------------------------------------------------


@router.get("/discover/servers", response_model=list[DiscoverServer])
async def discover(auth: Auth, db: DB, q: str | None = Query(None, max_length=64)):
    return [
        DiscoverServer(
            **ServerOut.model_validate(server).model_dump(), member_count=count, is_member=is_member
        )
        for server, count, is_member in await member_service.discover(db, auth.user, q)
    ]


@router.post("/servers/{server_id}/join", response_model=ServerOut)
async def join_public(server_id: uuid.UUID, auth: Auth, db: DB):
    server = await member_service.join_public(db, server_id, auth.user)
    await db.commit()
    await _member_joined(db, server.id, auth.user)
    return server


# --- Leave / kick / ban ----------------------------------------------------------------------


async def _removed(db: DB, server_id: uuid.UUID, user_id: uuid.UUID, reason: str) -> None:
    events.to_users([user_id], "server.removed", {"server_id": server_id, "reason": reason})
    await events.server_changed(db, server_id, "members", "voice")


@router.delete("/servers/{server_id}/members/@me", status_code=204)
async def leave(server_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.leave(db, access, auth.user)
    await db.commit()
    await _removed(db, server_id, auth.user.id, "left")


@router.delete("/servers/{server_id}/members/{user_id}", status_code=204)
async def kick(server_id: uuid.UUID, user_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.kick(db, access, auth.user, user_id)
    await db.commit()
    await _removed(db, server_id, user_id, "kicked")


@router.get("/servers/{server_id}/bans", response_model=list[BanOut])
async def list_bans(server_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return [
        BanOut(user=public_user(b.user), reason=b.reason, created_at=b.created_at)
        for b in await member_service.list_bans(db, access)
    ]


@router.post("/servers/{server_id}/bans", status_code=204)
async def ban(server_id: uuid.UUID, data: BanCreate, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.ban(db, access, auth.user, data.user_id, data.reason)
    await db.commit()
    await _removed(db, server_id, data.user_id, "banned")


@router.delete("/servers/{server_id}/bans/{user_id}", status_code=204)
async def unban(server_id: uuid.UUID, user_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.unban(db, access, auth.user, user_id)
    await db.commit()


# --- Roles ---------------------------------------------------------------------------------


@router.post("/servers/{server_id}/roles", response_model=RoleOut, status_code=201)
async def create_role(server_id: uuid.UUID, data: RoleCreate, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    role = await member_service.create_role(
        db, access, auth.user, data.name, data.color, data.permissions
    )
    await db.commit()
    await events.server_changed(db, server_id, "roles")
    return _role_out(role)


@router.patch("/servers/{server_id}/roles/{role_id}", response_model=RoleOut)
async def update_role(
    server_id: uuid.UUID, role_id: uuid.UUID, data: RoleUpdate, auth: Auth, db: DB
):
    access = await server_service.get_access(db, server_id, auth.user.id)
    role = await member_service.update_role(
        db,
        access,
        auth.user,
        role_id,
        data.name,
        data.color,
        "color" in data.model_fields_set,
        data.permissions,
    )
    await db.commit()
    await events.server_changed(db, server_id, "roles", "server", "channels")
    return _role_out(role)


@router.delete("/servers/{server_id}/roles/{role_id}", status_code=204)
async def delete_role(server_id: uuid.UUID, role_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.delete_role(db, access, auth.user, role_id)
    await db.commit()
    await events.server_changed(db, server_id, "roles", "members", "server", "channels")


@router.post("/servers/{server_id}/roles/{role_id}/move", status_code=204)
async def move_role(server_id: uuid.UUID, role_id: uuid.UUID, data: RoleMove, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.move_role(db, access, auth.user, role_id, data.direction)
    await db.commit()
    await events.server_changed(db, server_id, "roles", "members")


@router.put("/servers/{server_id}/members/{user_id}/roles/{role_id}", status_code=204)
async def add_member_role(
    server_id: uuid.UUID, user_id: uuid.UUID, role_id: uuid.UUID, auth: Auth, db: DB
):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.set_member_role(db, access, auth.user, user_id, role_id, assign=True)
    await db.commit()
    await events.server_changed(db, server_id, "members", "server", "channels")


@router.delete("/servers/{server_id}/members/{user_id}/roles/{role_id}", status_code=204)
async def remove_member_role(
    server_id: uuid.UUID, user_id: uuid.UUID, role_id: uuid.UUID, auth: Auth, db: DB
):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.set_member_role(db, access, auth.user, user_id, role_id, assign=False)
    await db.commit()
    await events.server_changed(db, server_id, "members", "server", "channels")


@router.post("/servers/{server_id}/transfer-ownership", status_code=204)
async def transfer_ownership(server_id: uuid.UUID, data: OwnershipTransfer, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await member_service.transfer_ownership(db, access, auth.user, data.new_owner_id, data.password)
    await db.commit()
    await events.server_changed(db, server_id, "server", "members", "channels")
