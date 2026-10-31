"""Environment-based application settings."""

from functools import lru_cache
from typing import Annotated, Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NOVA_", env_file=".env", extra="ignore")

    environment: Literal["development", "test", "production"] = "development"
    log_level: str = "INFO"

    database_url: str = "postgresql+asyncpg://nova:nova@localhost:5432/nova"
    database_echo: bool = False

    # Origins allowed to call the API with credentials (CORS + CSRF origin check).
    # Comma-separated in the environment, e.g. "https://a.example,https://b.example".
    allowed_origins: Annotated[list[str], NoDecode] = Field(
        default_factory=lambda: ["http://localhost:5173"]
    )
    # Public URL of the frontend, used to build links (e.g. password reset e-mails).
    frontend_url: str = "http://localhost:5173"

    session_cookie_name: str = "nova_session"
    session_ttl_days: int = 30
    # Must be True in production (requires HTTPS).
    session_cookie_secure: bool = False

    password_reset_ttl_minutes: int = 30

    # E-mail. Without an SMTP host, mails are written to the log (development).
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_username: str | None = None
    smtp_password: str | None = None
    smtp_security: Literal["starttls", "ssl", "none"] = "starttls"
    mail_from: str = "NOVA <no-reply@nova.local>"

    # Uploads: "local" (directory) or "s3" (any S3-compatible storage).
    storage_backend: Literal["local", "s3"] = "local"
    storage_local_path: str = "./data/uploads"
    s3_bucket: str = "nova-uploads"
    s3_endpoint_url: str | None = None
    s3_region: str = "us-east-1"
    s3_access_key: str | None = None
    s3_secret_key: str | None = None
    max_avatar_bytes: int = 2 * 1024 * 1024
    max_attachment_bytes: int = 10 * 1024 * 1024

    # LiveKit voice. The secret never leaves the backend.
    livekit_url: str | None = None  # URL the *browser* connects to, e.g. ws://localhost:7880
    livekit_api_key: str | None = None
    livekit_api_secret: str | None = None
    livekit_token_ttl_seconds: int = 600

    # Token of the first-party "NOVA Bot" (optional). The bot runs as its own process.
    first_party_bot_token: str | None = None

    # Rate limits: "<count>/<seconds>"
    rate_limit_login: str = "10/60"
    rate_limit_register: str = "5/60"
    rate_limit_password_reset: str = "5/300"
    rate_limit_messages: str = "20/10"
    rate_limit_invites: str = "10/60"
    rate_limit_friend_requests: str = "20/60"
    rate_limit_reports: str = "10/300"
    rate_limit_uploads: str = "20/60"
    # Disable only in automated tests that do not exercise rate limiting.
    rate_limit_enabled: bool = True

    @field_validator("allowed_origins", mode="before")
    @classmethod
    def _split_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [o.strip().rstrip("/") for o in value.split(",") if o.strip()]
        return value

    @property
    def voice_enabled(self) -> bool:
        return bool(self.livekit_url and self.livekit_api_key and self.livekit_api_secret)

    @property
    def is_production(self) -> bool:
        return self.environment == "production"


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    if settings.is_production and not settings.session_cookie_secure:
        raise RuntimeError("NOVA_SESSION_COOKIE_SECURE must be true in production")
    return settings
