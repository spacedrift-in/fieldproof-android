"""Database engine, session factory and declarative base.

The engine is created lazily so tests can point ``DATABASE_URL`` at a disposable
database before the first connection is opened.
"""
from __future__ import annotations

from collections.abc import Iterator

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import get_settings


class Base(DeclarativeBase):
    pass


_engine: Engine | None = None
_session_factory: sessionmaker[Session] | None = None


def get_engine() -> Engine:
    global _engine
    if _engine is None:
        _engine = create_engine(
            get_settings().database_url,
            pool_pre_ping=True,
            future=True,
        )
    return _engine


def get_session_factory() -> sessionmaker[Session]:
    global _session_factory
    if _session_factory is None:
        _session_factory = sessionmaker(
            bind=get_engine(),
            autoflush=False,
            autocommit=False,
            expire_on_commit=False,
            class_=Session,
        )
    return _session_factory


def reset_engine() -> None:
    """Drop cached engine/session factory (used by tests between databases)."""
    global _engine, _session_factory
    if _engine is not None:
        _engine.dispose()
    _engine = None
    _session_factory = None


def get_db() -> Iterator[Session]:
    """FastAPI dependency yielding a scoped database session."""
    session = get_session_factory()()
    try:
        yield session
    finally:
        session.close()


def init_db() -> None:
    """Create tables if they do not exist (demo bootstrap only).

    Production never calls this: schema is managed by migrations (KL-003). See
    :func:`run_migrations`.
    """
    from . import models  # noqa: F401  (registers ORM mappings)

    Base.metadata.create_all(bind=get_engine())


def run_migrations(revision: str = "head") -> None:
    """Apply Alembic migrations against ``DATABASE_URL``.

    This is the explicit, production schema path. It is never invoked implicitly
    at startup; operators/CI run it during deploy. Failures are counted in
    ``migration_failures_total`` so they can be alerted on.
    """
    from alembic import command
    from alembic.config import Config

    from .observability import metrics

    from .config import BACKEND_DIR

    ini_path = BACKEND_DIR / "alembic.ini"
    cfg = Config(str(ini_path))
    # Do not let alembic's env.py reconfigure the process logging (fileConfig
    # would reset root level/handlers). The CLI still configures its own logger.
    cfg.attributes["configure_logger"] = False
    try:
        command.upgrade(cfg, revision)
    except Exception:
        metrics.inc("migration_failures_total")
        raise
    else:
        metrics.inc("migrations_applied_total")
