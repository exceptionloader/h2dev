import logging
import re

import pytest

from tests.conftest import PASSWORD, make_client, register


async def test_register_sets_session_cookie_and_returns_profile(client):
    data = await register(client, "Nova_Fan", "Fan@Example.com")
    assert data["username"] == "nova_fan"
    assert data["email"] == "fan@example.com"
    assert data["display_name"] == "nova_fan"
    assert "password_hash" not in data

    cookie = next(c for c in client.cookies.jar if c.name == "nova_session")
    assert cookie.has_nonstandard_attr("HttpOnly")

    me = await client.get("/users/me")
    assert me.status_code == 200
    assert me.json()["id"] == data["id"]


async def test_password_is_stored_as_argon2id_hash(client):
    from sqlalchemy import select

    from app.core.database import SessionLocal
    from app.models import User

    await register(client, "hashme")
    async with SessionLocal() as db:
        user = await db.scalar(select(User).where(User.username == "hashme"))
    assert user.password_hash.startswith("$argon2id$")
    assert PASSWORD not in user.password_hash


@pytest.mark.parametrize(
    "payload",
    [
        {"username": "ab", "email": "a@example.com", "password": PASSWORD},
        {"username": "bad name", "email": "a@example.com", "password": PASSWORD},
        {"username": "valid", "email": "not-an-email", "password": PASSWORD},
        {"username": "valid", "email": "a@example.com", "password": "short"},
    ],
)
async def test_register_validates_input(client, payload):
    res = await client.post("/auth/register", json=payload)
    assert res.status_code == 422
    assert res.json()["code"] == "validation_error"


async def test_duplicate_username_and_email_rejected(client):
    await register(client, "taken")
    res = await client.post(
        "/auth/register",
        json={"username": "TAKEN", "email": "other@example.com", "password": PASSWORD},
    )
    assert res.status_code == 409 and res.json()["code"] == "username_taken"
    res = await client.post(
        "/auth/register",
        json={"username": "other", "email": "taken@example.com", "password": PASSWORD},
    )
    assert res.status_code == 409 and res.json()["code"] == "email_taken"


async def test_login_with_username_or_email_and_logout(client):
    await register(client, "logmein")
    async with make_client() as c:
        assert (await c.get("/users/me")).status_code == 401
        for login in ("logmein", "LOGMEIN@example.com"):
            res = await c.post("/auth/login", json={"login": login, "password": PASSWORD})
            assert res.status_code == 200, res.text
        assert (await c.get("/users/me")).status_code == 200
        assert (await c.post("/auth/logout")).status_code == 204
        assert (await c.get("/users/me")).status_code == 401


async def test_wrong_password_and_unknown_user_look_identical(client):
    await register(client, "victim")
    wrong = await client.post("/auth/login", json={"login": "victim", "password": "nope-nope"})
    unknown = await client.post("/auth/login", json={"login": "ghost", "password": "nope-nope"})
    assert wrong.status_code == unknown.status_code == 401
    assert wrong.json() == unknown.json()


async def test_logout_invalidates_token_server_side(client):
    await register(client, "stolen")
    token = client.cookies.get("nova_session")
    await client.post("/auth/logout")
    async with make_client() as attacker:
        attacker.cookies.set("nova_session", token)
        assert (await attacker.get("/users/me")).status_code == 401


async def test_forged_token_rejected(client):
    client.cookies.set("nova_session", "x" * 43)
    assert (await client.get("/users/me")).status_code == 401


async def test_update_profile(alice):
    res = await alice.patch(
        "/users/me",
        json={"display_name": "  Alice   W. ", "status": "dnd", "custom_status": "Ranked 🎮"},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["display_name"] == "Alice W."
    assert body["status"] == "dnd"
    assert body["custom_status"] == "Ranked 🎮"
    res = await alice.patch("/users/me", json={"status": "busy"})
    assert res.status_code == 422


async def test_change_password_revokes_other_sessions(alice):
    async with make_client() as other_device:
        await other_device.post("/auth/login", json={"login": "alice", "password": PASSWORD})
        assert (await other_device.get("/users/me")).status_code == 200

        res = await alice.post(
            "/users/me/password",
            json={"current_password": "wrong-password", "new_password": "neues-passwort-123"},
        )
        assert res.status_code == 400 and res.json()["code"] == "invalid_current_password"

        res = await alice.post(
            "/users/me/password",
            json={"current_password": PASSWORD, "new_password": "neues-passwort-123"},
        )
        assert res.status_code == 204
        assert (await alice.get("/users/me")).status_code == 200  # current session kept
        assert (await other_device.get("/users/me")).status_code == 401


async def test_password_reset_flow(alice, caplog):
    with caplog.at_level(logging.INFO, logger="nova.mail"):
        res = await alice.post("/auth/password-reset/request", json={"email": "alice@example.com"})
    assert res.status_code == 202
    body = next(r.body for r in caplog.records if getattr(r, "to", None) == "alice@example.com")
    token = re.search(r"token=([\w-]+)", body).group(1)

    async with make_client() as c:
        res = await c.post(
            "/auth/password-reset/confirm",
            json={"token": token, "new_password": "nach-dem-reset-1"},
        )
        assert res.status_code == 204
        # Token is single-use.
        res = await c.post(
            "/auth/password-reset/confirm",
            json={"token": token, "new_password": "nochmal-reset-12"},
        )
        assert res.status_code == 400 and res.json()["code"] == "invalid_reset_token"
        # All sessions were revoked; new password works.
        assert (await alice.get("/users/me")).status_code == 401
        res = await c.post("/auth/login", json={"login": "alice", "password": "nach-dem-reset-1"})
        assert res.status_code == 200


async def test_password_reset_does_not_reveal_accounts(client):
    res = await client.post("/auth/password-reset/request", json={"email": "nobody@example.com"})
    assert res.status_code == 202


@pytest.mark.rate_limit
async def test_login_is_rate_limited(client):
    await register(client, "target")
    codes = [
        (
            await client.post("/auth/login", json={"login": "target", "password": "wrong-pw"})
        ).status_code
        for _ in range(11)
    ]
    assert codes[:10] == [401] * 10
    assert codes[10] == 429


async def test_cross_origin_post_is_rejected(client):
    res = await client.post(
        "/auth/login",
        json={"login": "x", "password": "y"},
        headers={"Origin": "https://evil.example"},
    )
    assert res.status_code == 403 and res.json()["code"] == "bad_origin"
    res = await client.post(
        "/auth/login",
        json={"login": "x", "password": "y"},
        headers={"Origin": "http://localhost:5173"},
    )
    assert res.status_code == 401
