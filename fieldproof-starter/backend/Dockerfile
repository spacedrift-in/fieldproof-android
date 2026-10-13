# syntax=docker/dockerfile:1
# Production image for the FieldProof API.
#
# Multi-stage build: dependencies are compiled/installed into a virtualenv in
# the builder stage, then copied into a slim runtime image that runs as a
# non-root user. No secrets are baked into the image; everything is injected at
# runtime (see docs/deployment.md).

FROM python:3.13-slim AS builder
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1
WORKDIR /build
COPY requirements.txt requirements-prod.txt ./
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --upgrade pip \
    && /opt/venv/bin/pip install -r requirements.txt -r requirements-prod.txt

FROM python:3.13-slim AS runtime
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# Unprivileged runtime account.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --home-dir /app app

WORKDIR /app
COPY --from=builder /opt/venv /opt/venv
COPY app ./app

# Writable location used only by the local (development) storage backend.
RUN mkdir -p /var/lib/fieldproof/evidence \
    && chown -R app:app /app /var/lib/fieldproof
USER app

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4).status == 200 else 1)"

CMD ["gunicorn", "app.main:app", \
     "-k", "uvicorn.workers.UvicornWorker", \
     "-w", "4", "-b", "0.0.0.0:8000", \
     "--access-logfile", "-", "--error-logfile", "-"]
