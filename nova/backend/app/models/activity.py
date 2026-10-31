"""Gaming spaces, voice zones, instant gaming sessions and session notes."""

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Index, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, CreatedAt, UUIDPk, utcnow

ZONE_KINDS = ("lobby", "gaming", "chill", "afk", "strategy", "custom")
NOTE_KINDS = ("note", "link", "task", "followup")


class GamingSpace(UUIDPk, CreatedAt, Base):
    __tablename__ = "gaming_spaces"

    server_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("servers.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(64))
    description: Mapped[str | None] = mapped_column(String(300))
    # Category holding the zones' voice channels.
    category_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("channels.id", ondelete="SET NULL")
    )
    created_by: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )


class VoiceZone(UUIDPk, Base):
    """A clickable area on a space's map, backed by a voice channel."""

    __tablename__ = "voice_zones"
    __table_args__ = (
        CheckConstraint(
            "kind IN ('lobby', 'gaming', 'chill', 'afk', 'strategy', 'custom')", name="kind_valid"
        ),
        CheckConstraint(
            "x >= 0 AND y >= 0 AND width > 0 AND height > 0 AND x + width <= 100 "
            "AND y + height <= 100",
            name="inside_map",
        ),
    )

    space_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("gaming_spaces.id", ondelete="CASCADE"), index=True
    )
    channel_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("channels.id", ondelete="CASCADE"), unique=True
    )
    name: Mapped[str] = mapped_column(String(64))
    kind: Mapped[str] = mapped_column(String(16))
    # Position on a 100x100 grid (percent of the map).
    x: Mapped[int] = mapped_column(Integer)
    y: Mapped[int] = mapped_column(Integer)
    width: Mapped[int] = mapped_column(Integer)
    height: Mapped[int] = mapped_column(Integer)
    color: Mapped[str | None] = mapped_column(String(7))


class GamingSession(UUIDPk, CreatedAt, Base):
    __tablename__ = "gaming_sessions"
    __table_args__ = (
        CheckConstraint("status IN ('open', 'closed')", name="status_valid"),
        CheckConstraint("max_participants BETWEEN 2 AND 100", name="max_participants_range"),
        Index("ix_gaming_sessions_server_status", "server_id", "status"),
    )

    server_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("servers.id", ondelete="CASCADE"))
    creator_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    game: Mapped[str] = mapped_column(String(64))
    title: Mapped[str] = mapped_column(String(100))
    description: Mapped[str | None] = mapped_column(String(500))
    max_participants: Mapped[int] = mapped_column(Integer)
    start_time: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    status: Mapped[str] = mapped_column(String(16), default="open", server_default="open")
    voice_channel_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("channels.id", ondelete="SET NULL")
    )
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class GamingSessionMember(Base):
    __tablename__ = "gaming_session_members"

    session_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("gaming_sessions.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True, index=True
    )
    joined_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class SessionNote(UUIDPk, CreatedAt, Base):
    __tablename__ = "session_notes"
    __table_args__ = (
        CheckConstraint("kind IN ('note', 'link', 'task', 'followup')", name="kind_valid"),
    )

    session_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("gaming_sessions.id", ondelete="CASCADE"), index=True
    )
    author_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    kind: Mapped[str] = mapped_column(String(16))
    content: Mapped[str] = mapped_column(String(1000))
    url: Mapped[str | None] = mapped_column(String(500))
    done: Mapped[bool] = mapped_column(default=False, server_default="false")
    due_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
