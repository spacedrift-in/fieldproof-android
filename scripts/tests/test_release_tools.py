"""Tests for the release SBOM / license-scan tooling (no network required)."""
from __future__ import annotations

from pathlib import Path

import generate_sbom
import license_scan
import release_tools as rt

REPORT = """\
------------------------------------------------------------
Project ':app'
------------------------------------------------------------

releaseRuntimeClasspath - Runtime classpath of '/release'.
+--- androidx.core:core-ktx:1.10.1 -> 1.16.0
|    +--- androidx.annotation:annotation:1.2.0 -> 1.6.0
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.2.10}
+--- com.example:no-version
+--- com.guava:listenablefuture:1.0
\\--- com.example:with-rest:1.2.3 (c)
"""

POM_APACHE = """\
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <licenses>
    <license>
      <name>The Apache Software License, Version 2.0</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
</project>
"""

POM_BSD_GENERIC = """\
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <licenses>
    <license>
      <name>BSD License</name>
      <url>https://chromium.googlesource.com/libyuv</url>
    </license>
  </licenses>
</project>
"""

POM_GPL = """\
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <licenses>
    <license><name>GNU General Public License, version 3</name></license>
  </licenses>
</project>
"""

POM_NO_LICENSE = """\
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <artifactId>listenablefuture</artifactId>
</project>
"""


def _write_module(cache: Path, group: str, name: str, version: str, pom: str) -> None:
    module_dir = cache / group / name / version / "deadbeef"
    module_dir.mkdir(parents=True)
    (module_dir / f"{name}-{version}.pom").write_text(pom, encoding="utf-8")
    (module_dir / f"{name}-{version}.aar").write_bytes(b"aar-bytes")
    (module_dir / f"{name}-{version}-sources.jar").write_bytes(b"sources")


# ---------------------------------------------------------------------------
# Dependency report parsing
# ---------------------------------------------------------------------------

def test_parse_report_resolves_versions_and_dedups():
    deps = rt.parse_gradle_dependency_report(REPORT)
    by_name = {(d.group, d.name): d.version for d in deps}
    assert by_name[("androidx.core", "core-ktx")] == "1.16.0"
    assert by_name[("androidx.annotation", "annotation")] == "1.6.0"
    assert by_name[("com.example", "with-rest")] == "1.2.3"
    # Constraint-only line with no concrete version is ignored.
    assert ("org.jetbrains.kotlin", "kotlin-stdlib") not in by_name
    assert ("com.example", "no-version") not in by_name


def test_purl_format():
    dep = rt.Dependency("androidx.core", "core-ktx", "1.16.0")
    assert dep.purl == "pkg:maven/androidx.core/core-ktx@1.16.0"


# ---------------------------------------------------------------------------
# License normalization
# ---------------------------------------------------------------------------

def test_normalize_license_aliases_and_url_hints():
    assert rt.normalize_license("The Apache Software License, Version 2.0") == "Apache-2.0"
    assert rt.normalize_license("MIT License") == "MIT"
    assert rt.normalize_license("BSD License", "http://x/bsd-3-clause") == "BSD-3-Clause"
    assert rt.normalize_license("BSD License", "http://x/2-clause") == "BSD-2-Clause"
    assert rt.normalize_license("Apache-2.0") == "Apache-2.0"
    assert rt.normalize_license("Totally Unknown") == rt.UNKNOWN_LICENSE
    assert rt.normalize_license(None) == rt.UNKNOWN_LICENSE


# ---------------------------------------------------------------------------
# Cache resolution
# ---------------------------------------------------------------------------

def test_resolve_dependencies_reads_licenses_and_artifacts(tmp_path):
    cache = tmp_path / "cache"
    _write_module(cache, "androidx.core", "core-ktx", "1.16.0", POM_APACHE)
    deps = [rt.Dependency("androidx.core", "core-ktx", "1.16.0")]
    resolved = rt.resolve_dependencies(deps, cache)
    assert resolved[0].licenses == ["Apache-2.0"]
    names = sorted(p.name for p in resolved[0].files)
    # sources jar is filtered out.
    assert names == ["core-ktx-1.16.0.aar"]


def test_missing_pom_is_unknown(tmp_path):
    cache = tmp_path / "cache"
    deps = [rt.Dependency("com.guava", "listenablefuture", "1.0")]
    resolved = rt.resolve_dependencies(deps, cache)
    assert resolved[0].licenses == [rt.UNKNOWN_LICENSE]


# ---------------------------------------------------------------------------
# SBOM generation
# ---------------------------------------------------------------------------

def test_build_bom_shape_and_hashes(tmp_path):
    cache = tmp_path / "cache"
    _write_module(cache, "androidx.core", "core-ktx", "1.16.0", POM_APACHE)
    _write_module(cache, "com.guava", "listenablefuture", "1.0", POM_NO_LICENSE)
    bom = generate_sbom.build_bom(
        report_text=REPORT,
        cache_root=cache,
        project_name="FieldProof",
        project_version="2.0",
        timestamp="2026-10-13T00:00:00Z",
    )
    assert bom["bomFormat"] == "CycloneDX"
    assert bom["specVersion"] == "1.5"
    assert bom["metadata"]["component"]["version"] == "2.0"
    refs = {c["bom-ref"] for c in bom["components"]}
    assert "pkg:maven/androidx.core/core-ktx@1.16.0" in refs
    assert "pkg:maven/com.guava/listenablefuture@1.0" in refs
    assert refs == {c["bom-ref"] for c in bom["components"]}  # unique
    # Components are sorted for deterministic output.
    assert [c["bom-ref"] for c in bom["components"]] == sorted(refs)

    core = next(c for c in bom["components"] if c["name"] == "core-ktx")
    assert core["licenses"] == [{"license": {"id": "Apache-2.0"}}]
    assert core["hashes"][0]["alg"] == "SHA-256"
    guava = next(c for c in bom["components"] if c["name"] == "listenablefuture")
    assert guava["licenses"] == [{"license": {"name": "UNKNOWN"}}]


# ---------------------------------------------------------------------------
# License policy evaluation
# ---------------------------------------------------------------------------

def _resolved(group, name, version, licenses):
    return rt.ResolvedDependency(
        dependency=rt.Dependency(group, name, version), licenses=licenses
    )


def test_evaluate_flags_denied_licenses():
    policy = {"allowed": ["Apache-2.0"], "denied": ["GPL-3.0"], "allowUnknown": True}
    findings, summary = license_scan.evaluate(
        [_resolved("g", "gpl", "1.0", ["GPL-3.0"])], policy
    )
    assert findings[0]["status"] == "denied"
    assert summary["denied"] == 1


def test_evaluate_unknown_blocks_by_default():
    policy = {"allowed": ["Apache-2.0"], "denied": [], "allowUnknown": False}
    findings, summary = license_scan.evaluate(
        [_resolved("g", "u", "1.0", [rt.UNKNOWN_LICENSE])], policy
    )
    assert findings[0]["status"] == "unknown"
    assert summary["unknown"] == 1


def test_evaluate_unknown_allowed_when_configured():
    policy = {"allowed": ["Apache-2.0"], "denied": [], "allowUnknown": True}
    findings, _ = license_scan.evaluate(
        [_resolved("g", "u", "1.0", [rt.UNKNOWN_LICENSE])], policy
    )
    assert findings[0]["status"] == "review"


def test_evaluate_applies_overrides_for_missing_metadata():
    policy = {
        "allowed": ["Apache-2.0"],
        "denied": [],
        "allowUnknown": False,
        "overrides": {"com.guava:listenablefuture": "Apache-2.0", "_comment": "x"},
    }
    findings, summary = license_scan.evaluate(
        [_resolved("com.guava", "listenablefuture", "1.0", [rt.UNKNOWN_LICENSE])],
        policy,
    )
    assert findings[0]["status"] == "allowed"
    assert findings[0]["overridden"] is True
    assert summary["allowed"] == 1


def test_evaluate_multiple_licenses_uses_worst_status():
    policy = {"allowed": ["Apache-2.0", "BSD-3-Clause"], "denied": [], "allowUnknown": False}
    findings, _ = license_scan.evaluate(
        [_resolved("g", "dual", "1.0", ["Apache-2.0", "BSD-3-Clause"])], policy
    )
    assert findings[0]["status"] == "allowed"

    findings, _ = license_scan.evaluate(
        [_resolved("g", "bad", "1.0", ["Apache-2.0", "GPL-3.0"])],
        {"allowed": ["Apache-2.0"], "denied": ["GPL-3.0"], "allowUnknown": False},
    )
    assert findings[0]["status"] == "denied"
