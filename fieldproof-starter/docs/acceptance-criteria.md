# MVP acceptance criteria

- [ ] Inspection and checklist changes persist after process termination while offline.
- [ ] Photos and notes persist locally and are linked to the correct checklist item.
- [ ] Missing required evidence blocks submission and report finalization on the server.
- [ ] Sync UI distinguishes queued, uploading, synced, failed, and conflict states.
- [ ] Replaying the same upload operation does not create duplicate evidence.
- [ ] Interrupted upload can be retried without losing local evidence.
- [ ] Permission denial provides actionable feedback and does not corrupt local work.
- [ ] Conflicting updates return a visible conflict instead of silently overwriting.
- [ ] Reviewer sign-off references a specific inspection version.
- [ ] PDF includes inspection summary, checklist outcomes, evidence references/images, timestamps, and reviewer decision.
- [ ] Loading, empty, error, and success states are present on primary flows.
- [x] Fixtures, logs, and screenshots contain no secrets or private client data.
      (fixtures under `tests/fixtures/` and `backend/app/demo_data.py`; screenshots
      in `docs/screenshots/`; `.gitignore` excludes `.env`, `local.properties`,
      `.venv/`, `backend/var/`)
- [x] Setup instructions work on a clean environment and list required versions.
      (`scripts/verify_clean_setup.sh`; versions in `README.md` and `docs/demo.md`)
- [x] Demo explicitly identifies synthetic data and documents limitations.
      (`docs/demo.md` and `docs/known-limitations.md`)
