import logging
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import text

from app.api import auth, servers, users
from app.core.config import get_settings
from app.core.database import engine
from app.core.errors import AppError, app_error_handler, validation_error_handler
from app.core.logging import configure_logging

settings = get_settings()
configure_logging(settings.log_level)
log = logging.getLogger("nova.http")

UNSAFE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}


@asynccontextmanager
async def lifespan(_: FastAPI):
    log.info("startup", extra={"environment": settings.environment})
    yield
    await engine.dispose()


app = FastAPI(
    title="NOVA API",
    version="0.1.0",
    lifespan=lifespan,
    docs_url=None if settings.is_production else "/api/docs",
    openapi_url=None if settings.is_production else "/api/openapi.json",
)

app.add_exception_handler(AppError, app_error_handler)
app.add_exception_handler(RequestValidationError, validation_error_handler)


@app.middleware("http")
async def request_context(request: Request, call_next):
    request_id = request.headers.get("x-request-id") or uuid.uuid4().hex
    start = time.perf_counter()

    # CSRF defence in depth (cookies are also SameSite=Lax): browsers always send an
    # Origin header on cross-site state-changing requests, so reject unknown origins.
    origin = request.headers.get("origin")
    if request.method in UNSAFE_METHODS and origin and origin not in settings.allowed_origins:
        response = JSONResponse({"code": "bad_origin", "detail": "Origin not allowed"}, 403)
    else:
        try:
            response = await call_next(request)
        except Exception:
            log.exception("unhandled_error", extra={"request_id": request_id})
            response = JSONResponse({"code": "internal_error", "detail": "Internal error"}, 500)

    response.headers["X-Request-ID"] = request_id
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    response.headers.setdefault("Cache-Control", "no-store")
    log.info(
        "request",
        extra={
            "request_id": request_id,
            "method": request.method,
            "path": request.url.path,
            "status": response.status_code,
            "duration_ms": round((time.perf_counter() - start) * 1000, 1),
        },
    )
    return response


app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PATCH", "PUT", "DELETE"],
    allow_headers=["Content-Type", "X-Request-ID"],
)

API_PREFIX = "/api/v1"
app.include_router(auth.router, prefix=API_PREFIX)
app.include_router(users.router, prefix=API_PREFIX)
app.include_router(servers.router, prefix=API_PREFIX)


@app.get("/api/health", tags=["meta"])
async def health():
    async with engine.connect() as conn:
        await conn.execute(text("SELECT 1"))
    return {"status": "ok"}
