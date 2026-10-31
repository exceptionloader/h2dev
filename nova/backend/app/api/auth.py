from fastapi import APIRouter, Request, Response, status

from app.api.deps import DB, Auth, client_ip, rate_limit
from app.core.config import get_settings
from app.schemas.user import (
    LoginRequest,
    PasswordResetConfirm,
    PasswordResetRequest,
    RegisterRequest,
    UserMe,
)
from app.services import auth_service

router = APIRouter(prefix="/auth", tags=["auth"])


def _set_session_cookie(response: Response, token: str) -> None:
    settings = get_settings()
    response.set_cookie(
        settings.session_cookie_name,
        token,
        max_age=settings.session_ttl_days * 86400,
        httponly=True,
        secure=settings.session_cookie_secure,
        samesite="lax",
        path="/",
    )


def _clear_session_cookie(response: Response) -> None:
    settings = get_settings()
    response.delete_cookie(
        settings.session_cookie_name,
        path="/",
        httponly=True,
        secure=settings.session_cookie_secure,
        samesite="lax",
    )


@router.post("/register", response_model=UserMe, status_code=status.HTTP_201_CREATED)
async def register(data: RegisterRequest, request: Request, response: Response, db: DB):
    rate_limit(request, "register", get_settings().rate_limit_register)
    user = await auth_service.register(db, data)
    token = await auth_service.create_session(
        db, user, request.headers.get("user-agent"), client_ip(request)
    )
    await db.commit()
    _set_session_cookie(response, token)
    return user


@router.post("/login", response_model=UserMe)
async def login(data: LoginRequest, request: Request, response: Response, db: DB):
    rate_limit(request, "login", get_settings().rate_limit_login, data.login.lower())
    user = await auth_service.authenticate(db, data.login, data.password)
    token = await auth_service.create_session(
        db, user, request.headers.get("user-agent"), client_ip(request)
    )
    await db.commit()
    _set_session_cookie(response, token)
    return user


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(auth: Auth, db: DB, response: Response):
    await auth_service.revoke_session(db, auth.token)
    await db.commit()
    _clear_session_cookie(response)


@router.post("/password-reset/request", status_code=status.HTTP_202_ACCEPTED)
async def password_reset_request(data: PasswordResetRequest, request: Request, db: DB):
    rate_limit(request, "pwreset", get_settings().rate_limit_password_reset)
    await auth_service.request_password_reset(db, data.email)
    await db.commit()
    return {"status": "accepted"}


@router.post("/password-reset/confirm", status_code=status.HTTP_204_NO_CONTENT)
async def password_reset_confirm(data: PasswordResetConfirm, request: Request, db: DB):
    rate_limit(request, "pwreset-confirm", get_settings().rate_limit_password_reset)
    await auth_service.confirm_password_reset(db, data.token, data.new_password)
    await db.commit()
