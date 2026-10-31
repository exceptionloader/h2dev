import uuid
from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import AfterValidator, BaseModel, Field, StringConstraints

from app.schemas.common import ORMModel
from app.schemas.user import UserPublic


def _clean(value: str) -> str:
    value = " ".join(value.split())
    if not value:
        raise ValueError("must not be blank")
    return value


def _channel_name(value: str) -> str:
    value = "-".join(value.strip().lower().split())
    if not value:
        raise ValueError("must not be blank")
    return value


ServerName = Annotated[str, StringConstraints(max_length=64), AfterValidator(_clean)]
Description = Annotated[str, StringConstraints(max_length=500, strip_whitespace=True)]
ChannelType = Literal["category", "text", "voice", "announcement"]


class ServerCreate(BaseModel):
    name: ServerName
    description: Description | None = None
    visibility: Literal["private", "public"] = "private"


class ServerUpdate(BaseModel):
    name: ServerName | None = None
    description: Description | None = None
    visibility: Literal["private", "public"] | None = None


class ServerDelete(BaseModel):
    # The owner must type the server name to confirm deletion.
    confirm_name: str


class ServerOut(ORMModel):
    id: uuid.UUID
    name: str
    description: str | None
    icon_url: str | None
    banner_url: str | None
    owner_id: uuid.UUID
    visibility: str
    created_at: datetime


class ServerDetail(ServerOut):
    member_count: int
    my_permissions: list[str]
    is_owner: bool


class RoleOut(ORMModel):
    id: uuid.UUID
    name: str
    color: str | None
    icon: str | None
    position: int
    permissions: list[str]
    is_default: bool


class MemberOut(BaseModel):
    id: uuid.UUID
    user: UserPublic
    nickname: str | None
    joined_at: datetime
    role_ids: list[uuid.UUID]
    is_owner: bool


class ChannelCreate(BaseModel):
    name: Annotated[str, StringConstraints(max_length=64), AfterValidator(_channel_name)]
    type: ChannelType
    topic: Annotated[str, StringConstraints(max_length=300, strip_whitespace=True)] | None = None
    parent_id: uuid.UUID | None = None
    # Private channels are hidden from the default role; admins and the owner still see them.
    private: bool = False


class ChannelOut(ORMModel):
    id: uuid.UUID
    server_id: uuid.UUID
    parent_id: uuid.UUID | None
    type: ChannelType
    name: str
    topic: str | None
    position: int
    is_private: bool = False
    my_permissions: list[str] = Field(default_factory=list)


class AuditLogOut(BaseModel):
    id: uuid.UUID
    action: str
    actor: UserPublic | None
    target_type: str | None
    target_id: uuid.UUID | None
    details: dict[str, Any]
    created_at: datetime
