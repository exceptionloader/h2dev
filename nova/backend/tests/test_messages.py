"""Messages, replies, reactions, pins, attachments and channel access."""

import uuid

from tests.conftest import channel_by_name, create_server, invite_and_join

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64


async def setup(alice, bob):
    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    general = await channel_by_name(alice, sid, "allgemein")
    return sid, general["id"]


async def test_send_list_edit_delete(alice, bob):
    sid, cid = await setup(alice, bob)
    res = await bob.post(f"/channels/{cid}/messages", json={"content": "  GG wp  "})
    assert res.status_code == 201
    msg = res.json()
    assert msg["content"] == "GG wp" and msg["author"]["username"] == "bob"

    res = await alice.post(
        f"/channels/{cid}/messages", json={"content": "Danke!", "reply_to_id": msg["id"]}
    )
    reply = res.json()
    assert reply["reply_to"]["id"] == msg["id"] and reply["reply_to"]["content"] == "GG wp"

    listed = (await bob.get(f"/channels/{cid}/messages")).json()
    assert [m["content"] for m in listed] == ["GG wp", "Danke!"]

    # Only the author can edit
    assert (await alice.patch(f"/messages/{msg['id']}", json={"content": "x"})).status_code == 403
    res = await bob.patch(f"/messages/{msg['id']}", json={"content": "GG well played"})
    assert res.status_code == 200 and res.json()["edited_at"]

    # Member cannot delete others' messages; owner (MANAGE_MESSAGES) can, and it's audited
    assert (await bob.delete(f"/messages/{reply['id']}")).status_code == 403
    assert (await alice.delete(f"/messages/{msg['id']}")).status_code == 204
    listed = (await bob.get(f"/channels/{cid}/messages")).json()
    assert [m["content"] for m in listed] == ["Danke!"]
    assert listed[0]["reply_to"] is None
    log = [e["action"] for e in (await alice.get(f"/servers/{sid}/audit-logs")).json()]
    assert "message.delete" in log


async def test_message_validation_and_pagination(alice, bob):
    _, cid = await setup(alice, bob)
    assert (await bob.post(f"/channels/{cid}/messages", json={"content": "   "})).status_code == 400
    assert (
        await bob.post(f"/channels/{cid}/messages", json={"content": "x" * 2001})
    ).status_code == 422
    for i in range(55):
        await alice.post(f"/channels/{cid}/messages", json={"content": f"m{i}"})
    page1 = (await bob.get(f"/channels/{cid}/messages")).json()
    assert len(page1) == 50 and page1[-1]["content"] == "m54"
    page2 = (await bob.get(f"/channels/{cid}/messages", params={"before": page1[0]["id"]})).json()
    assert [m["content"] for m in page2] == [f"m{i}" for i in range(5)]


async def test_channel_access_rules(alice, bob):
    sid, cid = await setup(alice, bob)
    ann = await channel_by_name(alice, sid, "ankündigungen")
    voice = await channel_by_name(alice, sid, "Lobby")
    res = await bob.post(f"/channels/{ann['id']}/messages", json={"content": "hi"})
    assert res.status_code == 403
    assert (
        await alice.post(f"/channels/{ann['id']}/messages", json={"content": "News"})
    ).status_code == 201
    res = await alice.post(f"/channels/{voice['id']}/messages", json={"content": "hi"})
    assert res.status_code == 400 and res.json()["code"] == "not_text_channel"

    secret = (
        await alice.post(
            f"/servers/{sid}/channels", json={"name": "geheim", "type": "text", "private": True}
        )
    ).json()
    msg = (await alice.post(f"/channels/{secret['id']}/messages", json={"content": "psst"})).json()
    assert (await bob.get(f"/channels/{secret['id']}/messages")).status_code == 404
    assert (
        await bob.post(f"/channels/{secret['id']}/messages", json={"content": "x"})
    ).status_code == 404
    assert (await bob.put(f"/messages/{msg['id']}/reactions/👍")).status_code == 404
    assert (await bob.delete(f"/messages/{msg['id']}")).status_code == 404


async def test_outsider_cannot_read(alice, bob, carol):
    _, cid = await setup(alice, bob)
    assert (await carol.get(f"/channels/{cid}/messages")).status_code == 404
    assert (await carol.post(f"/channels/{cid}/messages", json={"content": "x"})).status_code == 404


async def test_reactions_and_pins(alice, bob):
    _, cid = await setup(alice, bob)
    msg = (await alice.post(f"/channels/{cid}/messages", json={"content": "Wer ist dabei?"})).json()
    res = await bob.put(f"/messages/{msg['id']}/reactions/🔥")
    assert res.status_code == 200
    await alice.put(f"/messages/{msg['id']}/reactions/🔥")
    await alice.put(f"/messages/{msg['id']}/reactions/🔥")  # idempotent
    reactions = (await bob.get(f"/channels/{cid}/messages")).json()[0]["reactions"]
    assert len(reactions) == 1 and len(reactions[0]["user_ids"]) == 2
    res = await bob.delete(f"/messages/{msg['id']}/reactions/🔥")
    assert len(res.json()["reactions"][0]["user_ids"]) == 1
    assert (await bob.put(f"/messages/{msg['id']}/reactions/<script>")).status_code in (400, 404)

    assert (await bob.put(f"/messages/{msg['id']}/pin")).status_code == 403
    res = await alice.put(f"/messages/{msg['id']}/pin")
    assert res.status_code == 200 and res.json()["pinned"] is True
    pins = (await bob.get(f"/channels/{cid}/pins")).json()
    assert [p["id"] for p in pins] == [msg["id"]]
    await alice.delete(f"/messages/{msg['id']}/pin")
    assert (await bob.get(f"/channels/{cid}/pins")).json() == []


async def test_attachments(alice, bob, carol):
    _, cid = await setup(alice, bob)
    res = await bob.post(
        f"/channels/{cid}/attachments", files={"file": ("screenshot.png", PNG, "text/html")}
    )
    assert res.status_code == 201
    att = res.json()
    # Type is sniffed from content, not taken from the client.
    assert att["content_type"] == "image/png" and att["is_image"] is True

    # Not yet linked: only the uploader can fetch it.
    assert (await alice.get(att["url"].removeprefix("/api/v1"))).status_code == 404
    res = await bob.post(
        f"/channels/{cid}/messages", json={"content": "", "attachment_ids": [att["id"]]}
    )
    assert res.status_code == 201
    assert res.json()["attachments"][0]["filename"] == "screenshot.png"

    dl = await alice.get(att["url"].removeprefix("/api/v1"))
    assert dl.status_code == 200 and dl.content == PNG
    assert "sandbox" in dl.headers["content-security-policy"]
    assert dl.headers["content-disposition"].startswith("inline")
    assert (await carol.get(att["url"].removeprefix("/api/v1"))).status_code == 404

    # Non-image files are served as downloads.
    res = await bob.post(
        f"/channels/{cid}/attachments",
        files={"file": ("../../evil.html", b"<script>alert(1)</script>", "text/html")},
    )
    other = res.json()
    assert other["content_type"] == "application/octet-stream" and other["filename"] == "evil.html"
    # Someone else's attachment cannot be attached
    res = await alice.post(
        f"/channels/{cid}/messages", json={"content": "x", "attachment_ids": [other["id"]]}
    )
    assert res.status_code == 400


async def test_upload_limits(alice, bob, monkeypatch):
    from app.core.config import get_settings

    _, cid = await setup(alice, bob)
    monkeypatch.setattr(get_settings(), "max_attachment_bytes", 100)
    res = await bob.post(f"/channels/{cid}/attachments", files={"file": ("big.bin", b"x" * 101)})
    assert res.status_code == 400 and res.json()["code"] == "file_too_large"
    res = await bob.post(f"/channels/{cid}/attachments", files={"file": ("e.bin", b"")})
    assert res.json()["code"] == "file_empty"


async def test_avatar_and_icon_upload(alice, bob):
    res = await alice.post("/users/me/avatar", files={"file": ("a.png", PNG, "image/png")})
    assert res.status_code == 200
    url = res.json()["avatar_url"]
    assert url.startswith("/api/v1/media/avatars/")
    img = await bob.get(url.removeprefix("/api/v1"))
    assert img.status_code == 200 and img.headers["content-type"] == "image/png"
    res = await alice.post("/users/me/avatar", files={"file": ("a.svg", b"<svg onload=alert(1)>")})
    assert res.status_code == 400 and res.json()["code"] == "invalid_image"

    sid = (await create_server(alice))["id"]
    await invite_and_join(alice, bob, sid)
    res = await bob.post(f"/servers/{sid}/icon", files={"file": ("i.png", PNG)})
    assert res.status_code == 403
    res = await alice.post(f"/servers/{sid}/icon", files={"file": ("i.png", PNG)})
    assert res.status_code == 200 and res.json()["icon_url"].startswith("/api/v1/media/icons/")
    assert (await bob.get(f"/media/avatars/{uuid.uuid4()}.png")).status_code == 404
    assert (await bob.get("/media/etc/passwd")).status_code == 404


async def test_s3_storage_backend(monkeypatch):
    import boto3
    from moto import mock_aws

    from app.core.config import get_settings
    from app.services.storage import S3Storage

    with mock_aws():
        monkeypatch.setattr(get_settings(), "s3_bucket", "nova-test")
        boto3.client("s3", region_name="us-east-1").create_bucket(Bucket="nova-test")
        store = S3Storage()
        key = f"avatars/{uuid.uuid4()}.png"
        await store.save(key, PNG, "image/png")
        assert await store.load(key) == PNG
        await store.delete(key)
        assert await store.load(key) is None
