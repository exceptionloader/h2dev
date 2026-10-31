import uuid
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, StringConstraints

from app.schemas.user import UserPublic


class FriendRequestIn(BaseModel):
    username: Annotated[str, StringConstraints(min_length=1, max_length=32, strip_whitespace=True)]


class FriendRequestOut(BaseModel):
    id: uuid.UUID
    user: UserPublic
    created_at: datetime


class FriendsOut(BaseModel):
    friends: list[UserPublic]
    incoming: list[FriendRequestOut]
    outgoing: list[FriendRequestOut]
    blocked: list[UserPublic]


class DMOpen(BaseModel):
    user_id: uuid.UUID


class DirectMessageOut(BaseModel):
    id: uuid.UUID
    dm_id: uuid.UUID
    author: UserPublic
    content: str
    created_at: datetime
    edited_at: datetime | None


class DMChannelOut(BaseModel):
    id: uuid.UUID
    user: UserPublic
    last_message: DirectMessageOut | None
    last_message_at: datetime


class DMSend(BaseModel):
    content: Annotated[str, StringConstraints(min_length=1, max_length=2000, strip_whitespace=True)]


ReportReason = Literal["spam", "harassment", "hate", "cheating", "nsfw", "other"]


class ReportCreate(BaseModel):
    reason: ReportReason
    details: Annotated[str, StringConstraints(max_length=1000, strip_whitespace=True)] | None = None
    message_id: uuid.UUID | None = None
    target_user_id: uuid.UUID | None = None
    server_id: uuid.UUID | None = None


class ReportOut(BaseModel):
    id: uuid.UUID
    reporter: UserPublic | None
    target: UserPublic | None
    server_id: uuid.UUID | None
    message_id: uuid.UUID | None
    message_excerpt: str | None
    reason: ReportReason
    details: str | None
    status: Literal["open", "resolved", "dismissed"]
    created_at: datetime
    handled_at: datetime | None


class ReportUpdate(BaseModel):
    status: Literal["resolved", "dismissed", "open"]


class BotCreate(BaseModel):
    name: Annotated[str, StringConstraints(min_length=2, max_length=32, strip_whitespace=True)]
    description: Annotated[str, StringConstraints(max_length=300, strip_whitespace=True)] | None = (
        None
    )
    is_public: bool = False


class BotUpdate(BaseModel):
    name: (
        Annotated[str, StringConstraints(min_length=2, max_length=32, strip_whitespace=True)] | None
    ) = None
    description: Annotated[str, StringConstraints(max_length=300, strip_whitespace=True)] | None = (
        None
    )
    is_public: bool | None = None


class BotOut(BaseModel):
    id: uuid.UUID
    name: str
    description: str | None
    is_public: bool
    is_first_party: bool
    user: UserPublic
    created_at: datetime


class BotWithToken(BotOut):
    token: str


class BotInstallIn(BaseModel):
    bot_id: uuid.UUID
    permissions: list[str]


class BotInstallationOut(BaseModel):
    bot: BotOut
    permissions: list[str]
    installed_at: datetime


class AvailableBot(BotOut):
    installed: bool
