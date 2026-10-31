"""Bot applications: tokens, installation permissions and API access as a bot."""

import httpx

from app.main import app
from tests.conftest import channel_by_name, create_server, invite_and_join


def bot_client(token: str) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test/api/v1",
        headers={"Authorization": f"Bot {token}"},
    )


async def test_bot_lifecycle_and_permissions(alice, bob):
    res = await alice.post(
        "/bots", json={"name": "Stats Bot", "description": "K/D", "is_public": False}
    )
    assert res.status_code == 201
    bot = res.json()
    token = bot["token"]
    assert token.startswith("nb_") and bot["user"]["is_bot"] is True

    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    general = await channel_by_name(alice, sid, "allgemein")

    async with bot_client(token) as bc:
        # Not installed yet: no access.
        assert (await bc.get(f"/servers/{sid}")).status_code == 404
        # Bots cannot use human-only endpoints.
        assert (await bc.get("/friends")).status_code == 403
        assert (await bc.post("/servers", json={"name": "x"})).status_code == 403

        # Bob (no MANAGE_BOTS) cannot install; Alice can't grant ADMINISTRATOR.
        res = await bob.post(f"/servers/{sid}/bots", json={"bot_id": bot["id"], "permissions": []})
        assert res.status_code == 403
        res = await alice.post(
            f"/servers/{sid}/bots", json={"bot_id": bot["id"], "permissions": ["ADMINISTRATOR"]}
        )
        assert res.status_code == 400
        res = await alice.post(
            f"/servers/{sid}/bots",
            json={"bot_id": bot["id"], "permissions": ["VIEW_CHANNEL", "SEND_MESSAGES"]},
        )
        assert res.status_code == 204

        res = await bc.post(
            f"/channels/{general['id']}/messages", json={"content": "Hallo, ich bin ein Bot"}
        )
        assert res.status_code == 201 and res.json()["author"]["is_bot"] is True
        ann = await channel_by_name(alice, sid, "ankündigungen")
        assert (
            await bc.post(f"/channels/{ann['id']}/messages", json={"content": "x"})
        ).status_code == 403
        # Not granted: MANAGE_MESSAGES → cannot delete others' messages
        msg = (await bob.post(f"/channels/{general['id']}/messages", json={"content": "hi"})).json()
        assert (await bc.delete(f"/messages/{msg['id']}")).status_code == 403

        # Managed role cannot be edited or assigned manually
        roles = (await alice.get(f"/servers/{sid}/roles")).json()
        managed = next(r for r in roles if r["name"].startswith("Stats Bot"))
        assert (
            await alice.patch(f"/servers/{sid}/roles/{managed['id']}", json={"permissions": []})
        ).status_code == 403

        installed = (await alice.get(f"/servers/{sid}/bots")).json()
        assert installed[0]["permissions"] == ["VIEW_CHANNEL", "SEND_MESSAGES"]

        # Uninstall removes membership and role
        assert (await alice.delete(f"/servers/{sid}/bots/{bot['id']}")).status_code == 204
        assert (await bc.get(f"/servers/{sid}")).status_code == 404
        roles = (await alice.get(f"/servers/{sid}/roles")).json()
        assert not any(r["name"].startswith("Stats Bot") for r in roles)

        # Token regeneration invalidates the old token
        new = (await alice.post(f"/bots/{bot['id']}/token")).json()["token"]
        assert (await bc.get(f"/servers/{sid}/channels")).status_code in (401, 404)
        assert (await bc.get("/servers")).status_code == 401
    async with bot_client(new) as bc2:
        assert (await bc2.get("/servers")).status_code == 200


async def test_private_bots_only_installable_by_owner(alice, bob):
    bot = (await bob.post("/bots", json={"name": "Privat"})).json()
    sid = (await create_server(alice))["id"]
    available = (await alice.get(f"/servers/{sid}/bots/available")).json()
    assert bot["id"] not in [b["id"] for b in available]
    res = await alice.post(f"/servers/{sid}/bots", json={"bot_id": bot["id"], "permissions": []})
    assert res.status_code == 404


async def test_bot_cannot_log_in(alice):
    bot = (await alice.post("/bots", json={"name": "Login Bot"})).json()
    res = await alice.post("/auth/login", json={"login": bot["user"]["username"], "password": "!"})
    assert res.status_code == 401


async def test_first_party_bot_bootstrap_and_kick(alice):
    from app.core.database import SessionLocal
    from app.services.bot_service import ensure_first_party_bot

    async with SessionLocal() as db:
        await ensure_first_party_bot(db, "nb_first_party_test_token")
        await db.commit()
    async with SessionLocal() as db:  # idempotent, rotates token
        await ensure_first_party_bot(db, "nb_first_party_test_token_2")
        await db.commit()

    sid = (await create_server(alice))["id"]
    available = (await alice.get(f"/servers/{sid}/bots/available")).json()
    nova = next(b for b in available if b["is_first_party"])
    assert nova["name"] == "NOVA Bot"
    await alice.post(
        f"/servers/{sid}/bots",
        json={"bot_id": nova["id"], "permissions": ["VIEW_CHANNEL", "SEND_MESSAGES"]},
    )
    async with bot_client("nb_first_party_test_token") as old:
        assert (await old.get("/servers")).status_code == 401
    async with bot_client("nb_first_party_test_token_2") as bc:
        assert [s["id"] for s in (await bc.get("/servers")).json()] == [sid]
        # Kicking the bot removes the installation too
        assert (
            await alice.delete(f"/servers/{sid}/members/{nova['user']['id']}")
        ).status_code == 204
        assert (await alice.get(f"/servers/{sid}/bots")).json() == []
        assert (await bc.get(f"/servers/{sid}")).status_code == 404
