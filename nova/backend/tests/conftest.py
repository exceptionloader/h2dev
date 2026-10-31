import os

# Tests drop and recreate the schema, so they only ever run against a *_test database.
TEST_DATABASE_URL = os.environ.get(
    "NOVA_TEST_DATABASE_URL", "postgresql+asyncpg://nova:nova@localhost:5432/nova_test"
)
if not TEST_DATABASE_URL.rsplit("/", 1)[-1].split("?")[0].endswith("_test"):
    raise RuntimeError("NOVA_TEST_DATABASE_URL must point to a database ending in '_test'")
os.environ["NOVA_DATABASE_URL"] = TEST_DATABASE_URL
os.environ["NOVA_ENVIRONMENT"] = "test"
os.environ["NOVA_LOG_LEVEL"] = "WARNING"

import asyncio  # noqa: E402
from collections.abc import AsyncIterator  # noqa: E402

import httpx  # noqa: E402
import pytest  # noqa: E402
from alembic.config import Config  # noqa: E402
from sqlalchemy import text  # noqa: E402

from alembic import command  # noqa: E402
from app.core.config import get_settings  # noqa: E402
from app.core.database import engine  # noqa: E402
from app.core.rate_limit import limiter  # noqa: E402
from app.main import app  # noqa: E402
from app.models import Base  # noqa: E402

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
