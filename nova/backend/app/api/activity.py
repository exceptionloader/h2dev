"""Gaming spaces, instant sessions and session notes."""

import uuid

from fastapi import APIRouter, Query, status

from app.api.deps import DB, AnyAuth, Auth
from app.core.errors import Forbidden, NotFound
from app.core.permissions import Permission
from app.models import GamingSession, SessionNote, User
from app.realtime import events
from app.realtime.hub import public_user
from app.schemas.activity import (
    NoteCreate,
    NoteOut,
    NoteUpdate,
    SessionCreate,
    SessionInvite,
    SessionOut,
    SpaceCreate,
    SpaceOut,
    SpaceUpdate,
)
from app.services import activity_service, member_service, server_service, social_service

router = APIRouter(tags=["activity"])


# --- Spaces ---------------------------------------------------------------------------------


@router.get("/servers/{server_id}/spaces", response_model=list[SpaceOut])
async def list_spaces(server_id: uuid.UUID, auth: AnyAuth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return await activity_service.space_out(
        db, access, await activity_service.list_spaces(db, access)
    )


@router.post("/servers/{server_id}/spaces", response_model=SpaceOut, status_code=201)
async def create_space(server_id: uuid.UUID, data: SpaceCreate, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    space = await activity_service.create_space(db, access, auth.user, data.name, data.description)
    await db.commit()
    await events.server_changed(db, server_id, "spaces", "channels")
    return (await activity_service.space_out(db, access, [space]))[0]


@router.get("/servers/{server_id}/spaces/{space_id}", response_model=SpaceOut)
async def get_space(server_id: uuid.UUID, space_id: uuid.UUID, auth: AnyAuth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    space = await activity_service.get_space(db, access, space_id)
    return (await activity_service.space_out(db, access, [space]))[0]


@router.patch("/servers/{server_id}/spaces/{space_id}", response_model=SpaceOut)
async def update_space(
    server_id: uuid.UUID, space_id: uuid.UUID, data: SpaceUpdate, auth: Auth, db: DB
):
    access = await server_service.get_access(db, server_id, auth.user.id)
    space = await activity_service.update_space(
        db, access, auth.user, space_id, data.model_dump(exclude_unset=True)
    )
    await db.commit()
    await events.server_changed(db, server_id, "spaces")
    return (await activity_service.space_out(db, access, [space]))[0]


@router.delete("/servers/{server_id}/spaces/{space_id}", status_code=204)
async def delete_space(server_id: uuid.UUID, space_id: uuid.UUID, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    await activity_service.delete_space(db, access, auth.user, space_id)
    await db.commit()
    await events.server_changed(db, server_id, "spaces", "channels")


# --- Sessions -------------------------------------------------------------------------------


async def _session_out(db: DB, session: GamingSession) -> SessionOut:
    return (await activity_service.serialize_sessions(db, [session]))[0]


async def _broadcast(db: DB, session: GamingSession, event_type: str) -> SessionOut:
    out = await _session_out(db, session)
    await events.to_server(db, session.server_id, event_type, out.model_dump(mode="json"))
    return out


@router.get("/sessions", response_model=list[SessionOut])
async def my_sessions(auth: Auth, db: DB):
    """Open sessions across all my servers (home dashboard)."""
    return await activity_service.serialize_sessions(
        db, await activity_service.list_my_open_sessions(db, auth.user.id)
    )


@router.get("/servers/{server_id}/sessions", response_model=list[SessionOut])
async def server_sessions(
    server_id: uuid.UUID, auth: AnyAuth, db: DB, include_closed: bool = Query(False)
):
    await server_service.get_access(db, server_id, auth.user.id)
    return await activity_service.serialize_sessions(
        db, await activity_service.list_server_sessions(db, server_id, include_closed)
    )


@router.post("/servers/{server_id}/sessions", response_model=SessionOut, status_code=201)
async def create_session(server_id: uuid.UUID, data: SessionCreate, auth: Auth, db: DB):
    access = await server_service.get_access(db, server_id, auth.user.id)
    session = await activity_service.create_session(db, access, auth.user, data)
    await db.commit()
    return await _broadcast(db, session, "session.created")


@router.get("/sessions/{session_id}", response_model=SessionOut)
async def get_session(session_id: uuid.UUID, auth: AnyAuth, db: DB):
    session, _ = await activity_service.session_access(db, session_id, auth.user.id)
    return await _session_out(db, session)


@router.post("/sessions/{session_id}/join", response_model=SessionOut)
async def join_session(session_id: uuid.UUID, auth: Auth, db: DB):
    session = await activity_service.join_session(db, session_id, auth.user)
    await db.commit()
    return await _broadcast(db, session, "session.updated")


@router.post("/sessions/{session_id}/leave", response_model=SessionOut)
async def leave_session(session_id: uuid.UUID, auth: Auth, db: DB):
    session = await activity_service.leave_session(db, session_id, auth.user)
    await db.commit()
    return await _broadcast(db, session, "session.updated")


@router.post("/sessions/{session_id}/close", response_model=SessionOut)
async def close_session(session_id: uuid.UUID, auth: Auth, db: DB):
    session = await activity_service.close_session(db, session_id, auth.user)
    await db.commit()
    return await _broadcast(db, session, "session.updated")


@router.post("/sessions/{session_id}/invite", status_code=status.HTTP_204_NO_CONTENT)
async def invite_friend(session_id: uuid.UUID, data: SessionInvite, auth: Auth, db: DB):
    """Invite a friend: sends a DM with a link (plus a server invite if they aren't a member)."""
    session, access = await activity_service.session_access(db, session_id, auth.user.id)
    friend = await db.get(User, data.user_id)
    if friend is None:
        raise NotFound("User not found", code="user_not_found")
    dm = await social_service.open_dm(db, auth.user, friend.id)  # checks friendship/blocks
    member_ids = await server_service.server_member_ids(db, session.server_id)
    text = f"🎮 Komm in meine Session „{session.title}“ ({session.game}): /sessions/{session.id}"
    if friend.id not in member_ids:
        if Permission.CREATE_INVITES not in access.permissions:
            raise Forbidden(
                "Friend is not a member and you cannot invite", code="missing_permission"
            )
        invite = await member_service.create_invite(db, access, auth.user, 1, 24)
        text += f"\nServer-Einladung: /einladung/{invite.code}"
    msg = await social_service.send_dm(db, auth.user, dm, text)
    await db.commit()
    out = social_service.dm_message_out(msg, auth.user)
    events.to_users(social_service.dm_participants(dm), "dm.message", out.model_dump(mode="json"))


# --- Notes ----------------------------------------------------------------------------------


async def _notes_out(db: DB, notes: list[SessionNote]) -> list[NoteOut]:
    from sqlalchemy import select

    ids = {n.author_id for n in notes if n.author_id}
    users = {u.id: u for u in await db.scalars(select(User).where(User.id.in_(ids)))} if ids else {}
    return [
        NoteOut(
            id=n.id,
            session_id=n.session_id,
            author=public_user(users[n.author_id]) if n.author_id in users else None,
            kind=n.kind,
            content=n.content,
            url=n.url,
            done=n.done,
            due_at=n.due_at,  # type: ignore[arg-type]
            created_at=n.created_at,
        )
        for n in notes
    ]


async def _notes_changed(db: DB, session_id: uuid.UUID) -> None:
    session = await db.get(GamingSession, session_id)
    if session:
        await events.to_server(
            db, session.server_id, "session.notes_changed", {"session_id": session_id}
        )


@router.get("/sessions/{session_id}/notes", response_model=list[NoteOut])
async def list_notes(session_id: uuid.UUID, auth: AnyAuth, db: DB):
    return await _notes_out(db, await activity_service.list_notes(db, session_id, auth.user))


@router.post("/sessions/{session_id}/notes", response_model=NoteOut, status_code=201)
async def add_note(session_id: uuid.UUID, data: NoteCreate, auth: Auth, db: DB):
    note = await activity_service.add_note(db, session_id, auth.user, data)
    await db.commit()
    await _notes_changed(db, session_id)
    return (await _notes_out(db, [note]))[0]


@router.patch("/notes/{note_id}", response_model=NoteOut)
async def update_note(note_id: uuid.UUID, data: NoteUpdate, auth: Auth, db: DB):
    note = await activity_service.update_note(db, note_id, auth.user, data)
    await db.commit()
    await _notes_changed(db, note.session_id)
    return (await _notes_out(db, [note]))[0]


@router.delete("/notes/{note_id}", status_code=204)
async def delete_note(note_id: uuid.UUID, auth: Auth, db: DB):
    note = await activity_service.delete_note(db, note_id, auth.user)
    await db.commit()
    await _notes_changed(db, note.session_id)
