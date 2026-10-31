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


def _reset_schema(monkeypatch, database_url: str, tmp_path) -> None:
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("EVIDENCE_STORAGE_DIR", str(tmp_path / "evidence"))

    from backend.app import db as db_module
    from backend.app import models  # noqa: F401  (register mappings)

    db_module.reset_engine()
    engine = db_module.get_engine()
    db_module.Base.metadata.drop_all(engine)
    db_module.Base.metadata.create_all(engine)


def _drop_schema() -> None:
    from backend.app import db as db_module
    from backend.app import storage as storage_module

    db_module.Base.metadata.drop_all(db_module.get_engine())
    db_module.reset_engine()
    storage_module.reset_storage()


@pytest.fixture()
def client(database_url, tmp_path, monkeypatch):
    from fastapi.testclient import TestClient

    _reset_schema(monkeypatch, database_url, tmp_path)

    from backend.app.main import create_app, reset_caches

    reset_caches()
    with TestClient(create_app()) as test_client:
        yield test_client

    _drop_schema()


@pytest.fixture()
def make_client(database_url, tmp_path, monkeypatch):
    """Factory that builds a fresh app/client after applying env overrides."""
    from fastapi.testclient import TestClient

    from backend.app.main import create_app, reset_caches

    entered = []

    def _make(**env):
        _reset_schema(monkeypatch, database_url, tmp_path)
        for key, value in env.items():
            monkeypatch.setenv(key, str(value))
        reset_caches()
        test_client = TestClient(create_app())
        test_client.__enter__()
        entered.append(test_client)
        return test_client

    yield _make

    for test_client in entered:
        test_client.__exit__(None, None, None)
    _drop_schema()
