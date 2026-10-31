import re
import uuid
from datetime import datetime
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, EmailStr, StringConstraints

from app.schemas.common import ORMModel

_USERNAME_RE = re.compile(r"^[a-z0-9_.]{3,32}$")


def _username(value: str) -> str:
    value = value.strip().lower()
    if not _USERNAME_RE.fullmatch(value) or ".." in value:
        raise ValueError("3–32 characters: a–z, 0–9, '_' or '.'")
    return value


def _not_blank(value: str) -> str:
    value = " ".join(value.split())
    if not value:
        raise ValueError("must not be blank")
    return value


Username = Annotated[str, AfterValidator(_username)]
DisplayName = Annotated[str, StringConstraints(max_length=32), AfterValidator(_not_blank)]
Password = Annotated[str, StringConstraints(min_length=10, max_length=128)]
CustomStatus = Annotated[str, StringConstraints(max_length=128, strip_whitespace=True)]
Status = Literal["online", "idle", "dnd", "invisible"]


class UserPublic(ORMModel):
    id: uuid.UUID
    username: str
    display_name: str
    avatar_url: str | None
    status: Status
    custom_status: str | None


class UserMe(UserPublic):
    email: str
    created_at: datetime


class RegisterRequest(BaseModel):
    username: Username
    email: EmailStr
    display_name: DisplayName | None = None
    password: Password


class LoginRequest(BaseModel):
    # Username or e-mail address.
    login: Annotated[str, StringConstraints(min_length=1, max_length=254, strip_whitespace=True)]
    password: Annotated[str, StringConstraints(min_length=1, max_length=128)]


class UpdateProfileRequest(BaseModel):
    display_name: DisplayName | None = None
    status: Status | None = None
    custom_status: CustomStatus | None = None


class ChangePasswordRequest(BaseModel):
    current_password: Annotated[str, StringConstraints(min_length=1, max_length=128)]
    new_password: Password


class PasswordResetRequest(BaseModel):
    email: EmailStr


class PasswordResetConfirm(BaseModel):
    token: Annotated[str, StringConstraints(min_length=20, max_length=128)]
    new_password: Password
