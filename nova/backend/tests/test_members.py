"""Invites, joining, kick/ban, roles, hierarchy/escalation and ownership transfer."""

from tests.conftest import PASSWORD, create_server, grant_role, invite_and_join, me, role_id


async def test_invite_flow_and_limits(alice, bob, carol):
    sid = (await create_server(alice))["id"]
    res = await alice.post(f"/servers/{sid}/invites", json={"max_uses": 1})
    assert res.status_code == 201
    code = res.json()["code"]

    preview = (await bob.get(f"/invites/{code}")).json()
    assert preview["server"]["name"] == "Squad" and preview["already_member"] is False
    assert (await bob.post(f"/invites/{code}/accept")).status_code == 200
    assert [s["id"] for s in (await bob.get("/servers")).json()] == [sid]

    # max_uses reached
    assert (await carol.post(f"/invites/{code}/accept")).status_code == 404
    assert (await carol.get(f"/invites/{code}")).status_code == 404
    assert (await carol.get("/invites/doesnotexist")).status_code == 404

    members = (await alice.get(f"/servers/{sid}/members")).json()
    assert len(members) == 2
    log = [e["action"] for e in (await alice.get(f"/servers/{sid}/audit-logs")).json()]
    assert "member.join" in log and "invite.create" in log


async def test_invite_requires_permission(alice, bob, carol):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    # Strip CREATE_INVITES from the default role.
    default = await role_id(alice, sid, "Mitglied")
    res = await alice.patch(
        f"/servers/{sid}/roles/{default}",
        json={"permissions": ["VIEW_CHANNEL", "SEND_MESSAGES"]},
    )
    assert res.status_code == 200
    res = await bob.post(f"/servers/{sid}/invites", json={})
    assert res.status_code == 403


async def test_kick_and_ban_respect_hierarchy(alice, bob, carol):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    await invite_and_join(alice, carol, sid)
    bob_id, carol_id, alice_id = (
        (await me(bob))["id"],
        (await me(carol))["id"],
        (await me(alice))["id"],
    )

    # Plain member cannot kick.
    assert (await carol.delete(f"/servers/{sid}/members/{bob_id}")).status_code == 403
    await grant_role(alice, sid, bob_id, "Moderator")
    # Moderator cannot touch the owner, but can kick a plain member.
    assert (await bob.delete(f"/servers/{sid}/members/{alice_id}")).status_code == 403
    assert (await bob.post(f"/servers/{sid}/bans", json={"user_id": alice_id})).status_code == 403
    assert (await bob.delete(f"/servers/{sid}/members/{carol_id}")).status_code == 204
    assert (await carol.get(f"/servers/{sid}")).status_code == 404

    # Ban: removed and cannot rejoin until unbanned.
    await invite_and_join(alice, carol, sid)
    res = await bob.post(f"/servers/{sid}/bans", json={"user_id": carol_id, "reason": "Spam"})
    assert res.status_code == 204
    code = (await alice.post(f"/servers/{sid}/invites", json={})).json()["code"]
    res = await carol.post(f"/invites/{code}/accept")
    assert res.status_code == 403 and res.json()["code"] == "banned"
    bans = (await bob.get(f"/servers/{sid}/bans")).json()
    assert bans[0]["user"]["username"] == "carol" and bans[0]["reason"] == "Spam"
    assert (await bob.delete(f"/servers/{sid}/bans/{carol_id}")).status_code == 204
    assert (await carol.post(f"/invites/{code}/accept")).status_code == 200


async def test_equal_rank_cannot_moderate_each_other(alice, bob, carol):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    await invite_and_join(alice, carol, sid)
    bob_id, carol_id = (await me(bob))["id"], (await me(carol))["id"]
    await grant_role(alice, sid, bob_id, "Moderator")
    await grant_role(alice, sid, carol_id, "Moderator")
    assert (await bob.delete(f"/servers/{sid}/members/{carol_id}")).status_code == 403


async def test_leave_and_owner_cannot_leave(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    assert (await bob.delete(f"/servers/{sid}/members/@me")).status_code == 204
    assert (await bob.get("/servers")).json() == []
    res = await alice.delete(f"/servers/{sid}/members/@me")
    assert res.status_code == 400 and res.json()["code"] == "owner_cannot_leave"


async def test_role_crud_and_assignment(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    bob_id = (await me(bob))["id"]
    res = await alice.post(
        f"/servers/{sid}/roles",
        json={"name": "Coach", "color": "#22C55E", "permissions": ["MANAGE_EVENTS"]},
    )
    assert res.status_code == 201
    coach = res.json()
    assert coach["position"] == 1 and coach["permissions"] == ["MANAGE_EVENTS"]
    roles = (await alice.get(f"/servers/{sid}/roles")).json()
    assert [r["name"] for r in roles] == ["Admin", "Moderator", "Coach", "Mitglied"]

    assert (
        await alice.put(f"/servers/{sid}/members/{bob_id}/roles/{coach['id']}")
    ).status_code == 204
    members = (await alice.get(f"/servers/{sid}/members")).json()
    assert coach["id"] in next(m for m in members if m["user"]["id"] == bob_id)["role_ids"]
    assert "MANAGE_EVENTS" in (await bob.get(f"/servers/{sid}")).json()["my_permissions"]

    res = await alice.post(f"/servers/{sid}/roles/{coach['id']}/move", json={"direction": "up"})
    assert res.status_code == 204
    roles = (await alice.get(f"/servers/{sid}/roles")).json()
    assert [r["name"] for r in roles] == ["Admin", "Coach", "Moderator", "Mitglied"]

    res = await alice.post(f"/servers/{sid}/roles", json={"name": "coach"})
    assert res.status_code == 409
    res = await alice.post(f"/servers/{sid}/roles", json={"name": "X", "permissions": ["NOPE"]})
    assert res.status_code == 400 and res.json()["code"] == "unknown_permission"

    assert (await alice.delete(f"/servers/{sid}/roles/{coach['id']}")).status_code == 204
    assert "MANAGE_EVENTS" not in (await bob.get(f"/servers/{sid}")).json()["my_permissions"]
    default = await role_id(alice, sid, "Mitglied")
    assert (await alice.delete(f"/servers/{sid}/roles/{default}")).status_code == 400


async def test_privilege_escalation_is_blocked(alice, bob, carol):
    """A role manager cannot create, grant or assign powers above their own."""
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    await invite_and_join(alice, carol, sid)
    bob_id = (await me(bob))["id"]
    res = await alice.post(
        f"/servers/{sid}/roles",
        json={"name": "Rollenwart", "permissions": ["MANAGE_ROLES", "KICK_MEMBERS"]},
    )
    manager = res.json()
    # Put it above Moderator so Bob can manage Moderator but not Admin.
    await alice.post(f"/servers/{sid}/roles/{manager['id']}/move", json={"direction": "up"})
    await grant_role(alice, sid, bob_id, "Rollenwart")

    # Creating a role with permissions Bob lacks
    res = await bob.post(
        f"/servers/{sid}/roles", json={"name": "Evil", "permissions": ["BAN_MEMBERS"]}
    )
    assert res.status_code == 403 and res.json()["code"] == "escalation"
    res = await bob.post(
        f"/servers/{sid}/roles", json={"name": "Godmode", "permissions": ["ADMINISTRATOR"]}
    )
    assert res.status_code == 403
    # Editing the Admin role (above him)
    admin = await role_id(alice, sid, "Admin")
    res = await bob.patch(f"/servers/{sid}/roles/{admin}", json={"name": "Pwned"})
    assert res.status_code == 403 and res.json()["code"] == "hierarchy"
    # Assigning Admin (above him) to himself
    res = await bob.put(f"/servers/{sid}/members/{bob_id}/roles/{admin}")
    assert res.status_code == 403
    # Assigning Moderator (below him but with BAN_MEMBERS he lacks) to himself
    mod = await role_id(alice, sid, "Moderator")
    res = await bob.put(f"/servers/{sid}/members/{bob_id}/roles/{mod}")
    assert res.status_code == 403 and res.json()["code"] == "escalation"
    # Adding BAN_MEMBERS to his own role
    res = await bob.patch(
        f"/servers/{sid}/roles/{manager['id']}",
        json={"permissions": ["MANAGE_ROLES", "KICK_MEMBERS", "BAN_MEMBERS"]},
    )
    assert res.status_code == 403
    # Allowed: a role below him with permissions he holds
    res = await bob.post(
        f"/servers/{sid}/roles", json={"name": "Helfer", "permissions": ["KICK_MEMBERS"]}
    )
    assert res.status_code == 201
    carol_id = (await me(carol))["id"]
    res = await bob.put(f"/servers/{sid}/members/{carol_id}/roles/{res.json()['id']}")
    assert res.status_code == 204


async def test_ownership_transfer(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    bob_id = (await me(bob))["id"]
    url = f"/servers/{sid}/transfer-ownership"

    res = await bob.post(url, json={"new_owner_id": bob_id, "password": PASSWORD})
    assert res.status_code == 403 and res.json()["code"] == "owner_only"
    res = await alice.post(url, json={"new_owner_id": bob_id, "password": "wrong-password"})
    assert res.status_code == 400 and res.json()["code"] == "invalid_current_password"

    res = await alice.post(url, json={"new_owner_id": bob_id, "password": PASSWORD})
    assert res.status_code == 204
    assert (await bob.get(f"/servers/{sid}")).json()["is_owner"] is True
    alice_detail = (await alice.get(f"/servers/{sid}")).json()
    assert alice_detail["is_owner"] is False
    assert "MANAGE_SERVER" not in alice_detail["my_permissions"]
    # New owner can now kick the old one; old owner can't delete the server.
    res = await alice.request("DELETE", f"/servers/{sid}", json={"confirm_name": "Squad"})
    assert res.status_code == 403
    log = (await bob.get(f"/servers/{sid}/audit-logs")).json()
    assert log[0]["action"] == "ownership.transfer"


async def test_discover_and_join_public_server(alice, bob):
    public = await create_server(alice, "Offene Runde", visibility="public")
    private = await create_server(alice, "Geheim")
    found = (await bob.get("/discover/servers")).json()
    assert [s["name"] for s in found] == ["Offene Runde"]
    assert found[0]["member_count"] == 1 and found[0]["is_member"] is False
    assert (await bob.get("/discover/servers", params={"q": "geheim"})).json() == []
    assert (await bob.post(f"/servers/{private['id']}/join")).status_code == 404
    assert (await bob.post(f"/servers/{public['id']}/join")).status_code == 200
    assert (await bob.post(f"/servers/{public['id']}/join")).status_code == 409


async def test_channel_overrides_and_management(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    channels = (await alice.get(f"/servers/{sid}/channels")).json()
    general = next(c for c in channels if c["name"] == "allgemein")
    default = await role_id(alice, sid, "Mitglied")
    url = f"/servers/{sid}/channels/{general['id']}/overrides/roles/{default}"

    res = await alice.put(url, json={"deny": ["SEND_MESSAGES"]})
    assert res.status_code == 204
    bob_view = next(
        c for c in (await bob.get(f"/servers/{sid}/channels")).json() if c["id"] == general["id"]
    )
    assert "SEND_MESSAGES" not in bob_view["my_permissions"]
    res = await alice.put(url, json={"allow": ["MANAGE_SERVER"]})
    assert res.status_code == 400 and res.json()["code"] == "override_scope"
    assert (await alice.put(url, json={})).status_code == 204  # removes override
    overrides = (await alice.get(f"/servers/{sid}/channels/{general['id']}/overrides")).json()
    assert overrides == []

    res = await bob.patch(f"/servers/{sid}/channels/{general['id']}", json={"name": "x"})
    assert res.status_code == 403
    res = await alice.patch(
        f"/servers/{sid}/channels/{general['id']}", json={"name": "Lobby Chat", "topic": "Hi"}
    )
    assert res.status_code == 200 and res.json()["name"] == "lobby-chat"
    assert (await alice.delete(f"/servers/{sid}/channels/{general['id']}")).status_code == 204
    assert (await bob.get(f"/channels/{general['id']}")).status_code == 404
