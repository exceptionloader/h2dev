import os

# Tests drop and recreate the schema, so they only ever run against a *_test database.
TEST_DATABASE_URL = os.environ.get(
    "NOVA_TEST_DATABASE_URL", "postgresql+asyncpg://nova:nova@localhost:5432/nova_test"
)
if not TEST_DATABASE_URL.rsplit("/", 1)[-1].split("?")[0].endswith("_test"):
    raise RuntimeError("NOVA_TEST_DATABASE_URL must point to a database ending in '_test'")
os.environ["NOVA_DATABASE_URL"] = TEST_DATABASE_URL
os.environ["NOVA_ENVIRONMENT"] = "test"
os.environ["NOVA_STORAGE_LOCAL_PATH"] = os.path.join(
    os.environ.get("TMPDIR", "/tmp"), f"nova-test-uploads-{os.getpid()}"
)
os.environ.pop("NOVA_SMTP_HOST", None)
os.environ["NOVA_LOG_LEVEL"] = "WARNING"

import asyncio  # noqa: E402
import contextlib  # noqa: E402
from collections.abc import AsyncIterator  # noqa: E402

import httpx  # noqa: E402
import pytest  # noqa: E402
from alembic.config import Config  # noqa: E402
from httpx_ws.transport import ASGIWebSocketTransport  # noqa: E402
from sqlalchemy import text  # noqa: E402

from alembic import command  # noqa: E402
from app.core.config import get_settings  # noqa: E402
from app.core.database import engine  # noqa: E402
from app.core.rate_limit import limiter  # noqa: E402
from app.main import app  # noqa: E402
from app.models import Base  # noqa: E402
from app.realtime.hub import hub  # noqa: E402

PASSWORD = "sehr-sicheres-passwort"


@pytest.fixture(scope="session", autouse=True)
async def _database() -> AsyncIterator[None]:
    """Recreate the test schema from the real migrations once per test run."""
    async with engine.begin() as conn:
        await conn.execute(text("DROP SCHEMA public CASCADE"))
        await conn.execute(text("CREATE SCHEMA public"))
    cfg = Config("alembic.ini")
    cfg.attributes["configure_logger"] = False
    cfg.attributes["database_url"] = get_settings().database_url
    # env.py runs its own event loop, so execute it in a worker thread.
    await asyncio.to_thread(command.upgrade, cfg, "head")
    yield
    await engine.dispose()


@pytest.fixture(autouse=True)
async def _clean_tables() -> AsyncIterator[None]:
    yield
    tables = ", ".join(t.name for t in Base.metadata.sorted_tables)
    async with engine.begin() as conn:
        await conn.execute(text(f"TRUNCATE {tables} CASCADE"))
    limiter.reset()
    hub.reset()


@pytest.fixture(autouse=True)
def _no_rate_limit(request: pytest.FixtureRequest) -> None:
    get_settings().rate_limit_enabled = "rate_limit" in request.keywords


def make_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test/api/v1")


@pytest.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    async with make_client() as c:
        yield c


async def register(c: httpx.AsyncClient, username: str, email: str | None = None) -> dict:
    res = await c.post(
        "/auth/register",
        json={
            "username": username,
            "email": email or f"{username}@example.com",
            "password": PASSWORD,
        },
    )
    assert res.status_code == 201, res.text
    return res.json()


@pytest.fixture
async def alice() -> AsyncIterator[httpx.AsyncClient]:
    async with make_client() as c:
        await register(c, "alice")
        yield c


@pytest.fixture
async def bob() -> AsyncIterator[httpx.AsyncClient]:
    async with make_client() as c:
        await register(c, "bob")
        yield c


@pytest.fixture
async def carol() -> AsyncIterator[httpx.AsyncClient]:
    async with make_client() as c:
        await register(c, "carol")
        yield c


async def me(c: httpx.AsyncClient) -> dict:
    return (await c.get("/users/me")).json()


async def create_server(c: httpx.AsyncClient, name: str = "Squad", **extra) -> dict:
    res = await c.post("/servers", json={"name": name, **extra})
    assert res.status_code == 201, res.text
    return res.json()


async def invite_and_join(owner: httpx.AsyncClient, joiner: httpx.AsyncClient, sid: str) -> None:
    code = (await owner.post(f"/servers/{sid}/invites", json={})).json()["code"]
    res = await joiner.post(f"/invites/{code}/accept")
    assert res.status_code == 200, res.text


async def role_id(c: httpx.AsyncClient, sid: str, name: str) -> str:
    roles = (await c.get(f"/servers/{sid}/roles")).json()
    return next(r["id"] for r in roles if r["name"] == name)


async def grant_role(owner: httpx.AsyncClient, sid: str, user_id: str, name: str) -> None:
    rid = await role_id(owner, sid, name)
    res = await owner.put(f"/servers/{sid}/members/{user_id}/roles/{rid}")
    assert res.status_code == 204, res.text


async def channel_by_name(c: httpx.AsyncClient, sid: str, name: str) -> dict:
    return next(ch for ch in (await c.get(f"/servers/{sid}/channels")).json() if ch["name"] == name)


@contextlib.asynccontextmanager
async def ws_connect(c: httpx.AsyncClient | None = None, **kwargs):
    """Open the gateway as the user of client ``c`` (cookies are copied)."""
    from httpx_ws import aconnect_ws

    async with httpx.AsyncClient(
        transport=ASGIWebSocketTransport(app),
        base_url="http://test/api/v1",
        cookies=c.cookies if c is not None else None,
    ) as wc:
        async with aconnect_ws("/gateway", wc, **kwargs) as ws:
            yield ws
        # Give the server a moment to run its disconnect handling before the test
        # transport cancels the app task.
        await asyncio.sleep(0.3)
