# Disaster recovery & backups

> **Goal.** Define backup/restore procedures and recovery objectives for the two
> stateful systems behind FieldProof: **PostgreSQL** (inspections, checklist
> items, evidence metadata, reviews, idempotency records) and the **evidence
> object store** (photo binaries). Config is injected from a secret manager and
> the app is stateless, so it can be rebuilt from the image plus config.

## 1. Recovery objectives (starting targets)

| Tier | RPO (max data loss) | RTO (time to restore) | Notes |
|---|---|---|---|
| Metadata (Postgres) | ≤ 5 minutes | ≤ 1 hour | Continuous WAL archiving + PITR. |
| Evidence objects (S3) | ≤ 15 minutes | ≤ 2 hours | Versioning + cross-region replication. |
| Idempotency records | ≤ 5 minutes | ≤ 1 hour | Restored with Postgres. The client retries safely, so a little loss is tolerable. |
| Config/secrets | 0 | ≤ 30 minutes | Stored in the secret manager with its own backup. |

Adjust these to contractual SLAs; they are deliberately conservative starting
points.

## 2. What must be backed up

1. **PostgreSQL** — logical dumps *and* continuous PITR (managed snapshots +
   WAL archiving, or `pg_basebackup` + archive_command / pgBackRest for
   self-hosted).
2. **Object storage** — bucket versioning enabled; replicate to a second
   region/bucket. Lifecycle rules move old objects to colder storage but must
   **not** delete before the retention window you can tolerate.
3. **Secrets/config** — export from the secret manager into its own encrypted
   backup; losing the KMS key makes encrypted evidence unrecoverable.
4. **Images/tooling** — the exact `fieldproof-api` image tag and
   `docker-compose.prod.yml` revision for the deploy you are recovering.

## 3. Backup schedule (example)

| Asset | Frequency | Retention | Method |
|---|---|---|---|
| Postgres base backup | daily | 30 days | managed snapshot / `pg_basebackup` |
| Postgres WAL | continuous | 7–30 days | WAL archiving → object storage |
| Postgres logical dump | daily | 90 days | `pg_dump -Fc` to a separate account |
| Evidence bucket | continuous | per policy | versioning + replication |
| Secrets export | weekly | 90 days | secret-manager export, encrypted |

Store backups in a **separate account/region** from production so a
region-wide or account-level failure is survivable. Encrypt backups at rest and
test that restore credentials are separate from production write credentials.

## 4. Restore runbooks

### 4.1 Restore PostgreSQL (point-in-time)

```bash
# 1. Provision a fresh instance (or isolate the target) with the same major version.
# 2. Restore the most recent base backup, then replay WAL to the target time.
pg_restore -h <host> -U <user> -d fieldproof --clean --if-exists /backups/fieldproof.dump
# 3. Verify counts and the newest inspection/evidence timestamps.
psql -h <host> -U <user> -d fieldproof -c \
  "select count(*) from inspections; select count(*) from evidence;"
```

For PITR: restore base backup, set `recovery_target_time`, and start Postgres to
replay WAL up to the desired point.

### 4.2 Restore evidence objects

```bash
# From a versioned/replicated bucket:
aws s3 sync s3://fieldproof-evidence-backup/ s3://fieldproof-evidence/ --delete
# Or restore specific object versions via the S3 API / console.
```

After restoring, run an integrity spot-check: for a sample of `evidence` rows
compare `sha256` and `size_bytes` against the downloaded object.

### 4.3 Rebuild the API

```bash
git checkout <known-good-revision>
docker compose -f docker-compose.prod.yml up -d --build
curl -fsS https://<host>/readyz   # must be 200 with database+storage ok
```

## 5. Failure playbooks

- **Object-store outage (app can't read/write evidence):** `/readyz` returns
  `503` (`storage=error`); the load balancer drains the instance. Uploads fail
  with `5xx`; the Android client keeps local evidence and retries later, so no
  work is lost. Restore storage access, then re-check `/readyz`.
- **Database outage:** `/readyz` returns `503` (`database=error`). Restore/fail
  over Postgres, then re-check readiness. Requests during the window fail
  safely and are retried by the client.
- **Malware scanner outage:** with `MALWARE_SCAN_FAIL_CLOSED=true`, uploads
  return `503` and are retried; with `false`, uploads are accepted unscanned
  (only acceptable as a deliberate, temporary trade-off).
- **KMS key loss:** encrypted evidence is unrecoverable. Guard the key with
  deletion protection, replication and separate backup.
- **Accidental tenant deletion / bad write:** Postgres PITR + S3 object
  versioning allow a targeted, tenant-scoped restore.

## 6. Verification & drills

- **Quarterly:** restore Postgres to a scratch environment and run the backend
  suite against it (`TEST_DATABASE_URL=... pytest tests`).
- **Quarterly:** restore a sample of evidence objects and verify checksums.
- **Semiannually:** full region-loss simulation (rebuild from backups + image).
- Record, for each drill: start/end time (actual RTO), data recovered (actual
  RPO), and follow-up actions. An untested backup is not a backup.

## 7. Current gaps / follow-ups

- **Migrations (KL-003):** schema is created with SQLAlchemy `create_all`; there
  is no versioned migration history, so point-in-time restores and forward roll
  forwards are coarser than they should be. Introduce Alembic before GA.
- **Rate-limit state is per-process**, so it is not part of backup/restore and
  resets on restart — expected.
- **Local (`EVIDENCE_STORAGE_BACKEND=local`) storage is not durable** and has no
  backup story; it exists only for development.
