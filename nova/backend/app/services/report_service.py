"""Abuse reports and the per-server moderation queue."""

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import permissions as perms
from app.core.errors import AppError, Forbidden, NotFound
from app.core.permissions import Permission
from app.models import Message, User, UserReport
from app.models.base import utcnow
from app.services import audit_service, server_service
from app.services.server_service import ServerAccess


def require_moderator(access: ServerAccess) -> None:
    if not (
        perms.has(access.permissions, Permission.MANAGE_MESSAGES)
        or perms.has(access.permissions, Permission.KICK_MEMBERS)
    ):
        raise Forbidden("Moderators only", code="missing_permission")


async def create_report(db: AsyncSession, reporter: User, data) -> UserReport:  # noqa: ANN001
    report = UserReport(reporter_id=reporter.id, reason=data.reason, details=data.details)
    if data.message_id:
        message = await db.get(Message, data.message_id)
        if message is None:
            raise NotFound("Message not found", code="message_not_found")
        # You can only report what you can see.
        _, channel, _ = await server_service.get_channel_access(db, message.channel_id, reporter.id)
        report.message_id = message.id
        report.server_id = channel.server_id
        report.target_user_id = message.author_id
        report.message_excerpt = message.content[:500]
    elif data.server_id:
        await server_service.get_access(db, data.server_id, reporter.id)
        if not data.target_user_id:
            raise AppError("Report needs a user or a message", code="report_target_required")
        report.server_id = data.server_id
        report.target_user_id = data.target_user_id
    elif data.target_user_id:
        report.target_user_id = data.target_user_id  # platform-level report
    else:
        raise AppError("Report needs a user or a message", code="report_target_required")
    if report.target_user_id == reporter.id:
        raise AppError("You cannot report yourself", code="report_self")
    if report.target_user_id and await db.get(User, report.target_user_id) is None:
        raise NotFound("User not found", code="user_not_found")
    db.add(report)
    await db.flush()
    return report


async def list_reports(
    db: AsyncSession, access: ServerAccess, status: str | None
) -> list[UserReport]:
    require_moderator(access)
    stmt = select(UserReport).where(UserReport.server_id == access.server.id)
    if status:
        stmt = stmt.where(UserReport.status == status)
    return list(await db.scalars(stmt.order_by(UserReport.created_at.desc()).limit(100)))


async def update_report(
    db: AsyncSession, report_id: uuid.UUID, actor: User, status: str
) -> UserReport:
    report = await db.get(UserReport, report_id)
    if report is None or report.server_id is None:
        raise NotFound("Report not found", code="report_not_found")
    access = await server_service.get_access(db, report.server_id, actor.id)
    require_moderator(access)
    report.status = status
    report.handled_by = actor.id if status != "open" else None
    report.handled_at = utcnow() if status != "open" else None
    audit_service.record(
        db,
        server_id=report.server_id,
        actor_id=actor.id,
        action=f"report.{status}",
        target_type="report",
        target_id=report.id,
        details={"reason": report.reason},
    )
    await db.flush()
    return report
