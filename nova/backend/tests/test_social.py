"""Friends, blocking, direct messages, session invites and reports."""

from tests.conftest import channel_by_name, create_server, invite_and_join, me


async def befriend(a, b, username_b: str) -> None:
    assert (await a.post("/friends/requests", json={"username": username_b})).status_code == 204
    req = (await b.get("/friends")).json()["incoming"][0]
    assert (await b.post(f"/friends/requests/{req['id']}/accept")).status_code == 204


async def test_friend_request_flow(alice, bob):
    assert (await alice.post("/friends/requests", json={"username": "BOB"})).status_code == 204
    assert (await alice.post("/friends/requests", json={"username": "bob"})).status_code == 409
    out = (await alice.get("/friends")).json()
    assert out["outgoing"][0]["user"]["username"] == "bob"
    incoming = (await bob.get("/friends")).json()["incoming"]
    assert incoming[0]["user"]["username"] == "alice"
    assert (await alice.post(f"/friends/requests/{incoming[0]['id']}/accept")).status_code == 404
    assert (await bob.post(f"/friends/requests/{incoming[0]['id']}/accept")).status_code == 204
    assert [f["username"] for f in (await alice.get("/friends")).json()["friends"]] == ["bob"]

    bob_id = (await me(bob))["id"]
    assert (await alice.delete(f"/friends/{bob_id}")).status_code == 204
    assert (await bob.get("/friends")).json()["friends"] == []


async def test_mutual_request_auto_accepts_and_errors(alice, bob):
    await alice.post("/friends/requests", json={"username": "bob"})
    await bob.post("/friends/requests", json={"username": "alice"})
    assert len((await alice.get("/friends")).json()["friends"]) == 1
    assert (await alice.post("/friends/requests", json={"username": "alice"})).status_code == 400
    assert (await alice.post("/friends/requests", json={"username": "ghost"})).status_code == 404


async def test_block_prevents_requests_and_dms(alice, bob):
    await befriend(alice, bob, "bob")
    bob_id, alice_id = (await me(bob))["id"], (await me(alice))["id"]
    dm = (await alice.post("/dms", json={"user_id": bob_id})).json()
    assert (await bob.put(f"/blocks/{alice_id}")).status_code == 204
    assert (await alice.get("/friends")).json()["friends"] == []
    assert (await bob.get("/friends")).json()["blocked"][0]["username"] == "alice"
    # Blocked: no request (indistinguishable from unknown user), no DMs
    assert (await alice.post("/friends/requests", json={"username": "bob"})).status_code == 404
    res = await alice.post(f"/dms/{dm['id']}/messages", json={"content": "hey"})
    assert res.status_code == 403 and res.json()["code"] == "dm_blocked"
    assert (await bob.delete(f"/blocks/{alice_id}")).status_code == 204
    assert (await alice.post("/friends/requests", json={"username": "bob"})).status_code == 204


async def test_direct_messages(alice, bob, carol):
    bob_id = (await me(bob))["id"]
    res = await alice.post("/dms", json={"user_id": bob_id})
    assert res.status_code == 403 and res.json()["code"] == "dm_not_friends"
    await befriend(alice, bob, "bob")
    dm = (await alice.post("/dms", json={"user_id": bob_id})).json()
    assert (await alice.post("/dms", json={"user_id": bob_id})).json()["id"] == dm["id"]

    msg = (
        await alice.post(f"/dms/{dm['id']}/messages", json={"content": "Bock auf ne Runde?"})
    ).json()
    await bob.post(f"/dms/{dm['id']}/messages", json={"content": "Klar!"})
    listed = (await bob.get(f"/dms/{dm['id']}/messages")).json()
    assert [m["content"] for m in listed] == ["Bock auf ne Runde?", "Klar!"]
    overview = (await bob.get("/dms")).json()
    assert (
        overview[0]["user"]["username"] == "alice"
        and overview[0]["last_message"]["content"] == "Klar!"
    )

    assert (await carol.get(f"/dms/{dm['id']}/messages")).status_code == 404
    assert (await carol.post(f"/dms/{dm['id']}/messages", json={"content": "x"})).status_code == 404
    assert (await bob.delete(f"/dm-messages/{msg['id']}")).status_code == 404
    assert (await alice.delete(f"/dm-messages/{msg['id']}")).status_code == 204


async def test_invite_friend_to_session(alice, bob):
    sid = (await create_server(alice))["id"]
    session = (
        await alice.post(
            f"/servers/{sid}/sessions",
            json={"game": "Apex", "title": "Trio", "max_participants": 3},
        )
    ).json()
    bob_id = (await me(bob))["id"]
    res = await alice.post(f"/sessions/{session['id']}/invite", json={"user_id": bob_id})
    assert res.status_code == 403  # not friends
    await befriend(alice, bob, "bob")
    assert (
        await alice.post(f"/sessions/{session['id']}/invite", json={"user_id": bob_id})
    ).status_code == 204
    dm = (await bob.get("/dms")).json()[0]
    text = dm["last_message"]["content"]
    assert "Trio" in text and "/einladung/" in text  # Bob isn't a member yet → server invite
    code = text.rsplit("/einladung/", 1)[1].strip()
    assert (await bob.post(f"/invites/{code}/accept")).status_code == 200
    assert (await bob.post(f"/sessions/{session['id']}/join")).status_code == 200


async def test_reports_and_moderation_queue(alice, bob, carol):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    await invite_and_join(alice, carol, sid)
    general = await channel_by_name(alice, sid, "allgemein")
    msg = (
        await bob.post(f"/channels/{general['id']}/messages", json={"content": "Spam spam"})
    ).json()

    res = await carol.post(
        "/reports", json={"reason": "spam", "message_id": msg["id"], "details": "Werbung"}
    )
    assert res.status_code == 201
    assert (
        await bob.post("/reports", json={"reason": "spam", "message_id": msg["id"]})
    ).status_code == 400
    assert (await carol.post("/reports", json={"reason": "spam"})).status_code == 400

    assert (await carol.get(f"/servers/{sid}/reports")).status_code == 403
    queue = (await alice.get(f"/servers/{sid}/reports", params={"status": "open"})).json()
    assert queue[0]["target"]["username"] == "bob" and queue[0]["message_excerpt"] == "Spam spam"
    # The report survives deletion of the message.
    await alice.delete(f"/messages/{msg['id']}")
    res = await alice.patch(f"/reports/{queue[0]['id']}", json={"status": "resolved"})
    assert res.status_code == 200 and res.json()["status"] == "resolved"
    assert (await alice.get(f"/servers/{sid}/reports", params={"status": "open"})).json() == []
    assert (
        await carol.patch(f"/reports/{queue[0]['id']}", json={"status": "open"})
    ).status_code == 403

    # Reporting a message in a channel you can't see is impossible.
    secret = (
        await alice.post(
            f"/servers/{sid}/channels", json={"name": "geheim", "type": "text", "private": True}
        )
    ).json()
    hidden = (await alice.post(f"/channels/{secret['id']}/messages", json={"content": "x"})).json()
    assert (
        await carol.post("/reports", json={"reason": "other", "message_id": hidden["id"]})
    ).status_code == 404
