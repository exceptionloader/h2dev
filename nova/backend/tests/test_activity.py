"""Voice tokens and states, gaming spaces, instant sessions and notes."""

from datetime import UTC, datetime, timedelta

import jwt

from tests.conftest import channel_by_name, create_server, invite_and_join, me, role_id


def enable_voice(monkeypatch):
    from app.core.config import get_settings

    s = get_settings()
    monkeypatch.setattr(s, "livekit_url", "ws://localhost:7880")
    monkeypatch.setattr(s, "livekit_api_key", "devkey")
    monkeypatch.setattr(s, "livekit_api_secret", "secret-secret-secret-secret-secret")


async def test_voice_token_requires_configuration(alice):
    sid = (await create_server(alice))["id"]
    lobby = await channel_by_name(alice, sid, "Lobby")
    res = await alice.post(f"/voice/channels/{lobby['id']}/token")
    assert res.status_code == 503 and res.json()["code"] == "voice_unavailable"


async def test_voice_token_claims_and_permissions(alice, bob, carol, monkeypatch):
    enable_voice(monkeypatch)
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    lobby = await channel_by_name(alice, sid, "Lobby")
    general = await channel_by_name(alice, sid, "allgemein")

    res = await bob.post(f"/voice/channels/{lobby['id']}/token")
    assert res.status_code == 200
    body = res.json()
    claims = jwt.decode(body["token"], "secret-secret-secret-secret-secret", algorithms=["HS256"])
    assert claims["iss"] == "devkey" and claims["sub"] == (await me(bob))["id"]
    assert claims["video"]["room"] == body["room"] and claims["video"]["roomJoin"] is True
    assert claims["video"]["canPublish"] is True
    assert claims["exp"] - claims["nbf"] <= 620
    assert "secret" not in res.text

    assert (await bob.post(f"/voice/channels/{general['id']}/token")).status_code == 400
    assert (await carol.post(f"/voice/channels/{lobby['id']}/token")).status_code == 404

    # Without SPEAK: listen-only token; without CONNECT_VOICE: no token.
    default = await role_id(alice, sid, "Mitglied")
    url = f"/servers/{sid}/channels/{lobby['id']}/overrides/roles/{default}"
    await alice.put(url, json={"deny": ["SPEAK"]})
    res = await bob.post(f"/voice/channels/{lobby['id']}/token")
    assert res.json()["can_speak"] is False
    await alice.put(url, json={"deny": ["CONNECT_VOICE"]})
    assert (await bob.post(f"/voice/channels/{lobby['id']}/token")).status_code == 403


async def test_voice_states(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    lobby = await channel_by_name(alice, sid, "Lobby")
    general = await channel_by_name(alice, sid, "allgemein")
    res = await bob.put("/voice/state", json={"channel_id": lobby["id"], "muted": True})
    assert res.status_code == 200 and res.json()["muted"] is True
    states = (await alice.get(f"/servers/{sid}/voice-states")).json()
    assert [s["channel_id"] for s in states] == [lobby["id"]]
    assert (await bob.put("/voice/state", json={"channel_id": general["id"]})).status_code == 400
    assert (await bob.put("/voice/state", json={"channel_id": None})).status_code == 200
    assert (await alice.get(f"/servers/{sid}/voice-states")).json() == []


async def test_spaces(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    assert (await bob.post(f"/servers/{sid}/spaces", json={"name": "Arena"})).status_code == 403
    res = await alice.post(
        f"/servers/{sid}/spaces", json={"name": "Arena", "description": "Unser Treffpunkt"}
    )
    assert res.status_code == 201
    space = res.json()
    assert [z["kind"] for z in space["zones"]] == ["lobby", "gaming", "chill", "strategy", "afk"]
    assert all(z["can_connect"] for z in space["zones"])

    # Zones are real voice channels inside a category named after the space.
    channels = (await bob.get(f"/servers/{sid}/channels")).json()
    category = next(c for c in channels if c["name"] == "Arena")
    assert {c["name"] for c in channels if c["parent_id"] == category["id"]} == {
        "Lobby",
        "Gaming",
        "Chill",
        "Strategie",
        "AFK",
    }
    # Zones whose channel is private disappear for members who can't see it.
    afk = next(z for z in space["zones"] if z["kind"] == "afk")
    default = await role_id(alice, sid, "Mitglied")
    await alice.put(
        f"/servers/{sid}/channels/{afk['channel_id']}/overrides/roles/{default}",
        json={"deny": ["VIEW_CHANNEL"]},
    )
    bob_space = (await bob.get(f"/servers/{sid}/spaces/{space['id']}")).json()
    assert "afk" not in [z["kind"] for z in bob_space["zones"]]

    res = await alice.patch(f"/servers/{sid}/spaces/{space['id']}", json={"name": "Neue Arena"})
    assert res.json()["name"] == "Neue Arena"
    assert (await alice.delete(f"/servers/{sid}/spaces/{space['id']}")).status_code == 204
    assert (await bob.get(f"/servers/{sid}/spaces")).json() == []
    assert not any(c["name"] == "Arena" for c in (await bob.get(f"/servers/{sid}/channels")).json())


async def test_sessions_lifecycle(alice, bob, carol):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    await invite_and_join(alice, carol, sid)
    lobby = await channel_by_name(alice, sid, "Lobby")
    res = await alice.post(
        f"/servers/{sid}/sessions",
        json={
            "game": "Valorant",
            "title": "Ranked – 1 Spieler gesucht",
            "max_participants": 2,
            "voice_channel_id": lobby["id"],
        },
    )
    assert res.status_code == 201
    session = res.json()
    assert session["participants"][0]["username"] == "alice" and not session["is_full"]
    assert session["voice_channel_name"] == "Lobby"

    home = (await bob.get("/sessions")).json()
    assert [s["id"] for s in home] == [session["id"]]

    res = await bob.post(f"/sessions/{session['id']}/join")
    assert res.status_code == 200 and res.json()["is_full"] is True
    res = await carol.post(f"/sessions/{session['id']}/join")
    assert res.status_code == 400 and res.json()["code"] == "session_full"
    assert (await bob.post(f"/sessions/{session['id']}/join")).status_code == 409
    assert (await bob.post(f"/sessions/{session['id']}/leave")).status_code == 200
    assert (await carol.post(f"/sessions/{session['id']}/join")).status_code == 200

    # Only creator or MANAGE_EVENTS can close
    assert (await carol.post(f"/sessions/{session['id']}/close")).status_code == 403
    res = await alice.post(f"/sessions/{session['id']}/close")
    assert res.json()["status"] == "closed"
    assert (await bob.post(f"/sessions/{session['id']}/join")).status_code == 400
    assert (await bob.get("/sessions")).json() == []


async def test_session_validation_and_isolation(alice, bob):
    sid = (await create_server(alice))["id"]
    other = (await create_server(bob, "Andere"))["id"]
    other_voice = await channel_by_name(bob, other, "Lobby")
    base = {"game": "CS2", "title": "Wingman", "max_participants": 2}
    assert (
        await alice.post(f"/servers/{sid}/sessions", json={**base, "max_participants": 1})
    ).status_code == 422
    res = await alice.post(
        f"/servers/{sid}/sessions", json={**base, "voice_channel_id": other_voice["id"]}
    )
    assert res.status_code == 400
    future = (datetime.now(UTC) + timedelta(days=30)).isoformat()
    res = await alice.post(f"/servers/{sid}/sessions", json={**base, "start_time": future})
    assert res.status_code == 400 and res.json()["code"] == "invalid_start_time"
    session = (await alice.post(f"/servers/{sid}/sessions", json=base)).json()
    assert (await bob.get(f"/sessions/{session['id']}")).status_code == 404
    assert (await bob.post(f"/sessions/{session['id']}/join")).status_code == 404


async def test_session_notes(alice, bob, carol):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    await invite_and_join(alice, carol, sid)
    session = (
        await alice.post(
            f"/servers/{sid}/sessions",
            json={"game": "LoL", "title": "Clash", "max_participants": 5},
        )
    ).json()
    url = f"/sessions/{session['id']}/notes"
    assert (
        await alice.post(url, json={"kind": "note", "content": "Bans: Yasuo"})
    ).status_code == 201
    res = await alice.post(
        url, json={"kind": "link", "content": "Build", "url": "https://example.com/build"}
    )
    assert res.status_code == 201
    assert (await alice.post(url, json={"kind": "link", "content": "kaputt"})).status_code == 400
    assert (
        await alice.post(url, json={"kind": "link", "content": "x", "url": "javascript:alert(1)"})
    ).status_code == 422
    task = (await alice.post(url, json={"kind": "task", "content": "Warm-up"})).json()
    followup = (
        await alice.post(
            url, json={"kind": "followup", "content": "Revanche", "due_at": "2026-12-01T19:00:00Z"}
        )
    ).json()
    assert followup["due_at"].startswith("2026-12-01")

    # Non-participants can read but not write
    assert len((await carol.get(url)).json()) == 4
    assert (await carol.post(url, json={"kind": "note", "content": "x"})).status_code == 403
    assert (await carol.patch(f"/notes/{task['id']}", json={"done": True})).status_code == 403
    await bob.post(f"/sessions/{session['id']}/join")
    res = await bob.patch(f"/notes/{task['id']}", json={"done": True})
    assert res.status_code == 200 and res.json()["done"] is True
    assert (await bob.patch(f"/notes/{task['id']}", json={"content": "hack"})).status_code == 403
    assert (await bob.delete(f"/notes/{task['id']}")).status_code == 403
    assert (await alice.delete(f"/notes/{task['id']}")).status_code == 204
