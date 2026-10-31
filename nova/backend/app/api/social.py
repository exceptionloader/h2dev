"""Friends, blocks, direct messages and reports."""

import uuid

from fastapi import APIRouter, Query

from app.api.deps import DB, Auth, user_rate_limit
from app.core.config import get_settings
from app.models import User, UserReport
from app.realtime import events
from app.realtime.hub import public_user
from app.schemas.social import (
    DirectMessageOut,
    DMChannelOut,
    DMOpen,
    DMSend,
    FriendRequestIn,
    FriendsOut,
    ReportCreate,
    ReportOut,
    ReportUpdate,
)
from app.services import report_service, server_service, social_service

router = APIRouter(tags=["social"])


@router.get("/friends", response_model=FriendsOut)
async def friends(auth: Auth, db: DB):
    return await social_service.friends_overview(db, auth.user)


@router.post("/friends/requests", status_code=204)
async def send_request(data: FriendRequestIn, auth: Auth, db: DB):
    user_rate_limit("friend-requests", get_settings().rate_limit_friend_requests, auth.user.id)
    request, target = await social_service.send_request(db, auth.user, data.username)
    await db.commit()
    events.to_users([auth.user.id, target.id], "friends.changed", {})
    if request.status == "pending":
        events.to_users(
            [target.id], "friend.request", {"user": public_user(auth.user).model_dump(mode="json")}
        )


@router.post("/friends/requests/{request_id}/accept", status_code=204)
async def accept_request(request_id: uuid.UUID, auth: Auth, db: DB):
    request = await social_service.accept_request(db, auth.user, request_id)
    await db.commit()
    events.to_users([request.requester_id, request.addressee_id], "friends.changed", {})


@router.delete("/friends/requests/{request_id}", status_code=204)
async def delete_request(request_id: uuid.UUID, auth: Auth, db: DB):
    request = await social_service.delete_request(db, auth.user, request_id)
    await db.commit()
    events.to_users([request.requester_id, request.addressee_id], "friends.changed", {})


@router.delete("/friends/{user_id}", status_code=204)
async def remove_friend(user_id: uuid.UUID, auth: Auth, db: DB):
    await social_service.remove_friend(db, auth.user, user_id)
    await db.commit()
    events.to_users([auth.user.id, user_id], "friends.changed", {})


@router.put("/blocks/{user_id}", status_code=204)
async def block(user_id: uuid.UUID, auth: Auth, db: DB):
    await social_service.block(db, auth.user, user_id)
    await db.commit()
    events.to_users([auth.user.id, user_id], "friends.changed", {})


@router.delete("/blocks/{user_id}", status_code=204)
async def unblock(user_id: uuid.UUID, auth: Auth, db: DB):
    await social_service.unblock(db, auth.user, user_id)
    await db.commit()
    events.to_users([auth.user.id], "friends.changed", {})


# --- Direct messages ------------------------------------------------------------------------


@router.get("/dms", response_model=list[DMChannelOut])
async def list_dms(auth: Auth, db: DB):
    return [
        DMChannelOut(
            id=dm.id,
            user=public_user(other),
            last_message_at=dm.last_message_at,
            last_message=social_service.dm_message_out(
                last, auth.user if last.author_id == auth.user.id else other
            )
            if last
            else None,
        )
        for dm, other, last in await social_service.list_dms(db, auth.user)
    ]


@router.post("/dms", response_model=DMChannelOut)
async def open_dm(data: DMOpen, auth: Auth, db: DB):
    dm = await social_service.open_dm(db, auth.user, data.user_id)
    await db.commit()
    other = await db.get(User, data.user_id)
    assert other is not None
    return DMChannelOut(
        id=dm.id, user=public_user(other), last_message=None, last_message_at=dm.last_message_at
    )


@router.get("/dms/{dm_id}/messages", response_model=list[DirectMessageOut])
async def dm_messages(dm_id: uuid.UUID, auth: Auth, db: DB, before: uuid.UUID | None = None):
    dm = await social_service.get_dm(db, auth.user, dm_id)
    messages = await social_service.list_dm_messages(db, dm, before)
    users = {auth.user.id: auth.user}
    other_id = dm.user_high_id if dm.user_low_id == auth.user.id else dm.user_low_id
    other = await db.get(User, other_id)
    if other:
        users[other.id] = other
    return [social_service.dm_message_out(m, users[m.author_id]) for m in messages]


@router.post("/dms/{dm_id}/messages", response_model=DirectMessageOut, status_code=201)
async def send_dm(dm_id: uuid.UUID, data: DMSend, auth: Auth, db: DB):
    user_rate_limit("messages", get_settings().rate_limit_messages, auth.user.id)
    dm = await social_service.get_dm(db, auth.user, dm_id)
    msg = await social_service.send_dm(db, auth.user, dm, data.content)
    await db.commit()
    out = social_service.dm_message_out(msg, auth.user)
    events.to_users(social_service.dm_participants(dm), "dm.message", out.model_dump(mode="json"))
    return out


@router.delete("/dm-messages/{message_id}", status_code=204)
async def delete_dm(message_id: uuid.UUID, auth: Auth, db: DB):
    msg = await social_service.delete_dm_message(db, auth.user, message_id)
    dm = await social_service.get_dm(db, auth.user, msg.dm_channel_id)
    await db.commit()
    events.to_users(
        social_service.dm_participants(dm), "dm.deleted", {"id": message_id, "dm_id": dm.id}
    )


# --- Reports --------------------------------------------------------------------------------


async def _report_out(db: DB, reports: list[UserReport]) -> list[ReportOut]:
    from sqlalchemy import select

    ids = {r.reporter_id for r in reports if r.reporter_id} | {
        r.target_user_id for r in reports if r.target_user_id
    }
    users = {u.id: u for u in await db.scalars(select(User).where(User.id.in_(ids)))} if ids else {}
    return [
        ReportOut(
            id=r.id,
            reporter=public_user(users[r.reporter_id]) if r.reporter_id in users else None,
            target=public_user(users[r.target_user_id]) if r.target_user_id in users else None,
            server_id=r.server_id,
            message_id=r.message_id,
            message_excerpt=r.message_excerpt,
            reason=r.reason,
            details=r.details,
            status=r.status,  # type: ignore[arg-type]
            created_at=r.created_at,
            handled_at=r.handled_at,
        )
        for r in reports
    ]


@router.post("/reports", status_code=201)
async def create_report(data: ReportCreate, auth: Auth, db: DB):
    user_rate_limit("reports", get_settings().rate_limit_reports, auth.user.id)
    report = await report_service.create_report(db, auth.user, data)
    await db.commit()
    if report.server_id:
        await events.server_changed(db, report.server_id, "reports")
    return {"id": report.id}


@router.get("/servers/{server_id}/reports", response_model=list[ReportOut])
async def list_reports(
    server_id: uuid.UUID,
    auth: Auth,
    db: DB,
    status: str | None = Query(None, pattern="^(open|resolved|dismissed)$"),
):
    access = await server_service.get_access(db, server_id, auth.user.id)
    return await _report_out(db, await report_service.list_reports(db, access, status))


@router.patch("/reports/{report_id}", response_model=ReportOut)
async def update_report(report_id: uuid.UUID, data: ReportUpdate, auth: Auth, db: DB):
    report = await report_service.update_report(db, report_id, auth.user, data.status)
    await db.commit()
    return (await _report_out(db, [report]))[0]
