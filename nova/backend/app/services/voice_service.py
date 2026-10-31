"""LiveKit access tokens and voice states.

The backend checks CONNECT_VOICE / SPEAK, then signs a short-lived LiveKit JWT. The LiveKit
API secret never leaves the server. Voice states (who sits in which channel) are reported
by clients after connecting and are only used for display; media access is enforced by
LiveKit through the token.
"""

import uuid
from datetime import timedelta

import jwt

from app.core.config import get_settings
from app.core.errors import AppError
from app.core.permissions import Permission
from app.models import Channel, User
from app.models.base import utcnow
from app.realtime.hub import VoiceState, hub
from app.services.server_service import require_channel


class VoiceUnavailable(AppError):
    status_code = 503
    code = "voice_unavailable"


def room_name(channel_id: uuid.UUID) -> str:
    return f"nova-voice-{channel_id}"


def create_token(user: User, channel: Channel, effective: Permission) -> tuple[str, bool]:
    settings = get_settings()
    if not settings.voice_enabled:
        raise VoiceUnavailable("Voice is not configured")
    if channel.type != "voice":
        raise AppError("Not a voice channel", code="not_voice_channel")
    require_channel(effective, Permission.CONNECT_VOICE)
    can_speak = Permission.SPEAK in effective
    now = utcnow()
    claims = {
        "iss": settings.livekit_api_key,
        "sub": str(user.id),
        "name": user.display_name,
        "nbf": int((now - timedelta(seconds=10)).timestamp()),
        "exp": int((now + timedelta(seconds=settings.livekit_token_ttl_seconds)).timestamp()),
        "video": {
            "room": room_name(channel.id),
            "roomJoin": True,
            "canPublish": can_speak,
            "canPublishSources": ["microphone"],
            "canSubscribe": True,
            "canPublishData": False,
        },
    }
    token = jwt.encode(claims, settings.livekit_api_secret, algorithm="HS256")  # type: ignore[arg-type]
    return token, can_speak


def set_state(
    user: User, channel: Channel | None, effective: Permission | None, muted: bool, deafened: bool
) -> tuple[VoiceState | None, VoiceState | None]:
    """Returns (previous, new) state."""
    previous = hub.voice.get(user.id)
    if channel is None:
        hub.voice.pop(user.id, None)
        return previous, None
    if channel.type != "voice":
        raise AppError("Not a voice channel", code="not_voice_channel")
    assert effective is not None
    require_channel(effective, Permission.CONNECT_VOICE)
    state = VoiceState(
        user_id=user.id,
        server_id=channel.server_id,
        channel_id=channel.id,
        muted=muted or deafened or Permission.SPEAK not in effective,
        deafened=deafened,
        joined_at=previous.joined_at
        if previous and previous.channel_id == channel.id
        else utcnow(),
    )
    hub.voice[user.id] = state
    return previous, state
