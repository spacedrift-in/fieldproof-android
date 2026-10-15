"""Migration tests (KL-003).

Validates that Alembic manages the schema end to end: an empty database is
upgraded to head, ``alembic check`` reports no drift between migrations and the
models, and a downgrade removes every table. These run against a throwaway
SQLite file so they need no PostgreSQL.
"""
from __future__ import annotations

import os
import subprocess
import sys

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect

# Alembic config lives at fieldproof-starter/backend/alembic.ini.
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INI_PATH = os.path.join(REPO_ROOT, "backend", "alembic.ini")


@pytest.fixture()
def sqlite_env(tmp_path, monkeypatch):
    """Point the app at a fresh on-disk SQLite DB and reset cached engines."""
    db_file = tmp_path / "migration.sqlite"
    url = f"sqlite+pysqlite:///{db_file}"
    monkeypatch.setenv("DATABASE_URL", url)

    from backend.app import db as db_module

    db_module.reset_engine()
    yield url
    db_module.reset_engine()


def _app_tables(db_module):
    engine = db_module.get_engine()
    return {name for name in inspect(engine).get_table_names() if name != "alembic_version"}


def _cfg() -> Config:
    """Alembic config that won't reconfigure process-wide logging.

    ``env.py`` normally calls ``fileConfig``, which resets the root logger level
    and handlers (and used to disable app loggers). We keep migrations from
    clobbering logging config when run in-process.
    """
    cfg = Config(INI_PATH)
    cfg.attributes["configure_logger"] = False
    return cfg


def test_upgrade_from_empty_db_creates_all_tables(sqlite_env):
    from backend.app import db as db_module
    from backend.app import models  # noqa: F401  (register mappings)

    before = _app_tables(db_module)
    assert before == set()  # empty database to start

    command.upgrade(_cfg(), "head")

    expected = set(db_module.Base.metadata.tables)
    assert expected.issubset(_app_tables(db_module))
    assert {"inspections", "checklist_items", "evidence", "reviews", "idempotency_records"} <= expected


def test_downgrade_base_removes_all_tables(sqlite_env):
    from backend.app import db as db_module
    from backend.app import models  # noqa: F401  (register mappings)

    command.upgrade(_cfg(), "head")
    expected = set(db_module.Base.metadata.tables)
    assert expected <= _app_tables(db_module)

    command.downgrade(_cfg(), "base")
    assert _app_tables(db_module) == set()


def test_round_trip_upgrade_downgrade_upgrade(sqlite_env):
    from backend.app import db as db_module
    from backend.app import models  # noqa: F401  (register mappings)

    command.upgrade(_cfg(), "head")
    command.downgrade(_cfg(), "base")
    command.upgrade(_cfg(), "head")
    assert set(db_module.Base.metadata.tables) <= _app_tables(db_module)


def test_alembic_check_reports_no_drift(sqlite_env):
    """Migrations must match the models exactly (CI guard)."""
    command.upgrade(_cfg(), "head")

    env = {**os.environ, "DATABASE_URL": sqlite_env}
    proc = subprocess.run(
        [sys.executable, "-m", "alembic", "-c", INI_PATH, "check"],
        cwd=REPO_ROOT,
        env=env,
        capture_output=True,
        text=True,
    )
    assert proc.returncode == 0, f"schema drift detected:\n{proc.stdout}\n{proc.stderr}"


def test_production_startup_does_not_auto_create_schema(monkeypatch):
    from backend.app.config import get_settings

    monkeypatch.setenv("APP_ENV", "production")
    assert get_settings().auto_create_schema is False

    monkeypatch.setenv("APP_ENV", "development")
    assert get_settings().auto_create_schema is True

    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("AUTO_CREATE_SCHEMA", "true")
    assert get_settings().auto_create_schema is True  # explicit override
