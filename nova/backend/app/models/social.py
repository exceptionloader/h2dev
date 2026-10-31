"""Friendships, blocks, direct messages and abuse reports."""

import uuid
from datetime import datetime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, CreatedAt, UUIDPk, utcnow

REPORT_REASONS = ("spam", "harassment", "hate", "cheating", "nsfw", "other")


class Friendship(UUIDPk, CreatedAt, Base):
    __tablename__ = "friendships"
    __table_args__ = (
        CheckConstraint("status IN ('pending', 'accepted')", name="status_valid"),
        CheckConstraint("requester_id <> addressee_id", name="not_self"),
        # One relationship per pair, regardless of who asked.
        Index(
            "uq_friendships_pair",
            text("least(requester_id, addressee_id)"),
            text("greatest(requester_id, addressee_id)"),
            unique=True,
        ),
    )

    requester_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    addressee_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    status: Mapped[str] = mapped_column(String(16), default="pending")
    accepted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class UserBlock(CreatedAt, Base):
    __tablename__ = "user_blocks"
    __table_args__ = (CheckConstraint("blocker_id <> blocked_id", name="not_self"),)

    blocker_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    blocked_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True, index=True
    )


class DMChannel(UUIDPk, CreatedAt, Base):
    __tablename__ = "dm_channels"
    __table_args__ = (
        UniqueConstraint("user_low_id", "user_high_id"),
        CheckConstraint("user_low_id < user_high_id", name="ordered_pair"),
    )

    # Stored ordered (low < high) so a pair has exactly one channel.
    user_low_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    user_high_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    last_message_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class DirectMessage(UUIDPk, CreatedAt, Base):
    __tablename__ = "direct_messages"
    __table_args__ = (Index("ix_direct_messages_channel_created", "dm_channel_id", "created_at"),)

    dm_channel_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("dm_channels.id", ondelete="CASCADE")
    )
    author_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    content: Mapped[str] = mapped_column(Text)
    edited_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class UserReport(UUIDPk, CreatedAt, Base):
    __tablename__ = "user_reports"
    __table_args__ = (
        CheckConstraint(
            "reason IN ('spam', 'harassment', 'hate', 'cheating', 'nsfw', 'other')",
            name="reason_valid",
        ),
        CheckConstraint("status IN ('open', 'resolved', 'dismissed')", name="status_valid"),
        Index("ix_user_reports_server_status", "server_id", "status"),
    )

    reporter_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    target_user_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE")
    )
    # Server-scoped reports appear in that server's moderation queue; reports without a
    # server (e.g. about a DM) go to the platform team.
    server_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("servers.id", ondelete="CASCADE")
    )
    message_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("messages.id", ondelete="SET NULL")
    )
    # Snapshot, because the reported message may be deleted later.
    message_excerpt: Mapped[str | None] = mapped_column(String(500))
    reason: Mapped[str] = mapped_column(String(16))
    details: Mapped[str | None] = mapped_column(String(1000))
    status: Mapped[str] = mapped_column(String(16), default="open", server_default="open")
    handled_by: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    handled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
