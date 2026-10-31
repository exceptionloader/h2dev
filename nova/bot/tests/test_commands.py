import random

from nova_bot.commands import format_sessions, handle, parse


def rng() -> random.Random:
    return random.Random(42)


def test_parse_ignores_normal_messages():
    assert parse("hallo") is None
    assert parse("/") is None
    assert parse("/Würfel 2d6") == ("wuerfel", ["2d6"])


def test_dice():
    reply = handle("/würfel 3d6", rng())
    assert reply.text.startswith("🎲 3d6: **")
    total = int(reply.text.split("**")[1])
    assert 3 <= total <= 18
    assert "Format" in handle("/würfel 99d6", rng()).text
    assert "Format" in handle("/würfel abc", rng()).text
    assert handle("/wuerfel", rng()).text.startswith("🎲 1d6")


def test_coin_teams_help_ping():
    assert handle("/münze", rng()).text in ("🪙 Kopf!", "🪙 Zahl!")
    teams = handle("/teams Anna Ben Chris Dana Emil", rng()).text
    assert teams.count(",") == 3 and "Team A" in teams and "Team B" in teams
    assert "mindestens zwei" in handle("/teams Anna", rng()).text
    assert "/würfel" in handle("/hilfe", rng()).text
    assert handle("/ping").text.startswith("Pong")
    assert handle("/unbekannt") is None


def test_sessions():
    assert handle("/sessions").needs_sessions is True
    text = format_sessions(
        [{"game": "Valorant", "title": "Ranked", "max_participants": 5, "participants": [1, 2]}]
    )
    assert "Valorant – Ranked (3 frei)" in text
    assert "keine offenen" in format_sessions([])
