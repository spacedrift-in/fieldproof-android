# Release verification plan — device matrix, accessibility, performance & go/no-go

> A **template/plan** for the pre-release verification pass that a human runs on
> real hardware before a build ships. Nothing in this file has been executed in
> this environment: it requires physical devices and a live backend. Each
> section states what to run, the pass bar, and where to record the result.
> Cross-references: `acceptance-criteria.md`, `release.md`, `observability.md`,
> `known-limitations.md` (KL-008/KL-009).

**Status: PENDING EXECUTION.** Treat the signed report (§6) as the gate; do not
promote a build without it.

## 1. Device matrix

Test at minimum across the following. Record OS version, API level, screen size
and RAM for each row.

| # | Class | Example device | Android | Why |
|---|---|---|---|---|
| D1 | Low-end / small screen | 1 GB RAM, 720p, Android 8–9 | API 26–28 | Camera + crypto under memory pressure; oldest supported. |
| D2 | Mid-range | 3–4 GB, 1080p, Android 11–12 | API 30–31 | Representative field hardware. |
| D3 | Current | 6+ GB, Android 13–14 | API 33–34 | Modern storage/crypto behaviour (scoped storage, strong crypto). |
| D4 | Tablet / large font | Any, font scale 1.3–2.0 | any | Layout, TalkBack and large-font overflow. |

If a class cannot be obtained, note the gap in the report — do not silently
skip.

## 2. Functional & resilience scenarios

Run each scenario end to end (create inspection → checklist → photo → offline →
reconnect → submit → review → report) and record pass/fail + repro notes.

| ID | Scenario | Pass bar |
|---|---|---|
| S1 | Cold start, no account/network | Usable; clear empty/loading state, no crash (KL-008). |
| S2 | Airplane mode mid-inspection | Edits queue durably; nothing lost after force-stop + relaunch. |
| S3 | Poor/flaky network (2G/lossy) | Retries with backoff; no duplicate evidence; conflicts surface, not overwrite (KL-029/KL-033). |
| S4 | Camera permission denied → grant | Recoverable error screen; retake works. |
| S5 | Photo capture with camera busy/unavailable | Failed seal is surfaced; no plaintext evidence persisted (KL-030). |
| S6 | Storage almost full / write fails | Upload aborts cleanly, temp file removed, user told to retry. |
| S7 | Process death / reboot during sync | Sync resumes; no stuck `QUEUED`/`UPLOADING` item. |
| S8 | Re-login / keystore loss (if applicable) | Documented key-loss behaviour (security.md §3), no plaintext leak. |
| S9 | Evidence upload to backend with malware scan down | Fails closed per `MALWARE_SCAN_FAIL_CLOSED` (503), client handles error state. |

## 3. Accessibility

| ID | Check | Pass bar |
|---|---|---|
| A1 | TalkBack over primary flows | Every control has a meaningful label; checklist/photo actions announce state; no focus traps. |
| A2 | Font scale at 1.3 and 2.0 | No clipped/truncated text; primary actions remain reachable (D4). |
| A3 | Contrast & color independence | Outcome PASS/FAIL distinguishable without relying on color alone; meets WCAG AA contrast on key screens. |
| A4 | Touch targets & keyboard nav | Targets >= 48 dp; logical focus order. |

## 4. Performance & battery

Measure on D1 (worst case) and D2. Record numbers in the report; the pass bar is
the threshold.

| ID | Metric | Pass bar |
|---|---|---|
| P1 | Cold start (first frame → interactive) | < 2.5 s on D1. |
| P2 | Photo capture → encrypted at rest | < 1.5 s after shutter on D1; UI never freezes. |
| P3 | Sync of a 20-item inspection + 5 photos (good network) | Completes; no ANR. |
| P4 | Battery drain over a 30-min active inspection | < 5% on D1; no wake-lock abuse. |
| P5 | Backend p95 latency for upload/submit | Within `observability.md` §3 latency alert threshold. |

## 5. Security spot-check (per build)

- [ ] Release build refuses cleartext (network_security_config); base URL injected (KL-031).
- [ ] No evidence stored in plaintext; temp captures removed on failure (KL-030).
- [ ] Logs/diagnostics from the device contain no notes, photo bytes or tokens (KL-034).
- [ ] Backups exclude evidence and crypto keys (security.md §1–§2).

## 6. Go / no-go report (sign here)

Copy this block per release, fill it in, and attach it to the release tag/PR.

```
Release:            <version> (<git sha>)
Verification date:  <YYYY-MM-DD>
Tester:             <name>
Devices used:       <D1..D4 with OS/API>

Scenarios:   S1 <pass/fail>  S2 <>  S3 <>  S4 <>  S5 <>  S6 <>  S7 <>  S8 <>  S9 <>
Accessibility: A1 <>  A2 <>  A3 <>  A4 <>
Performance:   P1 <value/pass>  P2 <>  P3 <>  P4 <>  P5 <>
Security:      spot-check <> 

Blocking defects:   <list or "none">
Non-blocking (deferred to known-limitations): <list or "none">

Decision:  [ ] GO   [ ] NO-GO

Sign-off:  <name> / <date>
```

**NO-GO** is required if any of S2, S3, S5, S6, S7 (data-loss/corruption), A1,
or the security spot-check fails, or if a blocking defect is open. Everything
else is triaged into `known-limitations.md` with an owner and severity.
