"""Malware-scanning integration point for uploaded evidence.

The core app depends only on the :class:`MalwareScanner` protocol. The default
:class:`NoOpScanner` keeps local dev and tests dependency free; production
should set ``MALWARE_SCANNER=clamav`` and point ``CLAMAV_HOST``/``CLAMAV_PORT``
at a ClamAV daemon (or an equivalent object-store scan service). The ClamAV
adapter imports the optional ``clamd`` package lazily so it is never required at
import time.
"""
from __future__ import annotations

import io
import logging
from dataclasses import dataclass
from typing import Literal, Protocol

logger = logging.getLogger(__name__)

ScanStatus = Literal["clean", "infected", "error"]


@dataclass(frozen=True)
class ScanVerdict:
    status: ScanStatus
    signature: str | None = None

    @property
    def clean(self) -> bool:
        return self.status == "clean"


class MalwareScanner(Protocol):
    def scan(self, data: bytes, *, filename: str | None, content_type: str | None) -> ScanVerdict: ...


class NoOpScanner:
    """Development/test scanner that trusts all input."""

    def scan(self, data: bytes, *, filename: str | None, content_type: str | None) -> ScanVerdict:
        return ScanVerdict(status="clean")


class ClamAVScanner:
    """Adapter over ClamAV's ``clamd`` network interface (INSTREAM)."""

    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self._client = None

    def _get_client(self):
        if self._client is None:
            try:
                import clamd  # type: ignore[import-not-found]
            except ImportError as exc:  # pragma: no cover - depends on deploy
                raise RuntimeError(
                    "MALWARE_SCANNER=clamav requires the optional 'clamd' package."
                ) from exc
            self._client = clamd.ClamdNetworkSocket(host=self.host, port=self.port)
        return self._client

    def scan(self, data: bytes, *, filename: str | None, content_type: str | None) -> ScanVerdict:
        try:
            client = self._get_client()
            result = client.instream(io.BytesIO(data))
        except Exception:  # noqa: BLE001 - treat any scanner failure as an error verdict
            logger.exception("Malware scan failed")
            return ScanVerdict(status="error")

        # clamd returns ``{name: ("OK", None)}`` or ``{name: ("FOUND", signature)}``.
        if not result:
            return ScanVerdict(status="error")
        first = next(iter(result.values()))
        verdict = first[0]
        if verdict == "OK":
            return ScanVerdict(status="clean")
        if verdict == "FOUND":
            return ScanVerdict(status="infected", signature=first[1] if len(first) > 1 else None)
        return ScanVerdict(status="error")


_scanner: MalwareScanner | None = None
_scanner_key: tuple[str, str, int] | None = None


def get_scanner(settings) -> MalwareScanner:
    global _scanner, _scanner_key
    key = (settings.malware_scanner, settings.clamav_host, settings.clamav_port)
    if _scanner is None or _scanner_key != key:
        if settings.malware_scanner == "clamav":
            _scanner = ClamAVScanner(settings.clamav_host, settings.clamav_port)
        else:
            _scanner = NoOpScanner()
        _scanner_key = key
    return _scanner


def reset_scanner() -> None:
    global _scanner, _scanner_key
    _scanner = None
    _scanner_key = None
