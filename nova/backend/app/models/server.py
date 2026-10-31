import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, CreatedAt, UUIDPk, utcnow
from app.models.user import User

CHANNEL_TYPES = ("category", "text", "voice", "announcement")


class Server(UUIDPk, CreatedAt, Base):
    __tablename__ = "servers"
    __table_args__ = (
        CheckConstraint("visibility IN ('private', 'public')", name="visibility_valid"),
    )

    name: Mapped[str] = mapped_column(String(64))
    description: Mapped[str | None] = mapped_column(String(500))
    icon_url: Mapped[str | None] = mapped_column(String(512))
    banner_url: Mapped[str | None] = mapped_column(String(512))
    # RESTRICT: a user cannot be deleted while owning servers (ownership must be
    # transferred or the server deleted first).
    owner_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="RESTRICT"), index=True
    )
    visibility: Mapped[str] = mapped_column(String(16), default="private", server_default="private")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), default=utcnow, onupdate=utcnow
    )

    owner: Mapped[User] = relationship(lazy="raise")


class ServerMember(UUIDPk, Base):
    __tablename__ = "server_members"
    __table_args__ = (UniqueConstraint("server_id", "user_id"),)

    server_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("servers.id", ondelete="CASCADE"))
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    nickname: Mapped[str | None] = mapped_column(String(32))
    joined_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), default=utcnow
    )

    user: Mapped[User] = relationship(lazy="raise")


class ServerRole(UUIDPk, CreatedAt, Base):
    __tablename__ = "server_roles"
    __table_args__ = (
        UniqueConstraint("server_id", "name"),
        # Exactly one default ("everyone") role per server.
        Index(
            "uq_server_roles_one_default",
            "server_id",
            unique=True,
            postgresql_where=text("is_default"),
        ),
    )

    server_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("servers.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(32))
    color: Mapped[str | None] = mapped_column(String(7))  # "#RRGGBB"
    icon: Mapped[str | None] = mapped_column(String(64))
    # Higher position = more authority. The default role always has position 0.
    position: Mapped[int] = mapped_column(Integer, default=0)
    # Bit field, see app.core.permissions.Permission.
    permissions: Mapped[int] = mapped_column(BigInteger, default=0)
    is_default: Mapped[bool] = mapped_column(default=False, server_default="false")
    # Managed roles belong to an installed bot and can only change via (un)installing it.
    managed: Mapped[bool] = mapped_column(default=False, server_default="false")


class MemberRole(Base):
    __tablename__ = "member_roles"

    member_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("server_members.id", ondelete="CASCADE"), primary_key=True
    )
    role_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("server_roles.id", ondelete="CASCADE"), primary_key=True, index=True
    )


class Channel(UUIDPk, CreatedAt, Base):
    __tablename__ = "channels"
    __table_args__ = (
        CheckConstraint("type IN ('category', 'text', 'voice', 'announcement')", name="type_valid"),
        CheckConstraint("NOT (type = 'category' AND parent_id IS NOT NULL)", name="no_nesting"),
        Index("ix_channels_server_position", "server_id", "position"),
    )

    server_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("servers.id", ondelete="CASCADE"))
    # Category this channel belongs to (a channel of type 'category').
    parent_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("channels.id", ondelete="SET NULL")
    )
    type: Mapped[str] = mapped_column(String(16))
    name: Mapped[str] = mapped_column(String(64))
    topic: Mapped[str | None] = mapped_column(String(300))
    position: Mapped[int] = mapped_column(Integer, default=0)


class ChannelPermissionOverride(UUIDPk, Base):
    __tablename__ = "channel_permission_overrides"
    __table_args__ = (
        CheckConstraint("(role_id IS NULL) <> (member_id IS NULL)", name="exactly_one_target"),
        UniqueConstraint("channel_id", "role_id"),
        UniqueConstraint("channel_id", "member_id"),
    )

    channel_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("channels.id", ondelete="CASCADE"), index=True
    )
    role_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("server_roles.id", ondelete="CASCADE")
    )
    member_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("server_members.id", ondelete="CASCADE")
    )
    allow: Mapped[int] = mapped_column(BigInteger, default=0, server_default="0")
    deny: Mapped[int] = mapped_column(BigInteger, default=0, server_default="0")


class AuditLog(UUIDPk, CreatedAt, Base):
    __tablename__ = "audit_logs"
    __table_args__ = (Index("ix_audit_logs_server_created", "server_id", "created_at"),)

    server_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("servers.id", ondelete="CASCADE"))
    actor_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    action: Mapped[str] = mapped_column(String(64))
    target_type: Mapped[str | None] = mapped_column(String(32))
    target_id: Mapped[uuid.UUID | None]
    details: Mapped[dict[str, Any]] = mapped_column(JSONB, default=dict, server_default="{}")

    actor: Mapped[User | None] = relationship(lazy="raise")
