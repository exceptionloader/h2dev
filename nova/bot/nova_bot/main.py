"""NOVA Bot process: connects to the gateway with its bot token and answers commands.

Environment:
  NOVA_API_URL    e.g. http://localhost:8000
  NOVA_BOT_TOKEN  the bot token (for the first-party bot: NOVA_FIRST_PARTY_BOT_TOKEN)
"""

import asyncio
import json
import logging
import os
import random
import uuid

import httpx
import websockets

from nova_bot.commands import format_sessions, handle, welcome

log = logging.getLogger("nova_bot")


class Bot:
    def __init__(self, api_url: str, token: str) -> None:
        self.api_url = api_url.rstrip("/")
        self.token = token
        self.user_id: str | None = None
        self.http = httpx.AsyncClient(
            base_url=f"{self.api_url}/api/v1",
            headers={"Authorization": f"Bot {token}"},
            timeout=10,
        )

    async def send(self, channel_id: str, content: str, reply_to: str | None = None) -> None:
        res = await self.http.post(
            f"/channels/{channel_id}/messages",
            json={"content": content[:2000], "reply_to_id": reply_to},
        )
        if res.status_code >= 400:
            # E.g. missing SEND_MESSAGES: the bot respects permissions like everyone else.
            log.warning("send failed %s %s", res.status_code, res.text[:200])

    async def on_message(self, msg: dict) -> None:
        author = msg.get("author") or {}
        if author.get("id") == self.user_id or author.get("is_bot"):
            return
        reply = handle(msg.get("content", ""), random.Random())  # noqa: S311
        if reply is None:
            return
        text = reply.text
        if reply.needs_sessions:
            res = await self.http.get(f"/servers/{msg['server_id']}/sessions")
            text = format_sessions(res.json() if res.status_code == 200 else [])
        if text:
            await self.send(msg["channel_id"], text, reply_to=msg["id"])

    async def on_member_joined(self, data: dict) -> None:
        user = data.get("user") or {}
        if user.get("is_bot"):
            return
        res = await self.http.get(f"/servers/{data['server_id']}/channels")
        if res.status_code != 200:
            return
        writable = [
            c for c in res.json()
            if c["type"] == "text" and "SEND_MESSAGES" in c["my_permissions"]
        ]
        writable.sort(key=lambda c: (c["name"] != "allgemein", c["position"]))
        if writable:
            await self.send(writable[0]["id"], welcome(user.get("display_name", "")))

    async def dispatch(self, event: dict) -> None:
        kind, data = event.get("type"), event.get("data") or {}
        if kind == "ready":
            self.user_id = data["user_id"]
            log.info("ready as %s", self.user_id)
        elif kind == "message.created":
            await self.on_message(data)
        elif kind == "member.joined":
            await self.on_member_joined(data)

    async def run(self) -> None:
        ws_url = self.api_url.replace("http", "ws", 1) + "/api/v1/gateway"
        delay = 1.0
        while True:
            try:
                async with websockets.connect(
                    ws_url, additional_headers={"Authorization": f"Bot {self.token}"}
                ) as ws:
                    delay = 1.0
                    pinger = asyncio.create_task(self._ping(ws))
                    try:
                        async for raw in ws:
                            try:
                                await self.dispatch(json.loads(raw))
                            except Exception:
                                log.exception("event handling failed")
                    finally:
                        pinger.cancel()
            except (OSError, websockets.WebSocketException) as exc:
                log.warning("gateway disconnected: %s – retry in %.0fs", exc, delay)
            await asyncio.sleep(delay + random.random())  # noqa: S311
            delay = min(delay * 2, 60)

    @staticmethod
    async def _ping(ws) -> None:  # noqa: ANN001
        while True:
            await asyncio.sleep(25)
            await ws.send("ping")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    token = os.environ["NOVA_BOT_TOKEN"]
    api_url = os.environ.get("NOVA_API_URL", "http://localhost:8000")
    log.info("starting NOVA Bot against %s (instance %s)", api_url, uuid.uuid4().hex[:6])
    asyncio.run(Bot(api_url, token).run())


if __name__ == "__main__":
    main()
