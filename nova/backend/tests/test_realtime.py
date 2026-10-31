"""WebSocket gateway: authentication, event fan-out with permission filtering, presence."""

import asyncio
import json

import pytest

from tests.conftest import create_server, invite_and_join, me, ws_connect


async def next_event(ws, event_type: str, timeout: float = 3.0, **match) -> dict:
    async def wait():
        while True:
            msg = json.loads(await ws.receive_text())
            if msg["type"] == event_type and all(msg["data"].get(k) == v for k, v in match.items()):
                return msg["data"]

    return await asyncio.wait_for(wait(), timeout)


async def close_code(c=None, **kwargs) -> int | None:
    """Connect and return the close code the server answered with (None = stayed open)."""
    from httpx_ws import WebSocketDisconnect

    try:
        async with ws_connect(c, **kwargs) as ws:
            await asyncio.wait_for(ws.receive_text(), 2)
            await asyncio.wait_for(ws.receive_text(), 0.5)
    except WebSocketDisconnect as exc:
        return exc.code
    except ExceptionGroup as group:
        for exc in group.exceptions:
            if isinstance(exc, WebSocketDisconnect):
                return exc.code
        raise
    except TimeoutError:
        return None
    return None


async def test_gateway_requires_auth():
    assert await close_code() == 4401


async def test_gateway_rejects_foreign_origin(alice):
    assert await close_code(alice, headers={"Origin": "https://evil.example"}) == 4403
    assert await close_code(alice) is None  # same client without a foreign Origin stays open


async def test_message_events_are_permission_filtered(alice, bob, carol):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    secret = (
        await alice.post(
            f"/servers/{sid}/channels", json={"name": "geheim", "type": "text", "private": True}
        )
    ).json()
    general = next(
        c for c in (await alice.get(f"/servers/{sid}/channels")).json() if c["name"] == "allgemein"
    )

    async with ws_connect(bob) as bob_ws, ws_connect(carol) as carol_ws:
        assert (await next_event(bob_ws, "ready"))["user_id"] == (await me(bob))["id"]
        await next_event(carol_ws, "ready")

        await alice.post(f"/channels/{secret['id']}/messages", json={"content": "geheim"})
        await alice.post(f"/channels/{general['id']}/messages", json={"content": "öffentlich"})
        event = await next_event(bob_ws, "message.created")
        # The private message was never delivered to Bob.
        assert event["content"] == "öffentlich"
        with pytest.raises(asyncio.TimeoutError):
            await next_event(carol_ws, "message.created", timeout=0.3)


async def test_presence_updates(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    bob_id = (await me(bob))["id"]
    async with ws_connect(alice) as alice_ws:
        await next_event(alice_ws, "ready")
        async with ws_connect(bob) as bob_ws:
            await next_event(bob_ws, "ready")
            event = await next_event(alice_ws, "presence.updated", user_id=bob_id)
            assert event["presence"] == "online"
            members = (await alice.get(f"/servers/{sid}/members")).json()
            bob_member = next(m for m in members if m["user"]["username"] == "bob")
            assert bob_member["user"]["presence"] == "online"

            await bob.patch("/users/me", json={"status": "invisible"})
            event = await next_event(alice_ws, "presence.updated", user_id=bob_id)
            assert event["presence"] == "offline"
            await bob.patch("/users/me", json={"status": "dnd"})
            event = await next_event(alice_ws, "presence.updated", user_id=bob_id)
            assert event["presence"] == "dnd"
        event = await next_event(alice_ws, "presence.updated", user_id=bob_id)
        assert event["presence"] == "offline"


async def test_kicked_member_is_notified(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    bob_id = (await me(bob))["id"]
    async with ws_connect(bob) as bob_ws:
        await next_event(bob_ws, "ready")
        await alice.delete(f"/servers/{sid}/members/{bob_id}")
        event = await next_event(bob_ws, "server.removed")
        assert event == {"server_id": sid, "reason": "kicked"}


async def test_logout_closes_socket(alice):
    from httpx_ws import WebSocketDisconnect

    async with ws_connect(alice) as ws:
        await next_event(ws, "ready")
        await alice.post("/auth/logout")
        with pytest.raises(WebSocketDisconnect) as exc:
            while True:  # skip queued events until the close frame arrives
                await asyncio.wait_for(ws.receive_text(), 3)
        assert exc.value.code == 4001
