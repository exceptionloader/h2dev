"""Small load test: N users in one server, all connected to the gateway, all chatting.

Measures HTTP latency of sending a message and the time until *every* connected member
has received it over the WebSocket (fan-out latency).

Run against a dev backend started with NOVA_RATE_LIMIT_ENABLED=false (sign-up is rate
limited per IP otherwise):

    cd nova/backend
    uv run python ../scripts/loadtest.py --users 50 --messages 5
"""

import argparse
import asyncio
import json
import statistics
import time
import uuid

import httpx
import websockets


async def register(api: str, name: str) -> httpx.AsyncClient:
    c = httpx.AsyncClient(base_url=f"{api}/api/v1", timeout=30)
    r = await c.post(
        "/auth/register",
        json={"username": name, "email": f"{name}@example.com", "password": "load-test-password"},
    )
    r.raise_for_status()
    return c


def pct(values: list[float], p: float) -> float:
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))] * 1000


async def main(api: str, users: int, messages: int) -> None:
    run = uuid.uuid4().hex[:6]
    t0 = time.perf_counter()
    clients = await asyncio.gather(*(register(api, f"load{run}_{i}") for i in range(users)))
    owner = clients[0]
    sid = (await owner.post("/servers", json={"name": f"Load {run}"})).json()["id"]
    code = (await owner.post(f"/servers/{sid}/invites", json={})).json()["code"]
    await asyncio.gather(*(c.post(f"/invites/{code}/accept") for c in clients[1:]))
    channels = (await owner.get(f"/servers/{sid}/channels")).json()
    cid = next(c["id"] for c in channels if c["name"] == "allgemein")
    print(f"setup: {users} users joined in {time.perf_counter() - t0:.1f}s")

    received: dict[str, list[float]] = {}
    sent_at: dict[str, float] = {}
    ws_url = api.replace("http", "ws", 1) + "/api/v1/gateway"

    async def listen(c: httpx.AsyncClient, ready: asyncio.Event, stop: asyncio.Event):
        cookie = "; ".join(f"{k}={v}" for k, v in c.cookies.items())
        async with websockets.connect(ws_url, additional_headers={"Cookie": cookie}) as ws:
            ready.set()
            while not stop.is_set():
                try:
                    raw = await asyncio.wait_for(ws.recv(), 0.5)
                except TimeoutError:
                    continue
                event = json.loads(raw)
                if event["type"] == "message.created":
                    received.setdefault(event["data"]["content"], []).append(time.perf_counter())

    stop = asyncio.Event()
    readies = [asyncio.Event() for _ in clients]
    listeners = [asyncio.create_task(listen(c, e, stop)) for c, e in zip(clients, readies, strict=True)]
    await asyncio.gather(*(e.wait() for e in readies))
    await asyncio.sleep(1)
    print(f"gateway: {users} WebSocket connections open")

    http_latency: list[float] = []

    async def chat(i: int, c: httpx.AsyncClient):
        for m in range(messages):
            content = f"msg-{i}-{m}"
            start = time.perf_counter()
            sent_at[content] = start
            r = await c.post(f"/channels/{cid}/messages", json={"content": content})
            r.raise_for_status()
            http_latency.append(time.perf_counter() - start)
            await asyncio.sleep(0.2)

    t1 = time.perf_counter()
    await asyncio.gather(*(chat(i, c) for i, c in enumerate(clients)))
    duration = time.perf_counter() - t1
    await asyncio.sleep(2)
    stop.set()
    await asyncio.gather(*listeners, return_exceptions=True)

    total = users * messages
    fanout = [max(ts) - sent_at[k] for k, ts in received.items() if len(ts) == users]
    complete = len(fanout)
    deliveries = sum(len(v) for v in received.values())
    print(f"messages: {total} sent in {duration:.1f}s ({total / duration:.0f}/s), "
          f"{deliveries} deliveries ({deliveries / duration:.0f}/s)")
    print(f"send latency ms   p50={pct(http_latency, .5):.0f} p95={pct(http_latency, .95):.0f} "
          f"max={max(http_latency) * 1000:.0f}")
    if fanout:
        print(f"fan-out to all {users} ms p50={pct(fanout, .5):.0f} p95={pct(fanout, .95):.0f} "
              f"max={max(fanout) * 1000:.0f} (mean {statistics.mean(fanout) * 1000:.0f})")
    print(f"complete fan-out: {complete}/{total} messages reached every member")
    await asyncio.gather(*(c.aclose() for c in clients))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8000")
    ap.add_argument("--users", type=int, default=50)
    ap.add_argument("--messages", type=int, default=5)
    args = ap.parse_args()
    asyncio.run(main(args.api, args.users, args.messages))
