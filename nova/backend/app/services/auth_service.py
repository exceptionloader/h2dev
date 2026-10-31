import uuid
from datetime import timedelta

from sqlalchemy import delete, or_, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.errors import AppError, Conflict, Unauthorized
from app.core.security import (
    generate_token,
    hash_password,
    hash_token,
    password_needs_rehash,
    verify_password,
)
from app.models import PasswordResetToken, User, UserSession
from app.models.base import utcnow
from app.schemas.user import RegisterRequest
from app.services import mail


async def register(db: AsyncSession, data: RegisterRequest) -> User:
    email = data.email.lower()
    existing = await db.scalar(
        select(User).where(or_(User.username == data.username, User.email == email))
    )
    if existing:
        if existing.username == data.username:
            raise Conflict("Username already taken", code="username_taken")
        raise Conflict("E-mail already registered", code="email_taken")

    user = User(
        username=data.username,
        email=email,
        display_name=data.display_name or data.username,
        password_hash=hash_password(data.password),
    )
    db.add(user)
    try:
        await db.flush()
    except IntegrityError as exc:  # concurrent registration with the same name
        await db.rollback()
        raise Conflict("Username or e-mail already taken", code="username_taken") from exc
    return user


async def authenticate(db: AsyncSession, login: str, password: str) -> User:
    login = login.strip().lower()
    user = await db.scalar(select(User).where(or_(User.username == login, User.email == login)))
    # verify_password runs a full hash even when the user does not exist (timing).
    if not verify_password(user.password_hash if user else None, password):
        raise Unauthorized("Invalid credentials", code="invalid_credentials")
    assert user is not None
    if not user.is_active:
        raise Unauthorized("Account disabled", code="account_disabled")
    if password_needs_rehash(user.password_hash):
        user.password_hash = hash_password(password)
    return user


async def create_session(
    db: AsyncSession, user: User, user_agent: str | None, ip: str | None
) -> str:
    token = generate_token()
    db.add(
        UserSession(
            user_id=user.id,
            token_hash=hash_token(token),
            expires_at=utcnow() + timedelta(days=get_settings().session_ttl_days),
            user_agent=(user_agent or "")[:255] or None,
            ip_address=ip,
        )
    )
    await db.flush()
    return token


async def resolve_session(db: AsyncSession, token: str) -> tuple[UserSession, User] | None:
    row = (
        await db.execute(
            select(UserSession, User)
            .join(User, User.id == UserSession.user_id)
            .where(UserSession.token_hash == hash_token(token))
        )
    ).first()
    if row is None:
        return None
    session, user = row
    now = utcnow()
    if session.expires_at <= now or not user.is_active:
        return None
    # Only write last_seen occasionally to avoid a DB write on every request.
    if now - session.last_seen_at > timedelta(minutes=5):
        session.last_seen_at = now
        await db.commit()
    return session, user


async def revoke_session(db: AsyncSession, token: str) -> None:
    await db.execute(delete(UserSession).where(UserSession.token_hash == hash_token(token)))


async def revoke_all_sessions(
    db: AsyncSession, user_id: uuid.UUID, except_session_id: uuid.UUID | None = None
) -> None:
    stmt = delete(UserSession).where(UserSession.user_id == user_id)
    if except_session_id:
        stmt = stmt.where(UserSession.id != except_session_id)
    await db.execute(stmt)


async def change_password(
    db: AsyncSession, user: User, current: str, new: str, keep_session_id: uuid.UUID
) -> None:
    if not verify_password(user.password_hash, current):
        raise AppError("Current password is wrong", code="invalid_current_password")
    user.password_hash = hash_password(new)
    await revoke_all_sessions(db, user.id, except_session_id=keep_session_id)


async def request_password_reset(db: AsyncSession, email: str) -> None:
    """Always succeeds from the caller's view so accounts cannot be enumerated."""
    user = await db.scalar(select(User).where(User.email == email.lower()))
    if user is None or not user.is_active:
        return
    # Invalidate earlier unused tokens.
    await db.execute(
        update(PasswordResetToken)
        .where(PasswordResetToken.user_id == user.id, PasswordResetToken.used_at.is_(None))
        .values(used_at=utcnow())
    )
    settings = get_settings()
    token = generate_token()
    db.add(
        PasswordResetToken(
            user_id=user.id,
            token_hash=hash_token(token),
            expires_at=utcnow() + timedelta(minutes=settings.password_reset_ttl_minutes),
        )
    )
    await db.flush()
    link = f"{settings.frontend_url}/passwort-zuruecksetzen?token={token}"
    await mail.send(
        user.email,
        "NOVA – Passwort zurücksetzen",
        f"Hallo {user.display_name},\n\nsetze dein Passwort hier zurück: {link}\n\n"
        f"Der Link ist {settings.password_reset_ttl_minutes} Minuten gültig.",
    )


async def confirm_password_reset(db: AsyncSession, token: str, new_password: str) -> None:
    row = await db.scalar(
        select(PasswordResetToken)
        .where(PasswordResetToken.token_hash == hash_token(token))
        .with_for_update()
    )
    if row is None or row.used_at is not None or row.expires_at <= utcnow():
        raise AppError("Reset link invalid or expired", code="invalid_reset_token")
    user = await db.get(User, row.user_id)
    assert user is not None
    row.used_at = utcnow()
    user.password_hash = hash_password(new_password)
    await revoke_all_sessions(db, user.id)
