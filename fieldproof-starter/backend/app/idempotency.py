"""Helpers for recording and replaying idempotent requests.

A record is unique per ``(key, scope)``. Repeating a request with the same key
and the same fingerprint returns the stored response; the same key with a
different fingerprint is a conflict.
"""
from __future__ import annotations

import hashlib
import json
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import IdempotencyRecord


def fingerprint(*parts: Any) -> str:
    """Stable SHA-256 over heterogeneous request parts."""
    digest = hashlib.sha256()
    for part in parts:
        chunk = part if isinstance(part, bytes) else _canonical(part)
        digest.update(len(chunk).to_bytes(8, "big"))
        digest.update(chunk)
    return digest.hexdigest()


def _canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), default=str).encode()


def get_record(db: Session, key: str, scope: str) -> IdempotencyRecord | None:
    stmt = select(IdempotencyRecord).where(
        IdempotencyRecord.key == key,
        IdempotencyRecord.scope == scope,
    )
    return db.execute(stmt).scalar_one_or_none()


def delete_for_entities(db: Session, entity_ids: list[str]) -> int:
    """Delete idempotency records that reference any of ``entity_ids``.

    Called when inspections/evidence are deleted so a replayed request cannot
    return a stored response for a record that no longer exists.
    """
    if not entity_ids:
        return 0
    records = db.execute(
        select(IdempotencyRecord).where(IdempotencyRecord.entity_id.in_(entity_ids))
    ).scalars().all()
    for record in records:
        db.delete(record)
    return len(records)


def store(
    db: Session,
    *,
    key: str,
    scope: str,
    request_fingerprint: str,
    entity_type: str,
    entity_id: Any,
    status_code: int,
    body: dict,
) -> IdempotencyRecord:
    record = IdempotencyRecord(
        key=key,
        scope=scope,
        fingerprint=request_fingerprint,
        entity_type=entity_type,
        entity_id=str(entity_id),
        response_status=status_code,
        response_body=body,
    )
    db.add(record)
    return record
