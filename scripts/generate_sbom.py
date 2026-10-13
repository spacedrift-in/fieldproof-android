#!/usr/bin/env python3
"""Generate a CycloneDX (1.5) SBOM for the Android release runtime classpath.

Input is the text output of:

    ./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain

which is parsed into Maven coordinates. Each module's resolved artifact(s) in the
Gradle cache are hashed and its POM licenses are recorded.

Usage:
    scripts/generate_sbom.py --gradle-report deps.txt --output sbom.json \
        --project-name FieldProof --project-version 1.0
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import release_tools as rt  # noqa: E402

SPEC_VERSION = "1.5"


def build_bom(
    *,
    report_text: str,
    cache_root: Path,
    project_name: str,
    project_version: str,
    timestamp: str,
    application_id: str = "com.spacedrift.fieldproof",
) -> dict:
    dependencies = rt.parse_gradle_dependency_report(report_text)
    resolved = rt.resolve_dependencies(dependencies, cache_root)

    components = []
    for item in resolved:
        dep = item.dependency
        component: dict = {
            "type": "library",
            "bom-ref": dep.purl,
            "group": dep.group,
            "name": dep.name,
            "version": dep.version,
            "purl": dep.purl,
            "scope": "required",
        }

        licenses = []
        for license_id in item.licenses:
            if license_id == rt.UNKNOWN_LICENSE:
                licenses.append({"license": {"name": "UNKNOWN"}})
            else:
                licenses.append({"license": {"id": license_id}})
        if licenses:
            component["licenses"] = licenses

        hashes = []
        for artifact in item.files:
            hashes.append(
                {"alg": "SHA-256", "content": rt.sha256_file(artifact)}
            )
        if hashes:
            component["hashes"] = hashes

        components.append(component)

    components.sort(key=lambda c: c["bom-ref"])
    return {
        "bomFormat": "CycloneDX",
        "specVersion": SPEC_VERSION,
        "version": 1,
        "metadata": {
            "timestamp": timestamp,
            "component": {
                "type": "application",
                "bom-ref": f"pkg:maven/{application_id}/{project_name.lower()}@{project_version}",
                "name": project_name,
                "version": project_version,
            },
            "tools": [
                {"vendor": "FieldProof", "name": "generate_sbom.py", "version": "1"}
            ],
        },
        "components": components,
    }


def _timestamp() -> str:
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    if epoch and epoch.isdigit():
        moment = datetime.fromtimestamp(int(epoch), tz=timezone.utc)
    else:
        moment = datetime.now(timezone.utc)
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-report", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--project-name", default="FieldProof")
    parser.add_argument("--project-version", default="0.0.0")
    parser.add_argument("--cache-root", type=Path, default=rt.default_cache_root())
    parser.add_argument(
        "--application-id", default="com.spacedrift.fieldproof"
    )
    args = parser.parse_args(argv)

    report_text = args.gradle_report.read_text(encoding="utf-8")
    bom = build_bom(
        report_text=report_text,
        cache_root=args.cache_root,
        project_name=args.project_name,
        project_version=args.project_version,
        timestamp=_timestamp(),
        application_id=args.application_id,
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bom, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote SBOM with {len(bom['components'])} components to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
