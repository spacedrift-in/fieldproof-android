from __future__ import annotations

import os

import pytest

_TEST_DATABASE_URL = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
if _TEST_DATABASE_URL:
    os.environ["DATABASE_URL"] = _TEST_DATABASE_URL


@pytest.fixture(scope="session")
def database_url() -> str:
    """Skip database-backed tests unless a PostgreSQL URL is configured."""
    if not _TEST_DATABASE_URL:
        pytest.skip(
            "Set TEST_DATABASE_URL (or DATABASE_URL) to a PostgreSQL instance to run "
            "the backend database tests. See backend/README.md."
        )
    return _TEST_DATABASE_URL


@pytest.fixture()
def client(database_url, tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("EVIDENCE_STORAGE_DIR", str(tmp_path / "evidence"))

    from fastapi.testclient import TestClient

    from backend.app import db as db_module
    from backend.app import models  # noqa: F401  (register mappings)

    db_module.reset_engine()
    engine = db_module.get_engine()
    db_module.Base.metadata.drop_all(engine)
    db_module.Base.metadata.create_all(engine)

    from backend.app.main import app

    with TestClient(app) as test_client:
        yield test_client

    db_module.Base.metadata.drop_all(engine)
    db_module.reset_engine()
