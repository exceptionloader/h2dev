"""Channel management, messages, reactions, pins and attachments."""

import uuid
from typing import Annotated

from fastapi import APIRouter, File, Query, Response, UploadFile

from app.api.deps import DB, AnyAuth, Auth, user_rate_limit
from app.core.config import get_settings
from app.core.errors import NotFound
from app.core.permissions import permission_names
from app.models import Channel
from app.realtime import events
from app.schemas.server import (
    AttachmentOut,
    ChannelOut,
    ChannelUpdate,
    MessageCreate,
    MessageEdit,
    MessageOut,
    OverrideIn,
    OverrideOut,
)
from app.services import channel_service, message_service, server_service, storage

router = APIRouter(tags=["channels"])


@router.get("/channels/{channel_id}", response_model=ChannelOut)
async def get_channel(channel_id: uuid.UUID, auth: AnyAuth, db: DB):
    access, channel, effective = await server_service.get_channel_access(
        db, channel_id, auth.user.id
    )
    _, private = await server_service.channel_permissions_for(db, access, channel)
    out = ChannelOut.model_validate(channel)
    out.my_permissions = permission_names(effective)
    out.is_private = private
    return out


@router.patch("/servers/{server_id}/channels/{channel_id}", response_model=ChannelOut)
async def update_channel(
    server_id: uuid.UUID, channel_id: uuid.UUID, data: ChannelUpdate, auth: Auth, db: DB
):
    access = await server_service.get_access(db, server_id, auth.user.id)
    channel = await channel_service.update_channel(
        db, access, auth.user, channel_id, data.model_dump(exclude_unset=True)
    )
    await db.commit()
    await events.server_changed(db, server_id, "channels")
    return await get_channel(channel.id, auth, db)  # type: ignore[arg-type]


@router.delete("/servers/{server_id}/channels/{channel_id}", status_code=204)
async def delete_channel(server_id: uuid.UUID, channel_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await channel_service.delete_channel(db, access, auth.user, channel_id)
    await db.commit()
    await events.server_changed(db, server_id, "channels", "spaces")


@router.get(
    "/servers/{server_id}/channels/{channel_id}/overrides", response_model=list[OverrideOut]
)
async def list_overrides(server_id: uuid.UUID, channel_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return [
        OverrideOut(
            role_id=o.role_id,
            member_id=o.member_id,
            allow=permission_names(o.allow),
            deny=permission_names(o.deny),
        )
        for o in await channel_service.list_overrides(db, access, channel_id)
    ]


@router.put("/servers/{server_id}/channels/{channel_id}/overrides/roles/{role_id}", status_code=204)
async def set_role_override(
    server_id: uuid.UUID,
    channel_id: uuid.UUID,
    role_id: uuid.UUID,
    data: OverrideIn,
    auth: Auth,
    db: DB,
):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await channel_service.set_role_override(
        db, access, auth.user, channel_id, role_id, data.allow, data.deny
    )
    await db.commit()
    await events.server_changed(db, server_id, "channels")


# --- Messages -------------------------------------------------------------------------------


async def _one(db: DB, message, channel: Channel) -> MessageOut:  # noqa: ANN001
    return (await message_service.serialize(db, [message], channel.server_id))[0]


@router.get("/channels/{channel_id}/messages", response_model=list[MessageOut])
async def list_messages(
    channel_id: uuid.UUID,
    auth: AnyAuth,
    db: DB,
    before: uuid.UUID | None = None,
    limit: int = Query(50, ge=1, le=100),
):
    _, channel, _ = await server_service.get_channel_access(db, channel_id, auth.user.id)
    messages = await message_service.list_messages(db, channel, before, limit)
    return await message_service.serialize(db, messages, channel.server_id)


@router.get("/channels/{channel_id}/pins", response_model=list[MessageOut])
async def list_pins(channel_id: uuid.UUID, auth: AnyAuth, db: DB):
    _, channel, _ = await server_service.get_channel_access(db, channel_id, auth.user.id)
    return await message_service.serialize(
        db, await message_service.list_pins(db, channel), channel.server_id
    )


@router.post("/channels/{channel_id}/messages", response_model=MessageOut, status_code=201)
async def create_message(channel_id: uuid.UUID, data: MessageCreate, auth: AnyAuth, db: DB):
    user_rate_limit("messages", get_settings().rate_limit_messages, auth.user.id)
    _, channel, effective = await server_service.get_channel_access(db, channel_id, auth.user.id)
    message = await message_service.create_message(
        db, channel, effective, auth.user, data.content, data.reply_to_id, data.attachment_ids
    )
    await db.commit()
    out = await _one(db, message, channel)
    await events.to_channel(db, channel, "message.created", out.model_dump(mode="json"))
    return out


@router.patch("/messages/{message_id}", response_model=MessageOut)
async def edit_message(message_id: uuid.UUID, data: MessageEdit, auth: AnyAuth, db: DB):
    message, channel, _ = await message_service.edit_message(
        db, message_id, auth.user, data.content
    )
    await db.commit()
    out = await _one(db, message, channel)
    await events.to_channel(db, channel, "message.updated", out.model_dump(mode="json"))
    return out


@router.delete("/messages/{message_id}", status_code=204)
async def delete_message(message_id: uuid.UUID, auth: AnyAuth, db: DB):
    message, channel = await message_service.delete_message(db, message_id, auth.user)
    await db.commit()
    await events.to_channel(
        db, channel, "message.deleted", {"id": message_id, "channel_id": channel.id}
    )


@router.put("/messages/{message_id}/pin", response_model=MessageOut)
async def pin(message_id: uuid.UUID, auth: AnyAuth, db: DB):
    message, channel, _ = await message_service.set_pinned(db, message_id, auth.user, True)
    await db.commit()
    out = await _one(db, message, channel)
    await events.to_channel(db, channel, "message.updated", out.model_dump(mode="json"))
    return out


@router.delete("/messages/{message_id}/pin", response_model=MessageOut)
async def unpin(message_id: uuid.UUID, auth: AnyAuth, db: DB):
    message, channel, _ = await message_service.set_pinned(db, message_id, auth.user, False)
    await db.commit()
    out = await _one(db, message, channel)
    await events.to_channel(db, channel, "message.updated", out.model_dump(mode="json"))
    return out


@router.put("/messages/{message_id}/reactions/{emoji}", response_model=MessageOut)
async def add_reaction(message_id: uuid.UUID, emoji: str, auth: AnyAuth, db: DB):
    message, channel, _ = await message_service.react(db, message_id, auth.user, emoji, True)
    await db.commit()
    out = await _one(db, message, channel)
    await events.to_channel(db, channel, "message.updated", out.model_dump(mode="json"))
    return out


@router.delete("/messages/{message_id}/reactions/{emoji}", response_model=MessageOut)
async def remove_reaction(message_id: uuid.UUID, emoji: str, auth: AnyAuth, db: DB):
    message, channel, _ = await message_service.react(db, message_id, auth.user, emoji, False)
    await db.commit()
    out = await _one(db, message, channel)
    await events.to_channel(db, channel, "message.updated", out.model_dump(mode="json"))
    return out


# --- Attachments ----------------------------------------------------------------------------


@router.post("/channels/{channel_id}/attachments", response_model=AttachmentOut, status_code=201)
async def upload_attachment(
    channel_id: uuid.UUID, file: Annotated[UploadFile, File()], auth: AnyAuth, db: DB
):
    user_rate_limit("uploads", get_settings().rate_limit_uploads, auth.user.id)
    _, channel, effective = await server_service.get_channel_access(db, channel_id, auth.user.id)
    data = await storage.read_limited(file, message_service.max_attachment_bytes())
    att = await message_service.upload_attachment(
        db, channel, effective, auth.user, data, file.filename
    )
    await db.commit()
    return AttachmentOut(
        id=att.id,
        filename=att.filename,
        content_type=att.content_type,
        size=att.size,
        is_image=att.is_image,
        url=message_service.attachment_url(att),
    )


def file_response(data: bytes, content_type: str, filename: str, inline: bool) -> Response:
    disposition = "inline" if inline else "attachment"
    ascii_name = filename.encode("ascii", "replace").decode().replace('"', "")
    return Response(
        content=data,
        media_type=content_type,
        headers={
            "Content-Disposition": f'{disposition}; filename="{ascii_name}"',
            # User content must never execute: no scripts, no plugins, own origin sandbox.
            "Content-Security-Policy": "default-src 'none'; img-src 'self'; sandbox",
            "Cache-Control": "private, max-age=86400",
        },
    )


@router.get("/attachments/{attachment_id}/{filename}")
async def download_attachment(attachment_id: uuid.UUID, filename: str, auth: AnyAuth, db: DB):
    att = await message_service.get_attachment_for_download(db, attachment_id, auth.user)
    data = await storage.get_storage().load(att.storage_key)
    if data is None:
        raise NotFound("File not found", code="file_not_found")
    return file_response(data, att.content_type, att.filename, inline=att.is_image)
