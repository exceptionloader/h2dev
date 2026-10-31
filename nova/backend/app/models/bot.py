"""Bot applications and their installations into servers.

Bots run as separate processes and use the public API with a bot token. Inside a server
a bot is an ordinary member with a managed role, so every permission check applies.
"""

import uuid

from sqlalchemy import BigInteger, ForeignKey, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, CreatedAt, UUIDPk


class BotApplication(UUIDPk, CreatedAt, Base):
    __tablename__ = "bot_applications"

    # NULL for first-party bots run by the NOVA team.
    owner_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    bot_user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), unique=True
    )
    name: Mapped[str] = mapped_column(String(32))
    description: Mapped[str | None] = mapped_column(String(300))
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    is_public: Mapped[bool] = mapped_column(default=False, server_default="false")
    is_first_party: Mapped[bool] = mapped_column(default=False, server_default="false")


class BotInstallation(UUIDPk, CreatedAt, Base):
    __tablename__ = "bot_installations"
    __table_args__ = (UniqueConstraint("bot_id", "server_id"),)

    bot_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("bot_applications.id", ondelete="CASCADE"))
    server_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("servers.id", ondelete="CASCADE"), index=True
    )
    role_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("server_roles.id", ondelete="CASCADE"))
    installed_by: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    # Granted permissions (bit field); mirrored on the managed role.
    permissions: Mapped[int] = mapped_column(BigInteger, default=0)
