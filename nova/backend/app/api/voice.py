import uuid

from fastapi import APIRouter

from app.api.deps import DB, AnyAuth, Auth
from app.core.config import get_settings
from app.realtime import events
from app.realtime.hub import hub
from app.schemas.activity import VoiceStateIn, VoiceStateOut, VoiceTokenOut
from app.services import server_service, voice_service

router = APIRouter(tags=["voice"])


@router.post("/voice/channels/{channel_id}/token", response_model=VoiceTokenOut)
async def voice_token(channel_id: uuid.UUID, auth: Auth, db: DB):
    _, channel, effective = await server_service.get_channel_access(db, channel_id, auth.user.id)
    token, can_speak = voice_service.create_token(auth.user, channel, effective)
    return VoiceTokenOut(
        url=get_settings().livekit_url or "",
        token=token,
        room=voice_service.room_name(channel.id),
        can_speak=can_speak,
    )


@router.put("/voice/state", response_model=VoiceStateOut | None)
async def set_voice_state(data: VoiceStateIn, auth: Auth, db: DB):
    channel = effective = None
    if data.channel_id:
        _, channel, effective = await server_service.get_channel_access(
            db, data.channel_id, auth.user.id
        )
    previous, state = voice_service.set_state(
        auth.user, channel, effective, data.muted, data.deafened
    )
    if previous and (state is None or previous.server_id != state.server_id):
        await events.to_server(
            db,
            previous.server_id,
            "voice.state",
            {"user_id": auth.user.id, "server_id": previous.server_id, "channel_id": None},
        )
    if state:
        await events.to_server(db, state.server_id, "voice.state", state.to_dict())
    return VoiceStateOut(**state.to_dict()) if state else None


@router.get("/servers/{server_id}/voice-states", response_model=list[VoiceStateOut])
async def voice_states(server_id: uuid.UUID, auth: AnyAuth, db: DB):
    await server_service.get_access(db, server_id, auth.user.id)
    return [VoiceStateOut(**s.to_dict()) for s in hub.voice_states_for_server(server_id)]
