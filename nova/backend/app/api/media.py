"""Avatars and server icons: upload and serve."""

import uuid
from typing import Annotated

from fastapi import APIRouter, File, UploadFile

from app.api.channels import file_response
from app.api.deps import DB, AnyAuth, Auth, user_rate_limit
from app.core.config import get_settings
from app.core.errors import AppError, NotFound
from app.core.permissions import Permission
from app.realtime import events
from app.schemas.server import ServerOut
from app.schemas.user import UserMe
from app.services import server_service, storage

router = APIRouter(tags=["media"])


async def _store_image(file: UploadFile, prefix: str) -> str:
    data = await storage.read_limited(file, get_settings().max_avatar_bytes)
    content_type = storage.sniff_image(data)
    if content_type is None:
        raise AppError("Only PNG, JPEG, GIF or WebP images", code="invalid_image")
    key = storage.new_key(prefix, storage.IMAGE_TYPES[content_type])
    await storage.get_storage().save(key, data, content_type)
    return f"/api/v1/media/{key}"


async def _delete_old(url: str | None) -> None:
    if url and url.startswith("/api/v1/media/"):
        key = url.removeprefix("/api/v1/media/")
        if storage.valid_key(key):
            await storage.get_storage().delete(key)


@router.post("/users/me/avatar", response_model=UserMe)
async def upload_avatar(file: Annotated[UploadFile, File()], auth: Auth, db: DB):
    user_rate_limit("uploads", get_settings().rate_limit_uploads, auth.user.id)
    url = await _store_image(file, "avatars")
    old, auth.user.avatar_url = auth.user.avatar_url, url
    await db.commit()
    await _delete_old(old)
    await events.presence_changed(db, auth.user)
    return auth.user


@router.delete("/users/me/avatar", response_model=UserMe)
async def delete_avatar(auth: Auth, db: DB):
    old, auth.user.avatar_url = auth.user.avatar_url, None
    await db.commit()
    await _delete_old(old)
    return auth.user


@router.post("/servers/{server_id}/icon", response_model=ServerOut)
async def upload_server_icon(
    server_id: uuid.UUID, file: Annotated[UploadFile, File()], auth: Auth, db: DB
):
    user_rate_limit("uploads", get_settings().rate_limit_uploads, auth.user.id)
    access = await server_service.get_access(db, server_id, auth.user.id)
    access.require(Permission.MANAGE_SERVER)
    url = await _store_image(file, "icons")
    old, access.server.icon_url = access.server.icon_url, url
    await db.commit()
    await _delete_old(old)
    await events.server_changed(db, server_id, "server")
    return access.server


@router.get("/media/{prefix}/{name}")
async def get_media(prefix: str, name: str, auth: AnyAuth):
    key = f"{prefix}/{name}"
    if prefix not in ("avatars", "icons") or not storage.valid_key(key):
        raise NotFound("File not found", code="file_not_found")
    data = await storage.get_storage().load(key)
    content_type = storage.sniff_image(data) if data else None
    if data is None or content_type is None:
        raise NotFound("File not found", code="file_not_found")
    return file_response(data, content_type, name, inline=True)
