from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.database import get_db
from app.core.errors import Forbidden, Unauthorized
from app.core.rate_limit import limiter
from app.core.security import hash_token
from app.models import BotApplication, User, UserSession
from app.services import auth_service

DB = Annotated[AsyncSession, Depends(get_db)]


@dataclass(slots=True)
class AuthContext:
    user: User
    session: UserSession | None  # None for bots
    token: str

    @property
    def is_bot(self) -> bool:
        return self.user.is_bot


async def resolve_bot(db: AsyncSession, authorization: str | None) -> User | None:
    """``Authorization: Bot <token>`` → the bot's user account."""
    if not authorization or not authorization.startswith("Bot "):
        return None
    token = authorization.removeprefix("Bot ").strip()
    row = (
        await db.execute(
            select(User)
            .join(BotApplication, BotApplication.bot_user_id == User.id)
            .where(BotApplication.token_hash == hash_token(token), User.is_active.is_(True))
        )
    ).scalar_one_or_none()
    return row


async def get_any_auth(request: Request, db: DB) -> AuthContext:
    """A logged-in user (session cookie) or a bot (bot token)."""
    authorization = request.headers.get("authorization")
    if authorization:
        bot = await resolve_bot(db, authorization)
        if bot is None:
            raise Unauthorized("Invalid bot token", code="invalid_bot_token")
        return AuthContext(user=bot, session=None, token=authorization)

    token = request.cookies.get(get_settings().session_cookie_name)
    if not token:
        raise Unauthorized()
    resolved = await auth_service.resolve_session(db, token)
    if resolved is None:
        raise Unauthorized("Session expired", code="session_expired")
    session, user = resolved
    return AuthContext(user=user, session=session, token=token)


async def get_auth(auth: Annotated[AuthContext, Depends(get_any_auth)]) -> AuthContext:
    """Humans only — account, social and admin-only endpoints are closed to bots."""
    if auth.is_bot:
        raise Forbidden("Not available for bots", code="bots_not_allowed")
    return auth


Auth = Annotated[AuthContext, Depends(get_auth)]
AnyAuth = Annotated[AuthContext, Depends(get_any_auth)]


def client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def rate_limit(request: Request, bucket: str, rule: str, extra: str = "") -> None:
    if get_settings().rate_limit_enabled:
        limiter.hit(f"{bucket}:{client_ip(request)}:{extra}", rule)


def user_rate_limit(bucket: str, rule: str, user_id: object) -> None:
    if get_settings().rate_limit_enabled:
        limiter.hit(f"{bucket}:user:{user_id}", rule)
