#!/usr/bin/env python3
"""Dependency license scan for the Android release runtime classpath.

Reads the same Gradle `dependencies` report used by ``generate_sbom.py``, looks
up each module's POM license(s) in the Gradle cache and evaluates them against a
policy (``scripts/license-policy.json``).

Exit code 0 = every dependency is allowed (or only "review" warnings).
Exit code 1 = at least one dependency is denied or (by default) unknown.

Usage:
    scripts/license_scan.py --gradle-report deps.txt \
        --policy scripts/license-policy.json --output license-report.json
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import release_tools as rt  # noqa: E402


def evaluate(
    resolved: list[rt.ResolvedDependency], policy: dict
) -> tuple[list[dict], dict]:
    allowed = {s.lower() for s in policy.get("allowed", [])}
    denied = {s.lower() for s in policy.get("denied", [])}
    review = {s.lower() for s in policy.get("review", [])}
    allow_unknown = bool(policy.get("allowUnknown", False))
    overrides = {
        key: value
        for key, value in policy.get("overrides", {}).items()
        if isinstance(value, str)
    }

    findings: list[dict] = []
    summary = {"allowed": 0, "review": 0, "denied": 0, "unknown": 0}

    for item in resolved:
        licenses = list(item.licenses)
        override = overrides.get(f"{item.dependency.group}:{item.dependency.name}")
        if override and licenses == [rt.UNKNOWN_LICENSE]:
            licenses = [override]

        statuses = []
        for license_id in licenses:
            lowered = license_id.lower()
            if lowered in denied:
                statuses.append("denied")
            elif lowered in allowed:
                statuses.append("allowed")
            elif lowered in review:
                statuses.append("review")
            elif license_id == rt.UNKNOWN_LICENSE:
                statuses.append("unknown")
            else:
                statuses.append("unknown")

        # The worst status wins for the dependency as a whole.
        if "denied" in statuses:
            overall = "denied"
        elif "unknown" in statuses and not allow_unknown:
            overall = "unknown"
        elif "review" in statuses:
            overall = "review"
        elif "unknown" in statuses:
            overall = "review"
        else:
            overall = "allowed"

        summary[overall] = summary.get(overall, 0) + 1
        findings.append(
            {
                "group": item.dependency.group,
                "name": item.dependency.name,
                "version": item.dependency.version,
                "purl": item.dependency.purl,
                "licenses": licenses,
                "overridden": bool(override and item.licenses == [rt.UNKNOWN_LICENSE]),
                "status": overall,
            }
        )

    findings.sort(key=lambda f: f["purl"])
    return findings, summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-report", required=True, type=Path)
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--cache-root", type=Path, default=rt.default_cache_root())
    parser.add_argument(
        "--fail-on-review",
        action="store_true",
        help="Treat 'review' licenses as blocking too.",
    )
    args = parser.parse_args(argv)

    report_text = args.gradle_report.read_text(encoding="utf-8")
    policy = json.loads(args.policy.read_text(encoding="utf-8"))

    dependencies = rt.parse_gradle_dependency_report(report_text)
    resolved = rt.resolve_dependencies(dependencies, args.cache_root)
    findings, summary = evaluate(resolved, policy)

    violations = [f for f in findings if f["status"] == "denied"]
    unknowns = [f for f in findings if f["status"] == "unknown"]
    reviews = [f for f in findings if f["status"] == "review"]

    report = {
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "summary": summary,
        "violations": violations,
        "unknown": unknowns,
        "review": reviews,
        "dependencies": findings,
        "policy": policy,
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(
        f"License scan: {summary['allowed']} allowed, "
        f"{summary['review']} review, {summary['unknown']} unknown, "
        f"{summary['denied']} denied ({len(findings)} total)"
    )
    for finding in violations:
        print(f"  DENIED  {finding['purl']} -> {', '.join(finding['licenses'])}")
    for finding in unknowns:
        print(f"  UNKNOWN {finding['purl']} -> {', '.join(finding['licenses'])}")
    for finding in reviews:
        print(f"  REVIEW  {finding['purl']} -> {', '.join(finding['licenses'])}")

    failed = bool(violations) or bool(unknowns)
    if args.fail_on_review and reviews:
        failed = True

    if failed:
        print("License policy check FAILED.", file=sys.stderr)
        return 1
    print("License policy check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
