"""Request-scoped tenant resolution.

Authentication is a known limitation (see ``docs/known-limitations.md`` KL-001).
Until an authenticated principal exists, the tenant is taken from the
``X-Tenant-Id`` header (defaulting to ``DEFAULT_TENANT``). This is sufficient to
namespace stored objects and enforce tenant-scoped reads, and the header is the
single place that will later be replaced by a verified identity claim.
"""
from __future__ import annotations

import re

from fastapi import Header, HTTPException

from .config import get_settings

_TENANT_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$")


def resolve_tenant_id(x_tenant_id: str | None) -> str:
    if x_tenant_id is None or not x_tenant_id.strip():
        return get_settings().default_tenant
    value = x_tenant_id.strip()
    if not _TENANT_RE.match(value):
        raise HTTPException(
            status_code=400,
            detail="Invalid X-Tenant-Id: use 1-63 characters [A-Za-z0-9_-].",
        )
    return value


def tenant_id(
    x_tenant_id: str | None = Header(default=None, alias="X-Tenant-Id"),
) -> str:
    return resolve_tenant_id(x_tenant_id)
