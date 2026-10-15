"""Structured logging, request context and metrics.

Design goals (see ``docs/observability.md``):

* exactly one JSON log line per request, with **no** request bodies, credentials
  or photo bytes;
* defence-in-depth redaction so a stray token/secret/note never lands in a log,
  even if it appears inside an exception message;
* a dependency-free Prometheus text endpoint exposing the signals we alert on
  (API error rate, upload latency, conflicts/sync failures, storage failures,
  migration failures).

Logging a request never emits the payload: only method, normalized route,
status, duration and a generated request id are recorded.
"""
from __future__ import annotations

import json
import logging
import re
import threading
import time
import uuid
from datetime import datetime, timezone
from typing import Any

REDACTION = "[redacted]"

# Order matters: the bearer prefix must be handled before the generic rule.
_SENSITIVE_PATTERNS = [
    re.compile(r"(?i)(bearer\s+)([A-Za-z0-9._\-]+)"),
    re.compile(
        r"(?i)((?:token|secret|password|passwd|api[_-]?key|authorization)\s*[:=]\s*)"
        r"(\"[^\"]*\"|'[^']*'|[^\s,;\"'}]+)"
    ),
    re.compile(r"(?i)((?:x-admin-token|idempotency-key|x-tenant-id)\s*[:=]\s*)([^\s,;\"'}]+)"),
    # Long opaque base64/hex runs (tokens, keys) that are not already caught.
    re.compile(r"\b[A-Za-z0-9+/_\-]{40,}={0,2}\b"),
]

_UUID_RE = re.compile(
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
)


def redact_text(text: str | None) -> str | None:
    """Mask credentials/identifiers that must never appear in diagnostics."""
    if not text:
        return text
    for pattern in _SENSITIVE_PATTERNS:
        if pattern.groups >= 2:
            text = pattern.sub(lambda m: m.group(1) + REDACTION, text)
        else:
            text = pattern.sub(REDACTION, text)
    return text


def redact_mapping(value: Any) -> Any:
    """Recursively redact strings inside a structured-log payload."""
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, dict):
        return {k: redact_mapping(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return type(value)(redact_mapping(v) for v in value)
    return value


class RedactionFilter(logging.Filter):
    """Scrub sensitive substrings from every record before it is formatted.

    Only string values are rewritten; other argument types are preserved so a
    ``%d``/``%r`` in a third-party log format string keeps working.
    """

    def filter(self, record: logging.LogRecord) -> bool:  # noqa: A003
        try:
            record.msg = redact_text(str(record.msg))
            if record.args:
                if isinstance(record.args, dict):
                    record.args = {
                        key: redact_text(value) if isinstance(value, str) else value
                        for key, value in record.args.items()
                    }
                else:
                    record.args = tuple(
                        redact_text(arg) if isinstance(arg, str) else arg
                        for arg in record.args
                    )
        except Exception:  # noqa: BLE001 - logging must never raise
            return True
        return True


class JsonFormatter(logging.Formatter):
    """Render log records as single-line JSON with redaction applied."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": redact_text(record.getMessage()),
        }
        fields = getattr(record, "extra_fields", None)
        if isinstance(fields, dict):
            payload.update(redact_mapping(fields))
        if record.exc_info:
            payload["exc"] = redact_text(self.formatException(record.exc_info))
        return json.dumps(payload, default=str)


_configured = False
_configure_lock = threading.Lock()


def configure_logging(level: int = logging.INFO) -> None:
    """Install the JSON formatter + redaction filter on the root logger once."""
    global _configured
    with _configure_lock:
        if _configured:
            return
        handler = logging.StreamHandler()
        handler.setFormatter(JsonFormatter())
        handler.addFilter(RedactionFilter())

        root = logging.getLogger()
        # Drop any default handlers so the process emits structured logs only.
        root.handlers = [handler]
        root.setLevel(level)

        # Uvicorn installs its own handlers; route them through ours too.
        for name in ("uvicorn", "uvicorn.error", "uvicorn.access", "gunicorn.error"):
            uvicorn_logger = logging.getLogger(name)
            uvicorn_logger.handlers = []
            uvicorn_logger.propagate = True
        _configured = True


def reset_logging() -> None:
    """Test helper: allow ``configure_logging`` to run again."""
    global _configured
    with _configure_lock:
        _configured = False


def normalize_path(path: str) -> str:
    """Collapse high-cardinality path segments (ids) to keep metric labels bounded."""
    return _UUID_RE.sub("{id}", path or "")


def _format_value(value: float) -> str:
    if value == int(value):
        return str(int(value))
    return repr(value)


class MetricsRegistry:
    """Thread-safe in-process counter/summary registry with Prometheus output."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counters: dict[str, dict[str, float]] = {}
        self._summaries: dict[str, dict[str, list[float]]] = {}

    @staticmethod
    def _labels(labels: dict[str, str] | None) -> str:
        if not labels:
            return ""
        inner = ",".join(f'{k}="{v}"' for k, v in sorted(labels.items()))
        return f"{{{inner}}}"

    def inc(self, name: str, labels: dict[str, str] | None = None, value: float = 1.0) -> None:
        key = self._labels(labels)
        with self._lock:
            self._counters.setdefault(name, {})
            self._counters[name][key] = self._counters[name].get(key, 0.0) + value

    def observe(
        self, name: str, value: float, labels: dict[str, str] | None = None
    ) -> None:
        key = self._labels(labels)
        with self._lock:
            bucket = self._summaries.setdefault(name, {})
            entry = bucket.setdefault(key, [0.0, 0.0])
            entry[0] += 1
            entry[1] += value

    def render(self) -> str:
        lines: list[str] = []
        with self._lock:
            for name in sorted(self._counters):
                lines.append(f"# TYPE {name} counter")
                for key, value in sorted(self._counters[name].items()):
                    lines.append(f"{name}{key} {_format_value(value)}")
            for name in sorted(self._summaries):
                lines.append(f"# TYPE {name} summary")
                for key, (count, total) in sorted(self._summaries[name].items()):
                    if key:
                        inner = key[1:-1]
                        lines.append(f"{name}_count{{{inner}}} {_format_value(count)}")
                        lines.append(f"{name}_sum{{{inner}}} {_format_value(total)}")
                    else:
                        lines.append(f"{name}_count {_format_value(count)}")
                        lines.append(f"{name}_sum {_format_value(total)}")
        return "\n".join(lines) + "\n"

    def reset(self) -> None:
        with self._lock:
            self._counters.clear()
            self._summaries.clear()


metrics = MetricsRegistry()

_logger = logging.getLogger("fieldproof.request")


class RequestContextMiddleware:
    """Log one structured line per request and record latency/error metrics."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        headers = {k.decode().lower(): v.decode() for k, v in (scope.get("headers") or [])}
        request_id = headers.get("x-request-id") or uuid.uuid4().hex
        method = scope.get("method", "")
        path = normalize_path(scope.get("path", ""))
        start = time.perf_counter()
        status_holder = {"code": 500}

        async def send_wrapper(message):
            if message["type"] == "http.response.start":
                status_holder["code"] = message["status"]
                message.setdefault("headers", [])
                message["headers"].append((b"x-request-id", request_id.encode()))
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            duration = time.perf_counter() - start
            status = status_holder["code"]
            status_label = str(status)
            metrics.inc(
                "http_requests_total",
                {"method": method, "path": path, "status": status_label},
            )
            metrics.observe(
                "http_request_duration_seconds",
                duration,
                {"method": method, "path": path},
            )
            if status >= 500:
                metrics.inc("api_errors_total", {"path": path, "status": status_label})
            if status == 409:
                metrics.inc("api_conflicts_total", {"path": path})
            _logger.info(
                "http_request",
                extra={
                    "extra_fields": {
                        "request_id": request_id,
                        "method": method,
                        "path": path,
                        "status": status,
                        "duration_ms": round(duration * 1000, 2),
                        "client": (scope.get("client") or ("unknown",))[0],
                    }
                },
            )
