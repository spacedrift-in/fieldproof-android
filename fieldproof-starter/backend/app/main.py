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
import math
import threading
import time
from collections import OrderedDict
from contextlib import asynccontextmanager

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from sqlalchemy import text

from .config import get_settings
from .db import get_engine, init_db
from .observability import RequestContextMiddleware, configure_logging, metrics
from .routers import inspections
from .schemas import HealthResponse, ReadyResponse
from .storage import configure_storage, get_storage, reset_storage
from .scanning import reset_scanner

logger = logging.getLogger(__name__)


class RateLimitMiddleware:
    """Fixed-window rate limiter keyed by the transport client, not a header.

    Pre-auth traffic is bounded by the *connection* peer address (or a header a
    trusted ingress overwrites), never by the caller-supplied ``X-Tenant-Id``:
    keying on an unauthenticated header let a client rotate arbitrary values to
    dodge its quota and grow the key table without bound (KL-032). Per-tenant /
    per-principal quotas belong after authentication (SPA-51), at which point
    ``RATE_LIMIT_TRUST_TENANT_HEADER`` can be enabled against a verified claim.

    The key table is a TTL-evicting, cardinality-bounded ``OrderedDict``: entries
    expire with the window and the oldest window is evicted at capacity, so
    memory stays bounded even under identifier rotation.

    WARNING: state is per-process. For multi-replica deployments enable the same
    limits at a trusted ingress, or run a shared limiter (e.g. Redis) so quotas
    are enforced globally rather than per replica.
    """

    def __init__(
        self,
        app,
        *,
        requests: int,
        window_seconds: int,
        max_keys: int = 10000,
        client_ip_header: str | None = None,
        trust_tenant_header: bool = False,
    ) -> None:
        self.app = app
        self.requests = max(requests, 1)
        self.window_seconds = max(window_seconds, 1)
        self.max_keys = max(max_keys, 1)
        self.client_ip_header = client_ip_header
        self.trust_tenant_header = trust_tenant_header
        # key -> [window_start_monotonic, count]; ordered oldest-window first.
        self._hits: "OrderedDict[str, list[float]]" = OrderedDict()
        self._lock = threading.Lock()

    def _client_key(self, scope, headers: dict[str, str]) -> str:
        # A trusted ingress may set the real client address; only consulted when
        # configured. The first (left-most) token is the original client.
        if self.client_ip_header:
            raw = headers.get(self.client_ip_header)
            if raw:
                candidate = raw.split(",")[0].strip()
                if candidate:
                    return candidate
        client = scope.get("client")
        address = client[0] if client else "unknown"
        if self.trust_tenant_header:
            tenant = headers.get("x-tenant-id")
            if tenant:
                return f"{address}|{tenant}"
        return address

    def _purge_expired(self, now: float) -> None:
        while self._hits:
            key = next(iter(self._hits))
            window_start, _ = self._hits[key]
            if now - window_start >= self.window_seconds:
                del self._hits[key]
            else:
                break

    def _evict_oldest(self) -> None:
        while len(self._hits) >= self.max_keys:
            self._hits.popitem(last=False)

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] in {"/health", "/readyz", "/metrics"}:
            return await self.app(scope, receive, send)

        headers = {k.decode().lower(): v.decode() for k, v in (scope.get("headers") or [])}
        key = self._client_key(scope, headers)

        now = time.monotonic()
        with self._lock:
            self._purge_expired(now)
            entry = self._hits.get(key)
            if entry is None:
                self._evict_oldest()
                window_start, count = now, 0
            else:
                window_start, count = entry
                if now - window_start >= self.window_seconds:
                    window_start, count = now, 0
                self._hits.move_to_end(key)
            count += 1
            self._hits[key] = [window_start, count]
            exceeded = count > self.requests
            retry_after = max(1, math.ceil(window_start + self.window_seconds - now))

        if exceeded:
            metrics.inc("rate_limit_rejections_total", {"path": scope.get("path", "unknown")})
            response = JSONResponse(
                {"detail": "Rate limit exceeded."},
                status_code=429,
                headers={"Retry-After": str(retry_after)},
            )
            return await response(scope, receive, send)
        return await self.app(scope, receive, send)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    if settings.auto_create_schema:
        # Demo/development bootstrap only; production relies on migrations.
        init_db()
    elif settings.run_migrations_on_start:
        from .db import run_migrations

        run_migrations()
    # Best-effort object-storage hardening (private access block + lifecycle).
    configure_storage()
    yield


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging()

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
            max_keys=settings.rate_limit_max_keys,
            client_ip_header=settings.rate_limit_client_ip_header,
            trust_tenant_header=settings.rate_limit_trust_tenant_header,
        )

    # Added last so it is outermost: logs and times every response, including
    # rate-limit rejections.
    app.add_middleware(RequestContextMiddleware)

    app.include_router(inspections.router, prefix="/api/v1")

    @app.get("/health", response_model=HealthResponse, tags=["system"], summary="Liveness check")
    async def health() -> HealthResponse:
        return HealthResponse(
            status="ok", service="fieldproof-api", stage=settings.environment
        )

    if settings.metrics_enabled:
        @app.get("/metrics", include_in_schema=False)
        async def metrics_endpoint(
            x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
        ) -> Response:
            if settings.metrics_admin_token and x_admin_token != settings.metrics_admin_token:
                raise HTTPException(status_code=401, detail="Invalid admin token.")
            return Response(
                content=metrics.render(),
                media_type="text/plain; version=0.0.4; charset=utf-8",
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
    metrics.reset()
