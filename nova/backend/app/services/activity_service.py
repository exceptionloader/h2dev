"""Gaming spaces (2D maps of voice zones), instant gaming sessions and session notes."""

import uuid
from collections import defaultdict
from datetime import timedelta

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import permissions as perms
from app.core.errors import AppError, Conflict, Forbidden, NotFound
from app.core.permissions import Permission
from app.models import (
    Channel,
    GamingSession,
    GamingSessionMember,
    GamingSpace,
    Server,
    ServerMember,
    SessionNote,
    User,
    VoiceZone,
)
from app.models.base import utcnow
from app.realtime.hub import public_user
from app.schemas.activity import SessionOut, SpaceOut, ZoneOut
from app.services import audit_service, server_service
from app.services.server_service import ServerAccess

MAX_SPACES_PER_SERVER = 10
MAX_OPEN_SESSIONS_PER_USER = 5

# name, kind, x, y, w, h, color — on a 100x100 grid
DEFAULT_ZONES = [
    ("Lobby", "lobby", 3, 6, 45, 40, "#8B5CF6"),
    ("Gaming", "gaming", 52, 6, 45, 40, "#22D3EE"),
    ("Chill", "chill", 3, 52, 30, 42, "#22C55E"),
    ("Strategie", "strategy", 36, 52, 30, 42, "#F59E0B"),
    ("AFK", "afk", 69, 52, 28, 42, "#64748B"),
]


# --- Spaces ---------------------------------------------------------------------------------


async def create_space(
    db: AsyncSession, access: ServerAccess, actor: User, name: str, description: str | None
) -> GamingSpace:
    access.require(Permission.MANAGE_CHANNELS)
    count = await db.scalar(select(func.count()).where(GamingSpace.server_id == access.server.id))
    if (count or 0) >= MAX_SPACES_PER_SERVER:
        raise AppError("Space limit reached", code="space_limit_reached")
    max_pos = await db.scalar(
        select(func.coalesce(func.max(Channel.position), 0)).where(
            Channel.server_id == access.server.id, Channel.parent_id.is_(None)
        )
    )
    category = Channel(
        server_id=access.server.id, type="category", name=name, position=(max_pos or 0) + 1
    )
    db.add(category)
    await db.flush()
    space = GamingSpace(
        server_id=access.server.id,
        name=name,
        description=description,
        category_id=category.id,
        created_by=actor.id,
    )
    db.add(space)
    await db.flush()
    for i, (zname, kind, x, y, w, h, color) in enumerate(DEFAULT_ZONES):
        channel = Channel(
            server_id=access.server.id,
            parent_id=category.id,
            type="voice",
            name=zname,
            position=i,
        )
        db.add(channel)
        await db.flush()
        db.add(
            VoiceZone(
                space_id=space.id,
                channel_id=channel.id,
                name=zname,
                kind=kind,
                x=x,
                y=y,
                width=w,
                height=h,
                color=color,
            )
        )
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="space.create",
        target_type="space",
        target_id=space.id,
        details={"name": name},
    )
    await db.flush()
    return space


async def _visible_channels(db: AsyncSession, access: ServerAccess) -> dict[uuid.UUID, Permission]:
    return {ch.id: eff for ch, eff, _ in await server_service.list_visible_channels(db, access)}


async def space_out(
    db: AsyncSession, access: ServerAccess, spaces: list[GamingSpace]
) -> list[SpaceOut]:
    visible = await _visible_channels(db, access)
    zones: dict[uuid.UUID, list[VoiceZone]] = defaultdict(list)
    if spaces:
        for z in await db.scalars(
            select(VoiceZone).where(VoiceZone.space_id.in_([s.id for s in spaces]))
        ):
            zones[z.space_id].append(z)
    return [
        SpaceOut(
            id=s.id,
            server_id=s.server_id,
            name=s.name,
            description=s.description,
            created_at=s.created_at,
            zones=[
                ZoneOut(
                    id=z.id,
                    channel_id=z.channel_id,
                    name=z.name,
                    kind=z.kind,
                    x=z.x,
                    y=z.y,
                    width=z.width,
                    height=z.height,
                    color=z.color,
                    can_connect=Permission.CONNECT_VOICE in visible[z.channel_id],
                )
                for z in sorted(zones[s.id], key=lambda z: (z.y, z.x))
                if z.channel_id in visible
            ],
        )
        for s in spaces
    ]


async def list_spaces(db: AsyncSession, access: ServerAccess) -> list[GamingSpace]:
    return list(
        await db.scalars(
            select(GamingSpace)
            .where(GamingSpace.server_id == access.server.id)
            .order_by(GamingSpace.created_at)
        )
    )


async def get_space(db: AsyncSession, access: ServerAccess, space_id: uuid.UUID) -> GamingSpace:
    space = await db.get(GamingSpace, space_id)
    if space is None or space.server_id != access.server.id:
        raise NotFound("Space not found", code="space_not_found")
    return space


async def update_space(
    db: AsyncSession, access: ServerAccess, actor: User, space_id: uuid.UUID, values: dict
) -> GamingSpace:
    access.require(Permission.MANAGE_CHANNELS)
    space = await get_space(db, access, space_id)
    if values.get("name"):
        space.name = values["name"]
    if "description" in values:
        space.description = values["description"] or None
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="space.update",
        target_type="space",
        target_id=space.id,
        details={"name": space.name},
    )
    await db.flush()
    return space


async def delete_space(
    db: AsyncSession, access: ServerAccess, actor: User, space_id: uuid.UUID
) -> None:
    access.require(Permission.MANAGE_CHANNELS)
    space = await get_space(db, access, space_id)
    channel_ids = list(
        await db.scalars(select(VoiceZone.channel_id).where(VoiceZone.space_id == space.id))
    )
    if space.category_id:
        channel_ids.append(space.category_id)
    await db.delete(space)
    await db.execute(delete(Channel).where(Channel.id.in_(channel_ids)))
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="space.delete",
        target_type="space",
        target_id=space.id,
        details={"name": space.name},
    )
    await db.flush()


# --- Sessions -------------------------------------------------------------------------------


async def serialize_sessions(db: AsyncSession, sessions: list[GamingSession]) -> list[SessionOut]:
    if not sessions:
        return []
    ids = [s.id for s in sessions]
    members: dict[uuid.UUID, list[uuid.UUID]] = defaultdict(list)
    for sid, uid in await db.execute(
        select(GamingSessionMember.session_id, GamingSessionMember.user_id)
        .where(GamingSessionMember.session_id.in_(ids))
        .order_by(GamingSessionMember.joined_at)
    ):
        members[sid].append(uid)
    user_ids = {u for us in members.values() for u in us} | {
        s.creator_id for s in sessions if s.creator_id
    }
    users = {u.id: u for u in await db.scalars(select(User).where(User.id.in_(user_ids)))}
    servers = dict(
        (
            await db.execute(
                select(Server.id, Server.name).where(Server.id.in_({s.server_id for s in sessions}))
            )
        ).all()
    )
    channel_ids = {s.voice_channel_id for s in sessions if s.voice_channel_id}
    channels = (
        dict(
            (
                await db.execute(
                    select(Channel.id, Channel.name).where(Channel.id.in_(channel_ids))
                )
            ).all()
        )
        if channel_ids
        else {}
    )
    return [
        SessionOut(
            id=s.id,
            server_id=s.server_id,
            server_name=servers.get(s.server_id, ""),
            creator=public_user(users[s.creator_id]) if s.creator_id in users else None,
            game=s.game,
            title=s.title,
            description=s.description,
            max_participants=s.max_participants,
            participants=[public_user(users[u]) for u in members[s.id] if u in users],
            start_time=s.start_time,
            status=s.status,  # type: ignore[arg-type]
            is_full=len(members[s.id]) >= s.max_participants,
            voice_channel_id=s.voice_channel_id,
            voice_channel_name=channels.get(s.voice_channel_id) if s.voice_channel_id else None,
            created_at=s.created_at,
        )
        for s in sessions
    ]


async def create_session(
    db: AsyncSession, access: ServerAccess, actor: User, data
) -> GamingSession:  # noqa: ANN001 - SessionCreate
    open_count = await db.scalar(
        select(func.count()).where(
            GamingSession.creator_id == actor.id, GamingSession.status == "open"
        )
    )
    if (open_count or 0) >= MAX_OPEN_SESSIONS_PER_USER:
        raise AppError("Too many open sessions", code="session_limit_reached")
    now = utcnow()
    start = data.start_time or now
    if start.tzinfo is None:
        raise AppError("start_time needs a timezone", code="invalid_start_time")
    if not (now - timedelta(hours=1) <= start <= now + timedelta(days=14)):
        raise AppError("Start time out of range", code="invalid_start_time")
    if data.voice_channel_id:
        channel = await db.get(Channel, data.voice_channel_id)
        visible = await _visible_channels(db, access)
        if (
            channel is None
            or channel.server_id != access.server.id
            or channel.type != "voice"
            or channel.id not in visible
        ):
            raise AppError("Invalid voice channel", code="invalid_voice_channel")
    session = GamingSession(
        server_id=access.server.id,
        creator_id=actor.id,
        game=data.game,
        title=data.title,
        description=data.description,
        max_participants=data.max_participants,
        start_time=start,
        voice_channel_id=data.voice_channel_id,
    )
    db.add(session)
    await db.flush()
    db.add(GamingSessionMember(session_id=session.id, user_id=actor.id))
    await db.flush()
    return session


async def list_server_sessions(
    db: AsyncSession, server_id: uuid.UUID, include_closed: bool = False
) -> list[GamingSession]:
    stmt = select(GamingSession).where(GamingSession.server_id == server_id)
    if include_closed:
        stmt = stmt.where(GamingSession.created_at >= utcnow() - timedelta(days=7))
    else:
        stmt = stmt.where(GamingSession.status == "open")
    return list(await db.scalars(stmt.order_by(GamingSession.start_time).limit(100)))


async def list_my_open_sessions(db: AsyncSession, user_id: uuid.UUID) -> list[GamingSession]:
    my_servers = select(ServerMember.server_id).where(ServerMember.user_id == user_id)
    return list(
        await db.scalars(
            select(GamingSession)
            .where(
                GamingSession.server_id.in_(my_servers),
                GamingSession.status == "open",
                GamingSession.start_time >= utcnow() - timedelta(hours=12),
            )
            .order_by(GamingSession.start_time)
            .limit(100)
        )
    )


async def session_access(
    db: AsyncSession, session_id: uuid.UUID, user_id: uuid.UUID, lock: bool = False
) -> tuple[GamingSession, ServerAccess]:
    stmt = select(GamingSession).where(GamingSession.id == session_id)
    if lock:
        stmt = stmt.with_for_update()
    session = await db.scalar(stmt)
    if session is None:
        raise NotFound("Session not found", code="session_not_found")
    try:
        access = await server_service.get_access(db, session.server_id, user_id)
    except NotFound as exc:
        raise NotFound("Session not found", code="session_not_found") from exc
    return session, access


async def _is_participant(db: AsyncSession, session_id: uuid.UUID, user_id: uuid.UUID) -> bool:
    return await db.get(GamingSessionMember, (session_id, user_id)) is not None


async def join_session(db: AsyncSession, session_id: uuid.UUID, user: User) -> GamingSession:
    session, _ = await session_access(db, session_id, user.id, lock=True)
    if session.status != "open":
        raise AppError("Session is closed", code="session_closed")
    if await _is_participant(db, session.id, user.id):
        raise Conflict("Already joined", code="already_joined")
    count = await db.scalar(
        select(func.count()).where(GamingSessionMember.session_id == session.id)
    )
    if (count or 0) >= session.max_participants:
        raise AppError("Session is full", code="session_full")
    db.add(GamingSessionMember(session_id=session.id, user_id=user.id))
    await db.flush()
    return session


async def leave_session(db: AsyncSession, session_id: uuid.UUID, user: User) -> GamingSession:
    session, _ = await session_access(db, session_id, user.id, lock=True)
    row = await db.get(GamingSessionMember, (session.id, user.id))
    if row is None:
        raise AppError("Not a participant", code="not_participant")
    await db.delete(row)
    await db.flush()
    remaining = await db.scalar(
        select(func.count()).where(GamingSessionMember.session_id == session.id)
    )
    if not remaining and session.status == "open":
        session.status, session.closed_at = "closed", utcnow()
    await db.flush()
    return session


async def close_session(db: AsyncSession, session_id: uuid.UUID, user: User) -> GamingSession:
    session, access = await session_access(db, session_id, user.id, lock=True)
    if session.creator_id != user.id and not perms.has(
        access.permissions, Permission.MANAGE_EVENTS
    ):
        raise Forbidden("Only the creator or event managers can close it", code="forbidden")
    if session.status == "open":
        session.status, session.closed_at = "closed", utcnow()
        if session.creator_id != user.id:
            audit_service.record(
                db,
                server_id=session.server_id,
                actor_id=user.id,
                action="session.close",
                target_type="session",
                target_id=session.id,
                details={"title": session.title},
            )
    await db.flush()
    return session


# --- Notes ----------------------------------------------------------------------------------


async def list_notes(db: AsyncSession, session_id: uuid.UUID, user: User) -> list[SessionNote]:
    session, _ = await session_access(db, session_id, user.id)
    return list(
        await db.scalars(
            select(SessionNote)
            .where(SessionNote.session_id == session.id)
            .order_by(SessionNote.created_at)
        )
    )


async def _can_write_notes(
    db: AsyncSession, session: GamingSession, access: ServerAccess, user: User
) -> bool:
    return (
        session.creator_id == user.id
        or await _is_participant(db, session.id, user.id)
        or perms.has(access.permissions, Permission.MANAGE_EVENTS)
    )


async def add_note(db: AsyncSession, session_id: uuid.UUID, user: User, data) -> SessionNote:  # noqa: ANN001
    session, access = await session_access(db, session_id, user.id)
    if not await _can_write_notes(db, session, access, user):
        raise Forbidden("Only participants can add notes", code="not_participant")
    count = await db.scalar(select(func.count()).where(SessionNote.session_id == session.id))
    if (count or 0) >= 200:
        raise AppError("Too many notes", code="note_limit_reached")
    if data.kind == "link" and not data.url:
        raise AppError("Links need a URL", code="url_required")
    note = SessionNote(
        session_id=session.id,
        author_id=user.id,
        kind=data.kind,
        content=data.content,
        url=str(data.url) if data.url else None,
        due_at=data.due_at,
    )
    db.add(note)
    await db.flush()
    return note


async def _note_access(db: AsyncSession, note_id: uuid.UUID, user: User):
    note = await db.get(SessionNote, note_id)
    if note is None:
        raise NotFound("Note not found", code="note_not_found")
    session, access = await session_access(db, note.session_id, user.id)
    return note, session, access


async def update_note(db: AsyncSession, note_id: uuid.UUID, user: User, data) -> SessionNote:  # noqa: ANN001
    note, session, access = await _note_access(db, note_id, user)
    if data.content is not None:
        if note.author_id != user.id:
            raise Forbidden("Only the author can edit", code="not_author")
        note.content = data.content
    if data.done is not None:
        if not await _can_write_notes(db, session, access, user):
            raise Forbidden("Only participants can update notes", code="not_participant")
        note.done = data.done
    await db.flush()
    return note


async def delete_note(db: AsyncSession, note_id: uuid.UUID, user: User) -> SessionNote:
    note, session, access = await _note_access(db, note_id, user)
    if note.author_id != user.id and not perms.has(access.permissions, Permission.MANAGE_EVENTS):
        raise Forbidden("Only the author can delete", code="not_author")
    await db.delete(note)
    await db.flush()
    return note
