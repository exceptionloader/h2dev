"""Server creation, ownership, membership isolation and channel access."""

import uuid

from sqlalchemy import select

from app.core.database import SessionLocal
from app.models import ChannelPermissionOverride, MemberRole, ServerMember, ServerRole, User


async def create_server(client, name="Squad", **extra) -> dict:
    res = await client.post("/servers", json={"name": name, **extra})
    assert res.status_code == 201, res.text
    return res.json()


async def add_member(server_id: str, username: str, role_name: str | None = None) -> None:
    """Add a user to a server directly (invites arrive in milestone 2)."""
    async with SessionLocal() as db:
        user = await db.scalar(select(User).where(User.username == username))
        member = ServerMember(server_id=uuid.UUID(server_id), user_id=user.id)
        db.add(member)
        await db.flush()
        if role_name:
            role = await db.scalar(
                select(ServerRole).where(
                    ServerRole.server_id == uuid.UUID(server_id), ServerRole.name == role_name
                )
            )
            db.add(MemberRole(member_id=member.id, role_id=role.id))
        await db.commit()


async def test_creator_becomes_owner_with_full_permissions(alice):
    me = (await alice.get("/users/me")).json()
    server = await create_server(alice, "Valorant Crew", description="Ranked jeden Abend")
    assert server["owner_id"] == me["id"]
    assert server["is_owner"] is True
    assert server["member_count"] == 1
    assert "MANAGE_SERVER" in server["my_permissions"]
    assert "ADMINISTRATOR" in server["my_permissions"]

    members = (await alice.get(f"/servers/{server['id']}/members")).json()
    assert len(members) == 1 and members[0]["is_owner"] and members[0]["user"]["id"] == me["id"]

    listed = (await alice.get("/servers")).json()
    assert [s["id"] for s in listed] == [server["id"]]


async def test_default_roles_and_channels_are_created(alice):
    server = await create_server(alice)
    roles = (await alice.get(f"/servers/{server['id']}/roles")).json()
    assert [r["name"] for r in roles] == ["Admin", "Moderator", "Mitglied"]
    assert [r["is_default"] for r in roles] == [False, False, True]
    assert "ADMINISTRATOR" not in roles[0]["permissions"]

    channels = (await alice.get(f"/servers/{server['id']}/channels")).json()
    by_name = {c["name"]: c for c in channels}
    assert by_name["allgemein"]["type"] == "text"
    assert by_name["ankündigungen"]["type"] == "announcement"
    assert by_name["Lobby"]["type"] == "voice"
    assert by_name["allgemein"]["parent_id"] == by_name["Textkanäle"]["id"]

    log = (await alice.get(f"/servers/{server['id']}/audit-logs")).json()
    assert [e["action"] for e in log] == ["server.create"]


async def test_non_members_cannot_see_server(alice, bob):
    server = await create_server(alice)
    for path in ("", "/members", "/roles", "/channels", "/audit-logs"):
        res = await bob.get(f"/servers/{server['id']}{path}")
        assert res.status_code == 404, path
    assert (await bob.get("/servers")).json() == []
    res = await bob.patch(f"/servers/{server['id']}", json={"name": "Hijacked"})
    assert res.status_code == 404


async def test_unauthenticated_requests_rejected(client):
    assert (await client.get("/servers")).status_code == 401
    assert (await client.post("/servers", json={"name": "x"})).status_code == 401


async def test_server_name_validation(alice):
    assert (await alice.post("/servers", json={"name": "   "})).status_code == 422
    assert (await alice.post("/servers", json={"name": "x" * 65})).status_code == 422


async def test_plain_member_cannot_manage_server(alice, bob):
    server = await create_server(alice)
    await add_member(server["id"], "bob")

    detail = (await bob.get(f"/servers/{server['id']}")).json()
    assert detail["is_owner"] is False
    assert "MANAGE_SERVER" not in detail["my_permissions"]

    res = await bob.patch(f"/servers/{server['id']}", json={"name": "Bobs Server"})
    assert res.status_code == 403 and res.json()["code"] == "missing_permission"
    res = await bob.post(f"/servers/{server['id']}/channels", json={"name": "spam", "type": "text"})
    assert res.status_code == 403
    res = await bob.get(f"/servers/{server['id']}/audit-logs")
    assert res.status_code == 403


async def test_admin_can_edit_and_change_is_audited(alice, bob):
    server = await create_server(alice)
    await add_member(server["id"], "bob", "Admin")
    res = await bob.patch(
        f"/servers/{server['id']}", json={"name": "Neuer Name", "description": "Hallo"}
    )
    assert res.status_code == 200 and res.json()["name"] == "Neuer Name"
    log = (await alice.get(f"/servers/{server['id']}/audit-logs")).json()
    assert log[0]["action"] == "server.update"
    assert log[0]["actor"]["username"] == "bob"
    assert log[0]["details"]["name"] == {"from": "Squad", "to": "Neuer Name"}


async def test_only_owner_can_delete_server_with_confirmation(alice, bob):
    server = await create_server(alice, "Delete Me")
    await add_member(server["id"], "bob", "Admin")
    url = f"/servers/{server['id']}"

    res = await bob.request("DELETE", url, json={"confirm_name": "Delete Me"})
    assert res.status_code == 403 and res.json()["code"] == "owner_only"

    res = await alice.request("DELETE", url, json={"confirm_name": "wrong"})
    assert res.status_code == 400 and res.json()["code"] == "confirm_name_mismatch"

    res = await alice.request("DELETE", url, json={"confirm_name": "Delete Me"})
    assert res.status_code == 204
    assert (await alice.get(url)).status_code == 404
    assert (await bob.get("/servers")).json() == []


async def test_owner_keeps_rights_without_any_role(alice):
    """Ownership is not a role: removing every role from the owner changes nothing."""
    server = await create_server(alice)
    async with SessionLocal() as db:
        default = await db.scalar(
            select(ServerRole).where(
                ServerRole.server_id == uuid.UUID(server["id"]), ServerRole.is_default.is_(True)
            )
        )
        default.permissions = 0  # strip even the default role
        await db.commit()
    detail = (await alice.get(f"/servers/{server['id']}")).json()
    assert "ADMINISTRATOR" in detail["my_permissions"]
    res = await alice.patch(f"/servers/{server['id']}", json={"name": "Still mine"})
    assert res.status_code == 200


async def test_private_channel_visibility(alice, bob):
    server = await create_server(alice)
    sid = server["id"]
    await add_member(sid, "bob")

    res = await alice.post(
        f"/servers/{sid}/channels", json={"name": "Team Intern", "type": "text", "private": True}
    )
    assert res.status_code == 201
    secret = res.json()
    assert secret["name"] == "team-intern" and secret["is_private"] is True

    alice_names = {c["name"] for c in (await alice.get(f"/servers/{sid}/channels")).json()}
    bob_names = {c["name"] for c in (await bob.get(f"/servers/{sid}/channels")).json()}
    assert "team-intern" in alice_names
    assert "team-intern" not in bob_names
    assert "allgemein" in bob_names


async def test_private_channel_visible_to_role_with_override(alice, bob):
    server = await create_server(alice)
    sid = server["id"]
    await add_member(sid, "bob", "Moderator")
    secret = (
        await alice.post(
            f"/servers/{sid}/channels", json={"name": "mods", "type": "text", "private": True}
        )
    ).json()
    assert "mods" not in {c["name"] for c in (await bob.get(f"/servers/{sid}/channels")).json()}

    async with SessionLocal() as db:
        mod = await db.scalar(
            select(ServerRole).where(
                ServerRole.server_id == uuid.UUID(sid), ServerRole.name == "Moderator"
            )
        )
        db.add(
            ChannelPermissionOverride(
                channel_id=uuid.UUID(secret["id"]),
                role_id=mod.id,
                allow=1,  # VIEW_CHANNEL
            )
        )
        await db.commit()
    assert "mods" in {c["name"] for c in (await bob.get(f"/servers/{sid}/channels")).json()}


async def test_announcement_channel_is_read_only_for_members(alice, bob):
    server = await create_server(alice)
    await add_member(server["id"], "bob")
    channels = (await bob.get(f"/servers/{server['id']}/channels")).json()
    ann = next(c for c in channels if c["type"] == "announcement")
    general = next(c for c in channels if c["name"] == "allgemein")
    assert "SEND_MESSAGES" not in ann["my_permissions"]
    assert "VIEW_CHANNEL" in ann["my_permissions"]
    assert "SEND_MESSAGES" in general["my_permissions"]


async def test_channel_parent_must_be_category_of_same_server(alice):
    s1 = await create_server(alice, "One")
    s2 = await create_server(alice, "Two")
    foreign_cat = next(
        c
        for c in (await alice.get(f"/servers/{s2['id']}/channels")).json()
        if c["type"] == "category"
    )
    res = await alice.post(
        f"/servers/{s1['id']}/channels",
        json={"name": "x", "type": "text", "parent_id": foreign_cat["id"]},
    )
    assert res.status_code == 400 and res.json()["code"] == "invalid_parent"

    own_text = next(
        c for c in (await alice.get(f"/servers/{s1['id']}/channels")).json() if c["type"] == "text"
    )
    res = await alice.post(
        f"/servers/{s1['id']}/channels",
        json={"name": "x", "type": "text", "parent_id": own_text["id"]},
    )
    assert res.status_code == 400


async def test_moderator_creating_private_channel_keeps_access(alice, bob):
    server = await create_server(alice)
    sid = server["id"]
    await add_member(sid, "bob", "Moderator")
    async with SessionLocal() as db:
        mod = await db.scalar(
            select(ServerRole).where(
                ServerRole.server_id == uuid.UUID(sid), ServerRole.name == "Moderator"
            )
        )
        mod.permissions |= 1 << 11  # MANAGE_CHANNELS
        await db.commit()
    res = await bob.post(
        f"/servers/{sid}/channels", json={"name": "mod-only", "type": "text", "private": True}
    )
    assert res.status_code == 201
    assert "mod-only" in {c["name"] for c in (await bob.get(f"/servers/{sid}/channels")).json()}
