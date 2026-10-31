from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.database import get_db
from app.core.errors import Unauthorized
from app.core.rate_limit import limiter
from app.models import User, UserSession
from app.services import auth_service

DB = Annotated[AsyncSession, Depends(get_db)]


@dataclass(slots=True)
class AuthContext:
    user: User
    session: UserSession
    token: str


async def get_auth(request: Request, db: DB) -> AuthContext:
    token = request.cookies.get(get_settings().session_cookie_name)
    if not token:
        raise Unauthorized()
    resolved = await auth_service.resolve_session(db, token)
    if resolved is None:
        raise Unauthorized("Session expired", code="session_expired")
    session, user = resolved
    return AuthContext(user=user, session=session, token=token)


Auth = Annotated[AuthContext, Depends(get_auth)]


def client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def rate_limit(request: Request, bucket: str, rule: str, extra: str = "") -> None:
    if get_settings().rate_limit_enabled:
        limiter.hit(f"{bucket}:{client_ip(request)}:{extra}", rule)
