"""Evidence file storage behind a small abstraction.

The demo writes to the local filesystem. A future object-store implementation
(S3/GCS) only needs to satisfy :class:`EvidenceStorage`.
"""
from __future__ import annotations

from pathlib import Path
from typing import Protocol

from .config import get_settings


class EvidenceStorage(Protocol):
    def save(self, key: str, data: bytes) -> None: ...
    def read(self, key: str) -> bytes: ...
    def path_for(self, key: str) -> Path: ...
    def exists(self, key: str) -> bool: ...
    def delete(self, key: str) -> None: ...


class LocalEvidenceStorage:
    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _resolve(self, key: str) -> Path:
        target = (self.root / key).resolve()
        root = self.root.resolve()
        if target != root and root not in target.parents:
            raise ValueError(f"invalid storage key: {key!r}")
        return target

    def save(self, key: str, data: bytes) -> None:
        target = self._resolve(key)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def read(self, key: str) -> bytes:
        return self._resolve(key).read_bytes()

    def path_for(self, key: str) -> Path:
        return self._resolve(key)

    def exists(self, key: str) -> bool:
        return self._resolve(key).exists()

    def delete(self, key: str) -> None:
        target = self._resolve(key)
        if target.exists():
            target.unlink()


def get_storage() -> EvidenceStorage:
    return LocalEvidenceStorage(get_settings().evidence_storage_dir)
