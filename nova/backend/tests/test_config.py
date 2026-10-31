import pytest

from app.core.config import Settings


def test_allowed_origins_from_comma_separated_env(monkeypatch):
    monkeypatch.setenv("NOVA_ALLOWED_ORIGINS", "http://localhost:5173, https://nova.example/")
    assert Settings().allowed_origins == ["http://localhost:5173", "https://nova.example"]


def test_production_requires_secure_cookies(monkeypatch):
    from app.core import config

    monkeypatch.setenv("NOVA_ENVIRONMENT", "production")
    monkeypatch.setenv("NOVA_SESSION_COOKIE_SECURE", "false")
    config.get_settings.cache_clear()
    try:
        with pytest.raises(RuntimeError):
            config.get_settings()
    finally:
        monkeypatch.undo()
        config.get_settings.cache_clear()
