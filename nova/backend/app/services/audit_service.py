import uuid
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models import AuditLog


def record(
    db: AsyncSession,
    *,
    server_id: uuid.UUID,
    actor_id: uuid.UUID | None,
    action: str,
    target_type: str | None = None,
    target_id: uuid.UUID | None = None,
    details: dict[str, Any] | None = None,
) -> None:
    db.add(
        AuditLog(
            server_id=server_id,
            actor_id=actor_id,
            action=action,
            target_type=target_type,
            target_id=target_id,
            details=details or {},
        )
    )


async def list_for_server(
    db: AsyncSession, server_id: uuid.UUID, limit: int = 50
) -> list[AuditLog]:
    rows = await db.scalars(
        select(AuditLog)
        .where(AuditLog.server_id == server_id)
        .options(selectinload(AuditLog.actor))
        .order_by(AuditLog.created_at.desc())
        .limit(limit)
    )
    return list(rows)
