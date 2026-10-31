"""Friends, blocks and direct messages."""

import uuid

from sqlalchemy import and_, delete, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import AppError, Conflict, Forbidden, NotFound
from app.models import DirectMessage, DMChannel, Friendship, User, UserBlock
from app.models.base import utcnow
from app.realtime.hub import public_user
from app.schemas.social import DirectMessageOut, FriendRequestOut, FriendsOut

DM_PAGE = 50


def _pair(a: uuid.UUID, b: uuid.UUID):
    return or_(
        and_(Friendship.requester_id == a, Friendship.addressee_id == b),
        and_(Friendship.requester_id == b, Friendship.addressee_id == a),
    )


async def is_blocked_between(db: AsyncSession, a: uuid.UUID, b: uuid.UUID) -> bool:
    return bool(
        await db.scalar(
            select(UserBlock.blocker_id).where(
                or_(
                    and_(UserBlock.blocker_id == a, UserBlock.blocked_id == b),
                    and_(UserBlock.blocker_id == b, UserBlock.blocked_id == a),
                )
            )
        )
    )


async def are_friends(db: AsyncSession, a: uuid.UUID, b: uuid.UUID) -> bool:
    return bool(
        await db.scalar(select(Friendship.id).where(_pair(a, b), Friendship.status == "accepted"))
    )


async def friends_overview(db: AsyncSession, user: User) -> FriendsOut:
    rows = list(
        await db.scalars(
            select(Friendship).where(
                or_(Friendship.requester_id == user.id, Friendship.addressee_id == user.id)
            )
        )
    )
    blocked_ids = list(
        await db.scalars(select(UserBlock.blocked_id).where(UserBlock.blocker_id == user.id))
    )
    other_ids = {r.addressee_id if r.requester_id == user.id else r.requester_id for r in rows}
    users = {
        u.id: u
        for u in await db.scalars(select(User).where(User.id.in_(other_ids | set(blocked_ids))))
    }
    friends, incoming, outgoing = [], [], []
    for r in rows:
        other = users[r.addressee_id if r.requester_id == user.id else r.requester_id]
        if r.status == "accepted":
            friends.append(public_user(other))
        elif r.addressee_id == user.id:
            incoming.append(
                FriendRequestOut(id=r.id, user=public_user(other), created_at=r.created_at)
            )
        else:
            outgoing.append(
                FriendRequestOut(id=r.id, user=public_user(other), created_at=r.created_at)
            )
    friends.sort(key=lambda u: (u.presence == "offline", u.display_name.lower()))
    return FriendsOut(
        friends=friends,
        incoming=incoming,
        outgoing=outgoing,
        blocked=[public_user(users[b]) for b in blocked_ids if b in users],
    )


async def send_request(db: AsyncSession, user: User, username: str) -> tuple[Friendship, User]:
    target = await db.scalar(select(User).where(User.username == username.strip().lower()))
    # Same answer for "doesn't exist", "is a bot" and "blocked" — no information leaks.
    if (
        target is None
        or target.is_bot
        or not target.is_active
        or await is_blocked_between(db, user.id, target.id)
    ):
        raise NotFound("User not found", code="user_not_found")
    if target.id == user.id:
        raise AppError("You cannot add yourself", code="cannot_add_self")
    existing = await db.scalar(select(Friendship).where(_pair(user.id, target.id)))
    if existing:
        if existing.status == "accepted":
            raise Conflict("Already friends", code="already_friends")
        if existing.requester_id == user.id:
            raise Conflict("Request already sent", code="request_pending")
        existing.status, existing.accepted_at = "accepted", utcnow()  # they asked first
        await db.flush()
        return existing, target
    request = Friendship(requester_id=user.id, addressee_id=target.id)
    db.add(request)
    try:
        async with db.begin_nested():
            await db.flush()
    except IntegrityError as exc:
        raise Conflict("Request already exists", code="request_pending") from exc
    return request, target


async def accept_request(db: AsyncSession, user: User, request_id: uuid.UUID) -> Friendship:
    request = await db.get(Friendship, request_id)
    if request is None or request.addressee_id != user.id or request.status != "pending":
        raise NotFound("Request not found", code="request_not_found")
    request.status, request.accepted_at = "accepted", utcnow()
    await db.flush()
    return request


async def delete_request(db: AsyncSession, user: User, request_id: uuid.UUID) -> Friendship:
    request = await db.get(Friendship, request_id)
    if (
        request is None
        or request.status != "pending"
        or user.id not in (request.requester_id, request.addressee_id)
    ):
        raise NotFound("Request not found", code="request_not_found")
    await db.delete(request)
    await db.flush()
    return request


async def remove_friend(db: AsyncSession, user: User, other_id: uuid.UUID) -> None:
    result = await db.execute(
        delete(Friendship).where(_pair(user.id, other_id), Friendship.status == "accepted")
    )
    if not result.rowcount:  # type: ignore[attr-defined]
        raise NotFound("Not friends", code="not_friends")


async def block(db: AsyncSession, user: User, other_id: uuid.UUID) -> None:
    if other_id == user.id:
        raise AppError("You cannot block yourself", code="cannot_block_self")
    if await db.get(User, other_id) is None:
        raise NotFound("User not found", code="user_not_found")
    await db.execute(delete(Friendship).where(_pair(user.id, other_id)))
    if await db.get(UserBlock, (user.id, other_id)) is None:
        db.add(UserBlock(blocker_id=user.id, blocked_id=other_id))
    await db.flush()


async def unblock(db: AsyncSession, user: User, other_id: uuid.UUID) -> None:
    await db.execute(
        delete(UserBlock).where(UserBlock.blocker_id == user.id, UserBlock.blocked_id == other_id)
    )


# --- Direct messages ------------------------------------------------------------------------


def _other(dm: DMChannel, user_id: uuid.UUID) -> uuid.UUID:
    return dm.user_high_id if dm.user_low_id == user_id else dm.user_low_id


async def _check_can_message(db: AsyncSession, a: uuid.UUID, b: uuid.UUID) -> None:
    if await is_blocked_between(db, a, b):
        raise Forbidden("You cannot message this user", code="dm_blocked")
    if not await are_friends(db, a, b):
        raise Forbidden("Direct messages are only possible between friends", code="dm_not_friends")


async def open_dm(db: AsyncSession, user: User, other_id: uuid.UUID) -> DMChannel:
    if other_id == user.id:
        raise AppError("You cannot message yourself", code="dm_self")
    await _check_can_message(db, user.id, other_id)
    low, high = (user.id, other_id) if user.id < other_id else (other_id, user.id)
    dm = await db.scalar(
        select(DMChannel).where(DMChannel.user_low_id == low, DMChannel.user_high_id == high)
    )
    if dm is None:
        dm = DMChannel(user_low_id=low, user_high_id=high)
        db.add(dm)
        try:
            async with db.begin_nested():
                await db.flush()
        except IntegrityError:
            dm = await db.scalar(
                select(DMChannel).where(
                    DMChannel.user_low_id == low, DMChannel.user_high_id == high
                )
            )
    assert dm is not None
    return dm


async def get_dm(db: AsyncSession, user: User, dm_id: uuid.UUID) -> DMChannel:
    dm = await db.get(DMChannel, dm_id)
    if dm is None or user.id not in (dm.user_low_id, dm.user_high_id):
        raise NotFound("Conversation not found", code="dm_not_found")
    return dm


def dm_message_out(msg: DirectMessage, author: User) -> DirectMessageOut:
    return DirectMessageOut(
        id=msg.id,
        dm_id=msg.dm_channel_id,
        author=public_user(author),
        content=msg.content,
        created_at=msg.created_at,
        edited_at=msg.edited_at,
    )


async def list_dms(
    db: AsyncSession, user: User
) -> list[tuple[DMChannel, User, DirectMessage | None]]:
    dms = list(
        await db.scalars(
            select(DMChannel)
            .where(or_(DMChannel.user_low_id == user.id, DMChannel.user_high_id == user.id))
            .order_by(DMChannel.last_message_at.desc())
            .limit(100)
        )
    )
    others = {
        u.id: u
        for u in await db.scalars(
            select(User).where(User.id.in_([_other(d, user.id) for d in dms]))
        )
    }
    result = []
    for dm in dms:
        last = await db.scalar(
            select(DirectMessage)
            .where(DirectMessage.dm_channel_id == dm.id)
            .order_by(DirectMessage.created_at.desc())
            .limit(1)
        )
        result.append((dm, others[_other(dm, user.id)], last))
    return result


async def list_dm_messages(
    db: AsyncSession, dm: DMChannel, before: uuid.UUID | None
) -> list[DirectMessage]:
    stmt = select(DirectMessage).where(DirectMessage.dm_channel_id == dm.id)
    if before:
        anchor = await db.get(DirectMessage, before)
        if anchor is None or anchor.dm_channel_id != dm.id:
            raise NotFound("Message not found", code="message_not_found")
        stmt = stmt.where(DirectMessage.created_at < anchor.created_at)
    rows = list(await db.scalars(stmt.order_by(DirectMessage.created_at.desc()).limit(DM_PAGE)))
    rows.reverse()
    return rows


async def send_dm(db: AsyncSession, user: User, dm: DMChannel, content: str) -> DirectMessage:
    await _check_can_message(db, user.id, _other(dm, user.id))
    msg = DirectMessage(dm_channel_id=dm.id, author_id=user.id, content=content)
    db.add(msg)
    dm.last_message_at = utcnow()
    await db.flush()
    return msg


async def delete_dm_message(db: AsyncSession, user: User, message_id: uuid.UUID) -> DirectMessage:
    msg = await db.get(DirectMessage, message_id)
    if msg is None or msg.author_id != user.id:
        raise NotFound("Message not found", code="message_not_found")
    await db.delete(msg)
    await db.flush()
    return msg


def dm_participants(dm: DMChannel) -> list[uuid.UUID]:
    return [dm.user_low_id, dm.user_high_id]
