"""Evidence object storage behind a small abstraction.

Two implementations are provided:

* :class:`LocalEvidenceStorage` -- a **development only** filesystem backend.
* :class:`S3EvidenceStorage` -- durable, private object storage (S3 or any
  S3-compatible service such as MinIO). It enables server-side encryption,
  can apply a lifecycle policy (archive/expire), and assumes the bucket blocks
  all public access.

The backend is selected with ``EVIDENCE_STORAGE_BACKEND`` (``local``/``s3``).
``boto3`` is imported lazily only when the S3 backend is actually constructed,
so the core app stays dependency free.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any, Protocol

from .config import Settings, get_settings

logger = logging.getLogger(__name__)


class EvidenceStorage(Protocol):
    def save(self, key: str, data: bytes, content_type: str | None = None) -> None: ...
    def read(self, key: str) -> bytes: ...
    def exists(self, key: str) -> bool: ...
    def delete(self, key: str) -> None: ...


class LocalEvidenceStorage:
    """Development filesystem storage. Not for production use."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(self.root, 0o700)
        except OSError:  # pragma: no cover - best effort on exotic filesystems
            pass

    def _resolve(self, key: str) -> Path:
        target = (self.root / key).resolve()
        root = self.root.resolve()
        if target != root and root not in target.parents:
            raise ValueError(f"invalid storage key: {key!r}")
        return target

    def save(self, key: str, data: bytes, content_type: str | None = None) -> None:
        target = self._resolve(key)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        try:
            os.chmod(target, 0o600)
        except OSError:  # pragma: no cover
            pass

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

    def check(self) -> bool:
        return self.root.exists()


def _is_not_found(exc: Exception) -> bool:
    response = getattr(exc, "response", None)
    if isinstance(response, dict):
        code = str(response.get("Error", {}).get("Code", ""))
        status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        return code in {"404", "NoSuchKey", "NotFound"} or status == 404
    return False


class S3EvidenceStorage:
    """Private, encrypted object storage. Accepts an injected client for tests."""

    def __init__(
        self,
        bucket: str,
        *,
        prefix: str = "",
        region: str | None = None,
        endpoint_url: str | None = None,
        use_ssl: bool = True,
        sse: str = "AES256",
        kms_key_id: str | None = None,
        lifecycle_days: int = 0,
        lifecycle_transition_days: int = 0,
        lifecycle_glacier_days: int = 0,
        client: Any | None = None,
    ) -> None:
        if not bucket:
            raise ValueError("EVIDENCE_S3_BUCKET must be set when using the s3 backend")
        self.bucket = bucket
        self.prefix = prefix.strip("/")
        self.sse = sse
        self.kms_key_id = kms_key_id
        self.lifecycle_days = lifecycle_days
        self.lifecycle_transition_days = lifecycle_transition_days
        self.lifecycle_glacier_days = lifecycle_glacier_days
        self._client = client if client is not None else _build_s3_client(
            region=region, endpoint_url=endpoint_url, use_ssl=use_ssl
        )

    def _key(self, key: str) -> str:
        key = key.lstrip("/")
        return f"{self.prefix}/{key}" if self.prefix else key

    def _encryption_args(self) -> dict[str, Any]:
        args: dict[str, Any] = {}
        if not self.sse:
            return args
        args["ServerSideEncryption"] = self.sse
        if self.sse == "aws:kms" and self.kms_key_id:
            args["SSEKMSKeyId"] = self.kms_key_id
        return args

    def save(self, key: str, data: bytes, content_type: str | None = None) -> None:
        kwargs: dict[str, Any] = {"Bucket": self.bucket, "Key": self._key(key), "Body": data}
        if content_type:
            kwargs["ContentType"] = content_type
        kwargs.update(self._encryption_args())
        self._client.put_object(**kwargs)

    def read(self, key: str) -> bytes:
        obj = self._client.get_object(Bucket=self.bucket, Key=self._key(key))
        return obj["Body"].read()

    def exists(self, key: str) -> bool:
        try:
            self._client.head_object(Bucket=self.bucket, Key=self._key(key))
            return True
        except Exception as exc:  # noqa: BLE001
            if _is_not_found(exc):
                return False
            raise

    def delete(self, key: str) -> None:
        self._client.delete_object(Bucket=self.bucket, Key=self._key(key))

    def path_for(self, key: str):  # pragma: no cover - object stores have no paths
        raise NotImplementedError("S3-backed evidence has no local filesystem path")

    def check(self) -> bool:
        try:
            self._client.head_bucket(Bucket=self.bucket)
            return True
        except Exception:  # noqa: BLE001
            logger.warning("Object-storage readiness check failed", exc_info=True)
            return False

    # -- Bucket hardening helpers (called best-effort at startup) ----------

    def ensure_private(self) -> None:
        self._client.put_public_access_block(
            Bucket=self.bucket,
            PublicAccessBlockConfiguration={
                "BlockPublicAcls": True,
                "IgnorePublicAcls": True,
                "BlockPublicPolicy": True,
                "RestrictPublicBuckets": True,
            },
        )

    def apply_lifecycle(self) -> None:
        rules: list[dict[str, Any]] = []
        transitions: list[dict[str, Any]] = []
        if self.lifecycle_transition_days > 0:
            transitions.append(
                {"Days": self.lifecycle_transition_days, "StorageClass": "STANDARD_IA"}
            )
        if self.lifecycle_glacier_days > 0:
            transitions.append({"Days": self.lifecycle_glacier_days, "StorageClass": "GLACIER"})
        rule: dict[str, Any] = {
            "ID": "fieldproof-evidence-lifecycle",
            "Filter": {"Prefix": self.prefix} if self.prefix else {"Prefix": ""},
            "Status": "Enabled",
        }
        if transitions:
            rule["Transitions"] = transitions
        if self.lifecycle_days > 0:
            rule["Expiration"] = {"Days": self.lifecycle_days}
        rules.append(rule)
        self._client.put_bucket_lifecycle_configuration(
            Bucket=self.bucket, LifecycleConfiguration={"Rules": rules}
        )


def _build_s3_client(*, region: str | None, endpoint_url: str | None, use_ssl: bool):
    try:
        import boto3  # type: ignore[import-not-found]
    except ImportError as exc:  # pragma: no cover - depends on deploy
        raise RuntimeError(
            "EVIDENCE_STORAGE_BACKEND=s3 requires the optional 'boto3' package "
            "(see backend/requirements-prod.txt)."
        ) from exc
    return boto3.client("s3", region_name=region, endpoint_url=endpoint_url, use_ssl=use_ssl)


_storage: EvidenceStorage | None = None
_storage_key: tuple[Any, ...] | None = None


def _make_storage(settings: Settings) -> EvidenceStorage:
    if settings.storage_backend == "s3":
        return S3EvidenceStorage(
            settings.s3_bucket,
            prefix=settings.s3_prefix,
            region=settings.s3_region,
            endpoint_url=settings.s3_endpoint_url,
            use_ssl=settings.s3_use_ssl,
            sse=settings.s3_sse,
            kms_key_id=settings.s3_kms_key_id,
            lifecycle_days=settings.s3_lifecycle_days,
            lifecycle_transition_days=settings.s3_lifecycle_transition_days,
            lifecycle_glacier_days=settings.s3_lifecycle_glacier_days,
        )
    return LocalEvidenceStorage(settings.evidence_storage_dir)


def get_storage() -> EvidenceStorage:
    global _storage, _storage_key
    settings = get_settings()
    key = (
        settings.storage_backend,
        settings.s3_bucket,
        settings.s3_prefix,
        settings.s3_region,
        settings.s3_endpoint_url,
        settings.s3_sse,
        settings.s3_kms_key_id,
        str(settings.evidence_storage_dir),
    )
    if _storage is None or _storage_key != key:
        _storage = _make_storage(settings)
        _storage_key = key
    return _storage


def configure_storage() -> EvidenceStorage:
    """Construct storage and, for S3, apply private-access + lifecycle policy.

    Failures are logged but non-fatal so a transient object-store hiccup does
    not prevent the API process from starting (readiness will surface it).
    """
    storage = get_storage()
    if isinstance(storage, S3EvidenceStorage):
        for step in (storage.ensure_private, storage.apply_lifecycle):
            try:
                step()
            except Exception:  # noqa: BLE001
                logger.warning("Object-storage hardening step %s failed", step.__name__, exc_info=True)
    return storage


def reset_storage() -> None:
    global _storage, _storage_key
    _storage = None
    _storage_key = None
