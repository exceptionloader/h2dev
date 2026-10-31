import uuid
from datetime import datetime
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, Field, HttpUrl, StringConstraints

from app.schemas.user import UserPublic


def _clean(value: str) -> str:
    value = " ".join(value.split())
    if not value:
        raise ValueError("must not be blank")
    return value


Short = Annotated[str, StringConstraints(max_length=64), AfterValidator(_clean)]
Title = Annotated[str, StringConstraints(max_length=100), AfterValidator(_clean)]
Text500 = Annotated[str, StringConstraints(max_length=500, strip_whitespace=True)]


class VoiceTokenOut(BaseModel):
    url: str
    token: str
    room: str
    can_speak: bool


class VoiceStateIn(BaseModel):
    channel_id: uuid.UUID | None
    muted: bool = False
    deafened: bool = False


class VoiceStateOut(BaseModel):
    user_id: uuid.UUID
    server_id: uuid.UUID
    channel_id: uuid.UUID
    muted: bool
    deafened: bool
    joined_at: datetime


class SpaceCreate(BaseModel):
    name: Short
    description: Annotated[str, StringConstraints(max_length=300, strip_whitespace=True)] | None = (
        None
    )


class SpaceUpdate(BaseModel):
    name: Short | None = None
    description: Annotated[str, StringConstraints(max_length=300, strip_whitespace=True)] | None = (
        None
    )


class ZoneOut(BaseModel):
    id: uuid.UUID
    channel_id: uuid.UUID
    name: str
    kind: str
    x: int
    y: int
    width: int
    height: int
    color: str | None
    can_connect: bool


class SpaceOut(BaseModel):
    id: uuid.UUID
    server_id: uuid.UUID
    name: str
    description: str | None
    zones: list[ZoneOut]
    created_at: datetime


class SessionCreate(BaseModel):
    game: Short
    title: Title
    description: Text500 | None = None
    max_participants: int = Field(ge=2, le=100)
    start_time: datetime | None = None
    voice_channel_id: uuid.UUID | None = None


class SessionOut(BaseModel):
    id: uuid.UUID
    server_id: uuid.UUID
    server_name: str
    creator: UserPublic | None
    game: str
    title: str
    description: str | None
    max_participants: int
    participants: list[UserPublic]
    start_time: datetime
    status: Literal["open", "closed"]
    is_full: bool
    voice_channel_id: uuid.UUID | None
    voice_channel_name: str | None
    created_at: datetime


NoteKind = Literal["note", "link", "task", "followup"]


class NoteCreate(BaseModel):
    kind: NoteKind
    content: Annotated[str, StringConstraints(min_length=1, max_length=1000, strip_whitespace=True)]
    url: HttpUrl | None = None
    due_at: datetime | None = None


class NoteUpdate(BaseModel):
    content: (
        Annotated[str, StringConstraints(min_length=1, max_length=1000, strip_whitespace=True)]
        | None
    ) = None
    done: bool | None = None


class NoteOut(BaseModel):
    id: uuid.UUID
    session_id: uuid.UUID
    author: UserPublic | None
    kind: NoteKind
    content: str
    url: str | None
    done: bool
    due_at: datetime | None
    created_at: datetime


class SessionInvite(BaseModel):
    user_id: uuid.UUID
