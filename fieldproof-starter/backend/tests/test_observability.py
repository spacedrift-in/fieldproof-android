"""Observability tests: structured logging, redaction and metrics (KL-034).

Asserts that diagnostics never contain credentials/notes/photo bytes and that
the alerting signals are exposed on ``/metrics``.
"""
from __future__ import annotations

import json
import logging
from contextlib import contextmanager

from backend.app.observability import (
    JsonFormatter,
    RedactionFilter,
    normalize_path,
    redact_text,
)


def test_redact_masks_bearer_tokens_and_secrets():
    assert "supersecret" not in redact_text("Authorization: Bearer supersecret.token.value")
    assert "[redacted]" in redact_text("Authorization: Bearer supersecret.token.value")
    assert "hunter2" not in redact_text("api_key=hunter2XYZ")
    assert "abc123" not in redact_text("password: abc123")


def test_redact_preserves_non_sensitive_text():
    assert redact_text("Version conflict: the inspection changed") == (
        "Version conflict: the inspection changed"
    )


def test_normalize_path_collapses_ids_for_low_cardinality_metric_labels():
    path = "/api/v1/inspections/3fa85f64-5717-4562-b3fc-2c963f66afa6/report"
    normalized = normalize_path(path)
    assert normalized == "/api/v1/inspections/{id}/report"
    assert normalize_path("/health") == "/health"


def test_json_formatter_applies_redaction():
    stream = _CapturedLogger()
    stream.logger.info("upload failed token=abc123secretvalue for Bearer jwt.header.here")
    output = stream.getvalue()
    assert "abc123secretvalue" not in output
    assert "jwt.header.here" not in output
    payload = json.loads(output)
    assert payload["message"] == "upload failed token=[redacted] for Bearer [redacted]"


def test_json_formatter_emits_valid_json_with_extra_fields():
    stream = _CapturedLogger()
    stream.logger.info("http_request", extra={"extra_fields": {"status": 404, "path": "/health"}})
    payload = json.loads(stream.getvalue())
    assert payload["message"] == "http_request"
    assert payload["status"] == 404
    assert payload["path"] == "/health"
    assert payload["level"] == "INFO"


def test_request_log_line_omits_notes_and_ids(client):
    """A request log must not leak the note text or raw entity ids."""
    from backend.tests.test_inspections import _checklist_items, _create, _set_outcome

    _, inspection_id, _, _ = _create(client)
    item = _checklist_items(client, inspection_id)[0]
    secret_note = "Patient consent ref 998877"

    with _capture_request_logs() as stream:
        _set_outcome(client, inspection_id, item["id"], "FAIL", note=secret_note)
    output = stream.getvalue()

    assert "http_request" in output
    assert secret_note not in output
    # Paths are normalized, so raw UUIDs do not appear in diagnostics.
    assert inspection_id not in output


def test_metrics_endpoint_exposes_alerting_signals(client):
    client.get("/health")
    response = client.get("/metrics")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    body = response.text
    assert "http_requests_total" in body
    assert "http_request_duration_seconds_count" in body
    # Error/conflict signals used for alerting.
    assert "# TYPE http_requests_total counter" in body


def test_metrics_endpoint_requires_token_when_configured(make_client):
    client = make_client(METRICS_ENABLED="true", METRICS_ADMIN_TOKEN="open-sesame")
    assert client.get("/metrics").status_code == 401
    allowed = client.get("/metrics", headers={"X-Admin-Token": "open-sesame"})
    assert allowed.status_code == 200


def test_metrics_endpoint_can_be_disabled(make_client):
    client = make_client(METRICS_ENABLED="false")
    assert client.get("/metrics").status_code == 404


def test_api_conflict_metric_counts_409s(client):
    from backend.tests.test_inspections import _create, _set_outcome, _checklist_items

    _, inspection_id, _, _ = _create(client)
    item = _checklist_items(client, inspection_id)[0]
    _set_outcome(client, inspection_id, item["id"], "PASS", expected_version=999)  # 409

    body = client.get("/metrics").text
    assert "api_conflicts_total" in body


def test_rate_limit_rejection_metric(make_client):
    client = make_client(
        RATE_LIMIT_ENABLED="true", RATE_LIMIT_REQUESTS="1", RATE_LIMIT_WINDOW_SECONDS="60"
    )
    import uuid as _uuid

    missing = str(_uuid.uuid4())
    client.get(f"/api/v1/inspections/{missing}")
    client.get(f"/api/v1/inspections/{missing}")  # 429

    body = client.get("/metrics").text
    assert "rate_limit_rejections_total" in body


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

class _CapturedLogger:
    """Log to an in-memory stream through the production formatter/filter."""

    def __init__(self):
        import io

        self._stream = io.StringIO()
        handler = logging.StreamHandler(self._stream)
        handler.setFormatter(JsonFormatter())
        handler.addFilter(RedactionFilter())
        self.logger = logging.getLogger("fieldproof.test.observability")
        self._old_handlers = self.logger.handlers
        self._old_propagate = self.logger.propagate
        self.logger.handlers = [handler]
        self.logger.propagate = False
        self.logger.setLevel(logging.DEBUG)

    def getvalue(self) -> str:
        return self._stream.getvalue()


@contextmanager
def _capture_request_logs():
    """Route the app's request-log records into an in-memory stream.

    Targets the logger the middleware actually writes to, so the capture is
    independent of root-logger state set up by other tests or pytest plugins.
    """
    import io

    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())
    handler.addFilter(RedactionFilter())

    logger = logging.getLogger("fieldproof.request")
    saved = (logger.handlers, logger.propagate, logger.level, logger.disabled)
    logger.handlers = [handler]
    logger.propagate = False
    logger.setLevel(logging.INFO)
    logger.disabled = False
    try:
        yield stream
    finally:
        logger.handlers, logger.propagate, logger.level, logger.disabled = saved
