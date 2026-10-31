"""Channel messages, replies, reactions, pins and attachments."""

import uuid
from collections import defaultdict

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.errors import AppError, Forbidden, NotFound
from app.core.permissions import Permission
from app.models import Attachment, Channel, Message, MessageReaction, User
from app.models.base import utcnow
from app.realtime.hub import public_user
from app.schemas.server import AttachmentOut, MessageOut, ReactionOut, ReplyPreview
from app.services import audit_service, server_service, storage
from app.services.server_service import ServerAccess, require_channel

PAGE_SIZE = 50
MAX_DISTINCT_REACTIONS = 20
WRITABLE_TYPES = ("text", "announcement")


def attachment_url(att: Attachment) -> str:
    return f"/api/v1/attachments/{att.id}/{att.filename}"


async def serialize(
    db: AsyncSession, messages: list[Message], server_id: uuid.UUID
) -> list[MessageOut]:
    if not messages:
        return []
    ids = [m.id for m in messages]
    reply_ids = {m.reply_to_id for m in messages if m.reply_to_id}
    replies = (
        {r.id: r for r in await db.scalars(select(Message).where(Message.id.in_(reply_ids)))}
        if reply_ids
        else {}
    )
    author_ids = {m.author_id for m in messages if m.author_id} | {
        r.author_id for r in replies.values() if r.author_id
    }
    users = {u.id: u for u in await db.scalars(select(User).where(User.id.in_(author_ids)))}

    reactions: dict[uuid.UUID, dict[str, list[uuid.UUID]]] = defaultdict(dict)
    for r in await db.scalars(
        select(MessageReaction)
        .where(MessageReaction.message_id.in_(ids))
        .order_by(MessageReaction.created_at)
    ):
        reactions[r.message_id].setdefault(r.emoji, []).append(r.user_id)

    attachments: dict[uuid.UUID, list[Attachment]] = defaultdict(list)
    for a in await db.scalars(
        select(Attachment).where(Attachment.message_id.in_(ids)).order_by(Attachment.created_at)
    ):
        attachments[a.message_id].append(a)  # type: ignore[index]

    def user_out(user_id: uuid.UUID | None):
        user = users.get(user_id) if user_id else None
        return public_user(user) if user else None

    out = []
    for m in messages:
        reply = replies.get(m.reply_to_id) if m.reply_to_id else None
        out.append(
            MessageOut(
                id=m.id,
                channel_id=m.channel_id,
                server_id=server_id,
                author=user_out(m.author_id),
                content=m.content,
                created_at=m.created_at,
                edited_at=m.edited_at,
                pinned=m.pinned_at is not None,
                reply_to=ReplyPreview(
                    id=reply.id, author=user_out(reply.author_id), content=reply.content[:200]
                )
                if reply
                else None,
                reactions=[
                    ReactionOut(emoji=e, user_ids=u) for e, u in reactions.get(m.id, {}).items()
                ],
                attachments=[
                    AttachmentOut(
                        id=a.id,
                        filename=a.filename,
                        content_type=a.content_type,
                        size=a.size,
                        is_image=a.is_image,
                        url=attachment_url(a),
                    )
                    for a in attachments.get(m.id, [])
                ],
            )
        )
    return out


async def list_messages(
    db: AsyncSession, channel: Channel, before: uuid.UUID | None, limit: int = PAGE_SIZE
) -> list[Message]:
    stmt = select(Message).where(Message.channel_id == channel.id)
    if before:
        anchor = await db.get(Message, before)
        if anchor is None or anchor.channel_id != channel.id:
            raise NotFound("Message not found", code="message_not_found")
        stmt = stmt.where(
            (Message.created_at < anchor.created_at)
            | ((Message.created_at == anchor.created_at) & (Message.id < anchor.id))
        )
    rows = list(
        await db.scalars(stmt.order_by(Message.created_at.desc(), Message.id.desc()).limit(limit))
    )
    rows.reverse()
    return rows


async def list_pins(db: AsyncSession, channel: Channel) -> list[Message]:
    return list(
        await db.scalars(
            select(Message)
            .where(Message.channel_id == channel.id, Message.pinned_at.is_not(None))
            .order_by(Message.pinned_at.desc())
            .limit(50)
        )
    )


async def create_message(
    db: AsyncSession,
    channel: Channel,
    effective: Permission,
    author: User,
    content: str,
    reply_to_id: uuid.UUID | None,
    attachment_ids: list[uuid.UUID],
) -> Message:
    if channel.type not in WRITABLE_TYPES:
        raise AppError("You cannot post in this channel type", code="not_text_channel")
    require_channel(effective, Permission.SEND_MESSAGES)
    content = content.strip()
    if not content and not attachment_ids:
        raise AppError("Message is empty", code="message_empty")
    if attachment_ids:
        require_channel(effective, Permission.ATTACH_FILES)
    if reply_to_id:
        parent = await db.get(Message, reply_to_id)
        if parent is None or parent.channel_id != channel.id:
            raise AppError("Reply target not in this channel", code="invalid_reply")

    message = Message(
        channel_id=channel.id, author_id=author.id, content=content, reply_to_id=reply_to_id
    )
    db.add(message)
    await db.flush()
    if attachment_ids:
        found = list(
            await db.scalars(
                select(Attachment).where(
                    Attachment.id.in_(attachment_ids),
                    Attachment.uploader_id == author.id,
                    Attachment.channel_id == channel.id,
                    Attachment.message_id.is_(None),
                )
            )
        )
        if len(found) != len(set(attachment_ids)):
            raise AppError("Invalid attachment", code="invalid_attachment")
        for att in found:
            att.message_id = message.id
    await db.flush()
    return message


async def _message_in_access(
    db: AsyncSession, message_id: uuid.UUID, user_id: uuid.UUID
) -> tuple[Message, Channel, ServerAccess, Permission]:
    message = await db.get(Message, message_id)
    if message is None:
        raise NotFound("Message not found", code="message_not_found")
    try:
        access, channel, effective = await server_service.get_channel_access(
            db, message.channel_id, user_id
        )
    except NotFound as exc:
        raise NotFound("Message not found", code="message_not_found") from exc
    return message, channel, access, effective


async def edit_message(
    db: AsyncSession, message_id: uuid.UUID, user: User, content: str
) -> tuple[Message, Channel, ServerAccess]:
    message, channel, access, effective = await _message_in_access(db, message_id, user.id)
    if message.author_id != user.id:
        raise Forbidden("You can only edit your own messages", code="not_author")
    require_channel(effective, Permission.SEND_MESSAGES)
    message.content = content.strip()
    message.edited_at = utcnow()
    await db.flush()
    return message, channel, access


async def delete_message(
    db: AsyncSession, message_id: uuid.UUID, user: User
) -> tuple[Message, Channel]:
    message, channel, access, effective = await _message_in_access(db, message_id, user.id)
    if message.author_id != user.id:
        require_channel(effective, Permission.MANAGE_MESSAGES)
        audit_service.record(
            db,
            server_id=channel.server_id,
            actor_id=user.id,
            action="message.delete",
            target_type="message",
            target_id=message.id,
            details={
                "channel": channel.name,
                "author_id": str(message.author_id),
                "excerpt": message.content[:100],
            },
        )
    keys = list(
        await db.scalars(select(Attachment.storage_key).where(Attachment.message_id == message.id))
    )
    await db.delete(message)
    await db.flush()
    store = storage.get_storage()
    for key in keys:
        await store.delete(key)
    return message, channel


async def set_pinned(
    db: AsyncSession, message_id: uuid.UUID, user: User, pinned: bool
) -> tuple[Message, Channel, ServerAccess]:
    message, channel, access, effective = await _message_in_access(db, message_id, user.id)
    require_channel(effective, Permission.MANAGE_MESSAGES)
    if pinned and message.pinned_at is None:
        count = await db.scalar(
            select(func.count()).where(
                Message.channel_id == channel.id, Message.pinned_at.is_not(None)
            )
        )
        if (count or 0) >= 50:
            raise AppError("Too many pinned messages", code="pin_limit_reached")
        message.pinned_at = utcnow()
    elif not pinned:
        message.pinned_at = None
    audit_service.record(
        db,
        server_id=channel.server_id,
        actor_id=user.id,
        action="message.pin" if pinned else "message.unpin",
        target_type="message",
        target_id=message.id,
        details={"channel": channel.name},
    )
    await db.flush()
    return message, channel, access


def _valid_emoji(emoji: str) -> bool:
    return 0 < len(emoji) <= 16 and not any(c.isspace() or c in "<>&\"'/\\" for c in emoji)


async def react(
    db: AsyncSession, message_id: uuid.UUID, user: User, emoji: str, add: bool
) -> tuple[Message, Channel, ServerAccess]:
    if not _valid_emoji(emoji):
        raise AppError("Invalid emoji", code="invalid_emoji")
    message, channel, access, effective = await _message_in_access(db, message_id, user.id)
    if add:
        require_channel(effective, Permission.ADD_REACTIONS)
        if await db.get(MessageReaction, (message.id, user.id, emoji)) is None:
            distinct = await db.scalar(
                select(func.count(func.distinct(MessageReaction.emoji))).where(
                    MessageReaction.message_id == message.id
                )
            )
            existing = await db.scalar(
                select(MessageReaction.emoji)
                .where(MessageReaction.message_id == message.id, MessageReaction.emoji == emoji)
                .limit(1)
            )
            if existing is None and (distinct or 0) >= MAX_DISTINCT_REACTIONS:
                raise AppError("Too many different reactions", code="reaction_limit_reached")
            db.add(MessageReaction(message_id=message.id, user_id=user.id, emoji=emoji))
    else:
        await db.execute(
            delete(MessageReaction).where(
                MessageReaction.message_id == message.id,
                MessageReaction.user_id == user.id,
                MessageReaction.emoji == emoji,
            )
        )
    await db.flush()
    return message, channel, access


async def upload_attachment(
    db: AsyncSession,
    channel: Channel,
    effective: Permission,
    user: User,
    data: bytes,
    filename: str | None,
) -> Attachment:
    if channel.type not in WRITABLE_TYPES:
        raise AppError("You cannot post in this channel type", code="not_text_channel")
    require_channel(effective, Permission.SEND_MESSAGES)
    require_channel(effective, Permission.ATTACH_FILES)
    image_type = storage.sniff_image(data)
    name = storage.safe_filename(filename)
    content_type = image_type or "application/octet-stream"
    ext = storage.IMAGE_TYPES.get(image_type) if image_type else None
    key = storage.new_key("attachments", ext)
    await storage.get_storage().save(key, data, content_type)
    att = Attachment(
        channel_id=channel.id,
        uploader_id=user.id,
        storage_key=key,
        filename=name,
        content_type=content_type,
        size=len(data),
        is_image=image_type is not None,
    )
    db.add(att)
    await db.flush()
    return att


async def get_attachment_for_download(
    db: AsyncSession, attachment_id: uuid.UUID, user: User
) -> Attachment:
    att = await db.get(Attachment, attachment_id)
    if att is None:
        raise NotFound("File not found", code="file_not_found")
    if att.message_id is None:
        if att.uploader_id != user.id:
            raise NotFound("File not found", code="file_not_found")
        return att
    try:
        await server_service.get_channel_access(db, att.channel_id, user.id)
    except NotFound as exc:
        raise NotFound("File not found", code="file_not_found") from exc
    return att


def max_attachment_bytes() -> int:
    return get_settings().max_attachment_bytes
