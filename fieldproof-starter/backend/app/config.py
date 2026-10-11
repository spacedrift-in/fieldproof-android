"""Runtime configuration for the FieldProof API.

All values can be overridden with environment variables so the same code runs
against a local PostgreSQL container, CI, or a future managed database.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent

# The demo default matches ``docker-compose.yml`` in this folder.
DEFAULT_DATABASE_URL = "postgresql+psycopg://fieldproof:fieldproof@localhost:5432/fieldproof"

DEFAULT_MAX_EVIDENCE_BYTES = 10 * 1024 * 1024  # 10 MiB


@dataclass(frozen=True)
class Settings:
    database_url: str
    evidence_storage_dir: Path
    max_evidence_bytes: int


def get_settings() -> Settings:
    """Read settings from the environment on every call (cheap, easy to test)."""
    storage_override = os.getenv("EVIDENCE_STORAGE_DIR")
    return Settings(
        database_url=os.getenv("DATABASE_URL", DEFAULT_DATABASE_URL),
        evidence_storage_dir=(
            Path(storage_override).expanduser()
            if storage_override
            else BACKEND_DIR / "var" / "evidence"
        ),
        max_evidence_bytes=int(
            os.getenv("MAX_EVIDENCE_BYTES", str(DEFAULT_MAX_EVIDENCE_BYTES))
        ),
    )
