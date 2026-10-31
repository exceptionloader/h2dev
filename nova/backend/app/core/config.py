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

    # Rate limits: "<count>/<seconds>"
    rate_limit_login: str = "10/60"
    rate_limit_register: str = "5/60"
    rate_limit_password_reset: str = "5/300"
    # Disable only in automated tests that do not exercise rate limiting.
    rate_limit_enabled: bool = True

    @field_validator("allowed_origins", mode="before")
    @classmethod
    def _split_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [o.strip().rstrip("/") for o in value.split(",") if o.strip()]
        return value

    @property
    def is_production(self) -> bool:
        return self.environment == "production"


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    if settings.is_production and not settings.session_cookie_secure:
        raise RuntimeError("NOVA_SESSION_COOKIE_SECURE must be true in production")
    return settings
