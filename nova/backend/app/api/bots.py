import uuid

from fastapi import APIRouter

from app.api.deps import DB, Auth
from app.core.permissions import permission_names
from app.realtime import events
from app.schemas.social import (
    AvailableBot,
    BotCreate,
    BotInstallationOut,
    BotInstallIn,
    BotOut,
    BotUpdate,
    BotWithToken,
)
from app.services import bot_service, server_service

router = APIRouter(tags=["bots"])


@router.get("/bots", response_model=list[BotOut])
async def my_bots(auth: Auth, db: DB):
    return [bot_service.bot_out(a, u) for a, u in await bot_service.list_my_bots(db, auth.user)]


@router.post("/bots", response_model=BotWithToken, status_code=201)
async def create_bot(data: BotCreate, auth: Auth, db: DB):
    app, user, token = await bot_service.create_bot(db, auth.user, data)
    await db.commit()
    return BotWithToken(**bot_service.bot_out(app, user).model_dump(), token=token)


@router.patch("/bots/{bot_id}", response_model=BotOut)
async def update_bot(bot_id: uuid.UUID, data: BotUpdate, auth: Auth, db: DB):
    app, user = await bot_service.update_bot(
        db, auth.user, bot_id, data.model_dump(exclude_unset=True)
    )
    await db.commit()
    return bot_service.bot_out(app, user)


@router.post("/bots/{bot_id}/token", response_model=BotWithToken)
async def regenerate_token(bot_id: uuid.UUID, auth: Auth, db: DB):
    app, user, token = await bot_service.regenerate_token(db, auth.user, bot_id)
    await db.commit()
    return BotWithToken(**bot_service.bot_out(app, user).model_dump(), token=token)


@router.delete("/bots/{bot_id}", status_code=204)
async def delete_bot(bot_id: uuid.UUID, auth: Auth, db: DB):
    await bot_service.delete_bot(db, auth.user, bot_id)
    await db.commit()


@router.get("/servers/{server_id}/bots", response_model=list[BotInstallationOut])
async def installed_bots(server_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return [
        BotInstallationOut(
            bot=bot_service.bot_out(app, user),
            permissions=permission_names(inst.permissions),
            installed_at=inst.created_at,
        )
        for inst, app, user in await bot_service.installations(db, access)
    ]


@router.get("/servers/{server_id}/bots/available", response_model=list[AvailableBot])
async def available_bots(server_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return [
        AvailableBot(**bot_service.bot_out(a, u).model_dump(), installed=installed)
        for a, u, installed in await bot_service.available_bots(db, access, auth.user)
    ]


@router.post("/servers/{server_id}/bots", status_code=204)
async def install_bot(server_id: uuid.UUID, data: BotInstallIn, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await bot_service.install(db, access, auth.user, data.bot_id, data.permissions)
    await db.commit()
    await events.server_changed(db, server_id, "members", "roles", "bots")


@router.delete("/servers/{server_id}/bots/{bot_id}", status_code=204)
async def uninstall_bot(server_id: uuid.UUID, bot_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    bot_user_id = await bot_service.uninstall(db, access, auth.user, bot_id)
    await db.commit()
    events.to_users(
        [bot_user_id], "server.removed", {"server_id": server_id, "reason": "uninstalled"}
    )
    await events.server_changed(db, server_id, "members", "roles", "bots")
