"""FieldProof API application factory.

Run with: ``uvicorn app.main:app --app-dir backend`` from this folder.
Interactive OpenAPI docs: http://127.0.0.1:8000/docs (disabled when
``DOCS_ENABLED=false``).

The app is built by :func:`create_app` so tests can construct a fresh instance
after changing configuration; ``app`` is the module-level singleton used by
ASGI servers.
"""
from __future__ import annotations

import logging
import threading
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import text

from .config import get_settings
from .db import get_engine, init_db
from .routers import inspections
from .schemas import HealthResponse, ReadyResponse
from .storage import configure_storage, get_storage, reset_storage
from .scanning import reset_scanner

logger = logging.getLogger(__name__)


class RateLimitMiddleware:
    """Dependency-free fixed-window rate limiter keyed by tenant (or client IP).

    WARNING: state is per-process. For multi-replica deployments use a shared
    store (e.g. Redis) or an ingress/API-gateway limiter instead.
    """

    def __init__(self, app, *, requests: int, window_seconds: int) -> None:
        self.app = app
        self.requests = max(requests, 1)
        self.window_seconds = max(window_seconds, 1)
        self._hits: dict[str, tuple[float, int]] = {}
        self._lock = threading.Lock()

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] in {"/health", "/readyz"}:
            return await self.app(scope, receive, send)

        headers = {k.decode().lower(): v.decode() for k, v in (scope.get("headers") or [])}
        tenant = headers.get("x-tenant-id")
        if not tenant:
            tenant = (scope.get("client") or ("unknown",))[0]

        now = time.monotonic()
        with self._lock:
            window_start, count = self._hits.get(tenant, (now, 0))
            if now - window_start >= self.window_seconds:
                window_start, count = now, 0
            count += 1
            self._hits[tenant] = (window_start, count)
            exceeded = count > self.requests

        if exceeded:
            response = JSONResponse(
                {"detail": "Rate limit exceeded."},
                status_code=429,
                headers={"Retry-After": str(self.window_seconds)},
            )
            return await response(scope, receive, send)
        return await self.app(scope, receive, send)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Bootstrap the schema for the demo. Production should run migrations instead.
    init_db()
    # Best-effort object-storage hardening (private access block + lifecycle).
    configure_storage()
    yield


def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(
        title="FieldProof API",
        version=settings.app_version,
        description=(
            "Offline-first field inspection API. Postgres-backed persistence for "
            "inspections, checklist items, evidence, reviews and idempotency records. "
            "Evidence is stored in private object storage with encryption and "
            "lifecycle rules; uploads are MIME-validated and size-bounded. "
            "Tenancy is request-scoped via X-Tenant-Id. Authentication is not "
            "implemented yet."
        ),
        lifespan=lifespan,
        docs_url="/docs" if settings.docs_enabled else None,
        redoc_url="/redoc" if settings.docs_enabled else None,
        openapi_url="/openapi.json" if settings.docs_enabled else None,
    )

    if settings.allowed_origins:
        allow_credentials = settings.cors_allow_credentials
        if allow_credentials and "*" in settings.allowed_origins:
            logger.warning(
                "CORS_ALLOWED_ORIGINS contains '*' with credentials enabled; "
                "refusing wildcard and using an explicit allowlist instead."
            )
            allowed = [o for o in settings.allowed_origins if o != "*"]
            allow_credentials = bool(allowed)
        else:
            allowed = list(settings.allowed_origins)
        app.add_middleware(
            CORSMiddleware,
            allow_origins=allowed,
            allow_credentials=allow_credentials,
            allow_methods=["GET", "POST", "PUT", "OPTIONS"],
            allow_headers=["Content-Type", "Idempotency-Key", "X-Tenant-Id"],
        )

    if settings.rate_limit_enabled:
        app.add_middleware(
            RateLimitMiddleware,
            requests=settings.rate_limit_requests,
            window_seconds=settings.rate_limit_window_seconds,
        )

    app.include_router(inspections.router, prefix="/api/v1")

    @app.get("/health", response_model=HealthResponse, tags=["system"], summary="Liveness check")
    async def health() -> HealthResponse:
        return HealthResponse(
            status="ok", service="fieldproof-api", stage=settings.environment
        )

    @app.get("/readyz", response_model=ReadyResponse, tags=["system"], summary="Readiness check")
    async def readyz() -> JSONResponse:
        database_status = "ok"
        try:
            with get_engine().connect() as conn:
                conn.execute(text("SELECT 1"))
        except Exception:  # noqa: BLE001
            logger.warning("Readiness: database check failed", exc_info=True)
            database_status = "error"

        storage_backend = settings.storage_backend
        storage_status = "ok"
        try:
            storage = get_storage()
            if not storage.check():  # type: ignore[attr-defined]
                storage_status = "error"
        except Exception:  # noqa: BLE001
            logger.warning("Readiness: storage check failed", exc_info=True)
            storage_status = "error"

        overall = "ok" if database_status == "ok" and storage_status == "ok" else "error"
        body = ReadyResponse(
            status=overall,
            database=database_status,
            storage=storage_status,
            storage_backend=storage_backend,
        )
        return JSONResponse(
            status_code=200 if overall == "ok" else 503,
            content=body.model_dump(),
        )

    return app


app = create_app()


def reset_caches() -> None:
    """Clear module-level singletons (used by tests)."""
    reset_storage()
    reset_scanner()
