import uuid

from fastapi import APIRouter, Query, status

from app.api.deps import DB, AnyAuth, Auth
from app.core.permissions import Permission, permission_names
from app.models import Channel, Server, ServerRole
from app.realtime import events
from app.realtime.hub import public_user
from app.schemas.server import (
    AuditLogOut,
    ChannelCreate,
    ChannelOut,
    MemberOut,
    RoleOut,
    ServerCreate,
    ServerDelete,
    ServerDetail,
    ServerOut,
    ServerUpdate,
)
from app.services import audit_service, server_service
from app.services.server_service import ServerAccess

router = APIRouter(prefix="/servers", tags=["servers"])


async def _detail(db: DB, access: ServerAccess) -> ServerDetail:
    return ServerDetail(
        **ServerOut.model_validate(access.server).model_dump(),
        member_count=await server_service.member_count(db, access.server.id),
        my_permissions=permission_names(access.permissions),
        is_owner=access.ctx.is_owner,
    )


def _channel_out(channel: Channel, effective: Permission, private: bool) -> ChannelOut:
    out = ChannelOut.model_validate(channel)
    out.my_permissions = permission_names(effective)
    out.is_private = private
    return out


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


@router.get("", response_model=list[ServerOut])
async def list_servers(auth: AnyAuth, db: DB) -> list[Server]:
    return await server_service.list_user_servers(db, auth.user.id)


@router.post("", response_model=ServerDetail, status_code=status.HTTP_201_CREATED)
async def create_server(data: ServerCreate, auth: Auth, db: DB):
    server = await server_service.create_server(db, auth.user, data)
    await db.commit()
    return await _detail(db, await server_service.get_access(db, server.id, auth.user.id))


@router.get("/{server_id}", response_model=ServerDetail)
async def get_server(server_id: uuid.UUID, auth: AnyAuth, db: DB):
    return await _detail(db, await server_service.get_access(db, server_id, auth.user.id))


@router.patch("/{server_id}", response_model=ServerDetail)
async def update_server(server_id: uuid.UUID, data: ServerUpdate, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await server_service.update_server(db, access, auth.user, data)
    await db.commit()
    await events.server_changed(db, server_id, "server")
    return await _detail(db, access)


@router.delete("/{server_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_server(server_id: uuid.UUID, data: ServerDelete, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    member_ids = await server_service.server_member_ids(db, server_id)
    await server_service.delete_server(db, access, data.confirm_name)
    await db.commit()
    events.to_users(member_ids, "server.removed", {"server_id": server_id, "reason": "deleted"})


@router.get("/{server_id}/members", response_model=list[MemberOut])
async def list_members(server_id: uuid.UUID, auth: AnyAuth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return [
        MemberOut(
            id=m.id,
            user=public_user(m.user),
            nickname=m.nickname,
            joined_at=m.joined_at,
            role_ids=role_ids,
            is_owner=m.user_id == access.server.owner_id,
        )
        for m, role_ids in await server_service.list_members(db, access.server)
    ]


@router.get("/{server_id}/roles", response_model=list[RoleOut])
async def list_roles(server_id: uuid.UUID, auth: AnyAuth, db: DB):
    await server_service.get_access(db, server_id, auth.user.id)
    return [_role_out(r) for r in await server_service.list_roles(db, server_id)]


@router.get("/{server_id}/channels", response_model=list[ChannelOut])
async def list_channels(server_id: uuid.UUID, auth: AnyAuth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return [_channel_out(*row) for row in await server_service.list_visible_channels(db, access)]


@router.post(
    "/{server_id}/channels", response_model=ChannelOut, status_code=status.HTTP_201_CREATED
)
async def create_channel(server_id: uuid.UUID, data: ChannelCreate, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    row = await server_service.create_channel(db, access, auth.user, data)
    await db.commit()
    await events.server_changed(db, server_id, "channels")
    return _channel_out(*row)


@router.get("/{server_id}/audit-logs", response_model=list[AuditLogOut])
async def list_audit_logs(
    server_id: uuid.UUID, auth: Auth, db: DB, limit: int = Query(50, ge=1, le=100)
):
    access = await server_service.get_access(db, server_id, auth.user.id)
    access.require(Permission.VIEW_AUDIT_LOG)
    return [
        AuditLogOut(
            id=e.id,
            action=e.action,
            actor=public_user(e.actor) if e.actor else None,
            target_type=e.target_type,
            target_id=e.target_id,
            details=e.details,
            created_at=e.created_at,
        )
        for e in await audit_service.list_for_server(db, server_id, limit)
    ]
