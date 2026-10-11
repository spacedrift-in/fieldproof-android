"""FieldProof API application factory.

Run with: ``uvicorn app.main:app --app-dir backend --reload`` from this folder.
Interactive OpenAPI docs: http://127.0.0.1:8000/docs
"""
from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI

from .db import init_db
from .routers import inspections
from .schemas import HealthResponse


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Bootstrap the schema for the demo. Production should run migrations instead.
    init_db()
    yield


app = FastAPI(
    title="FieldProof API",
    version="0.2.0",
    description=(
        "Offline-first field inspection API. Postgres-backed persistence for "
        "inspections, checklist items, evidence, reviews and idempotency records. "
        "Synthetic demo data only; authentication is not implemented yet."
    ),
    lifespan=lifespan,
)

app.include_router(inspections.router, prefix="/api/v1")


@app.get("/health", response_model=HealthResponse, tags=["system"], summary="Liveness check")
async def health() -> HealthResponse:
    return HealthResponse(status="ok", service="fieldproof-api", stage="starter")
