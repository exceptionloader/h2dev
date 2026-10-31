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
    managed: bool = False


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


# --- Milestone 2 ----------------------------------------------------------------------------

RoleName = Annotated[str, StringConstraints(max_length=32), AfterValidator(_clean)]
HexColor = Annotated[str, StringConstraints(pattern=r"^#[0-9A-Fa-f]{6}$")]


class RoleCreate(BaseModel):
    name: RoleName
    color: HexColor | None = None
    permissions: list[str] = Field(default_factory=list, max_length=64)


class RoleUpdate(BaseModel):
    name: RoleName | None = None
    color: HexColor | None = None
    permissions: list[str] | None = Field(default=None, max_length=64)


class RoleMove(BaseModel):
    direction: Literal["up", "down"]


class InviteCreate(BaseModel):
    max_uses: int | None = Field(default=None, ge=1, le=1000)
    expires_in_hours: int | None = Field(default=24, ge=1, le=24 * 30)


class InviteOut(BaseModel):
    code: str
    server_id: uuid.UUID
    creator: UserPublic | None
    max_uses: int | None
    uses: int
    expires_at: datetime | None
    created_at: datetime


class InvitePreview(BaseModel):
    code: str
    server: ServerOut
    member_count: int
    already_member: bool
    expires_at: datetime | None


class BanCreate(BaseModel):
    user_id: uuid.UUID
    reason: Annotated[str, StringConstraints(max_length=300, strip_whitespace=True)] | None = None


class BanOut(BaseModel):
    user: UserPublic
    reason: str | None
    created_at: datetime


class OwnershipTransfer(BaseModel):
    new_owner_id: uuid.UUID
    password: Annotated[str, StringConstraints(min_length=1, max_length=128)]


class ChannelUpdate(BaseModel):
    name: Annotated[str, StringConstraints(max_length=64), AfterValidator(_channel_name)] | None = (
        None
    )
    topic: Annotated[str, StringConstraints(max_length=300, strip_whitespace=True)] | None = None
    parent_id: uuid.UUID | None = None


class OverrideIn(BaseModel):
    allow: list[str] = Field(default_factory=list, max_length=64)
    deny: list[str] = Field(default_factory=list, max_length=64)


class OverrideOut(BaseModel):
    role_id: uuid.UUID | None
    member_id: uuid.UUID | None
    allow: list[str]
    deny: list[str]


class DiscoverServer(ServerOut):
    member_count: int
    is_member: bool


class AttachmentOut(BaseModel):
    id: uuid.UUID
    filename: str
    content_type: str
    size: int
    is_image: bool
    url: str


class ReactionOut(BaseModel):
    emoji: str
    user_ids: list[uuid.UUID]


class ReplyPreview(BaseModel):
    id: uuid.UUID
    author: UserPublic | None
    content: str


class MessageOut(BaseModel):
    id: uuid.UUID
    channel_id: uuid.UUID
    server_id: uuid.UUID
    author: UserPublic | None
    content: str
    created_at: datetime
    edited_at: datetime | None
    pinned: bool
    reply_to: ReplyPreview | None
    reactions: list[ReactionOut]
    attachments: list[AttachmentOut]


MessageContent = Annotated[str, StringConstraints(max_length=2000)]


class MessageCreate(BaseModel):
    content: MessageContent = ""
    reply_to_id: uuid.UUID | None = None
    attachment_ids: list[uuid.UUID] = Field(default_factory=list, max_length=5)


class MessageEdit(BaseModel):
    content: Annotated[str, StringConstraints(min_length=1, max_length=2000)]
