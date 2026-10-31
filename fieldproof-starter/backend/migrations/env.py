"""Alembic environment.

Imports the ORM metadata from the application package and runs migrations
against the database URL from the application settings (``DATABASE_URL``), so no
credentials are ever written into ``alembic.ini``.

The import works in both supported layouts:

* repo checkout: package is ``backend.app`` (``fieldproof-starter/backend/app``)
* runtime image: package is ``app`` (``/app/app``)

Run from the ``fieldproof-starter`` directory:

    python -m alembic -c backend/alembic.ini upgrade head
    python -m alembic -c backend/alembic.ini revision --autogenerate -m "message"
"""
from __future__ import annotations

import importlib
import sys
from logging.config import fileConfig
from pathlib import Path

from alembic import context
from sqlalchemy import engine_from_config, pool

# ``migrations/env.py`` -> parents[1] is the package parent (``backend`` in the
# repo, ``/app`` in the image) and parents[2] is the repo root that makes
# ``backend.app`` importable. Append both so the import below succeeds either way.
_HERE = Path(__file__).resolve()
for _candidate in (_HERE.parents[1], _HERE.parents[2]):
    if _candidate.exists() and str(_candidate) not in sys.path:
        sys.path.append(str(_candidate))


def _load_app():
    """Return ``(metadata, get_settings, package)`` for whichever layout exists."""
    for package in ("backend.app", "app"):
        try:
            db_module = importlib.import_module(f"{package}.db")
            config_module = importlib.import_module(f"{package}.config")
            importlib.import_module(f"{package}.models")  # registers mappings
            return db_module.Base.metadata, config_module.get_settings, package
        except ModuleNotFoundError:
            continue
    raise ModuleNotFoundError(
        "Could not import the application package for Alembic "
        "(tried 'backend.app' and 'app')."
    )


config = context.config

if config.config_file_name is not None:
    # ``disable_existing_loggers`` must be False: env.py also runs in-process via
    # ``db.run_migrations()`` at startup, and the default (True) would silence
    # every application logger for the life of the service.
    fileConfig(config.config_file_name, disable_existing_loggers=False)

target_metadata, get_settings, _package = _load_app()

# The URL always comes from app settings (DATABASE_URL) rather than alembic.ini.
config.set_main_option(
    "sqlalchemy.url",
    get_settings().database_url.replace("%", "%%"),
)


def run_migrations_offline() -> None:
    """Emit migration SQL to stdout without a live connection (CI / review)."""
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """Run migrations against a live connection."""
    engine = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with engine.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
