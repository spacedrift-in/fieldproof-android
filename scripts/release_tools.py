"""Shared helpers for the release SBOM / license-scan tooling.

These functions are deliberately dependency-free (standard library only) so the
release gate can run on a stock GitHub runner. They parse the output of
``./gradlew :app:dependencies --configuration releaseRuntimeClasspath`` and
inspect the resolved modules in the Gradle cache to produce a CycloneDX SBOM and
a license report.
"""
from __future__ import annotations

import hashlib
import os
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

# Tree prefixes emitted by Gradle's `dependencies` task.
_TREE_PREFIX = r"^[| +\\-]+"
_COORD = re.compile(rf"{_TREE_PREFIX}(?P<group>[\w.\-]+):(?P<name>[\w.\-]+):(?P<version>[\w.\-+]+)(?P<rest>.*)$")
_RESOLVED = re.compile(r"->\s+(?P<version>[\w.\-+]+)")
_ARTIFACT_SUFFIXES = (".jar", ".aar")
_SKIP_SUFFIXES = ("-sources.jar", "-javadoc.jar", "-samples.jar")


@dataclass(frozen=True)
class Dependency:
    group: str
    name: str
    version: str

    @property
    def purl(self) -> str:
        return f"pkg:maven/{self.group}/{self.name}@{self.version}"

    @property
    def key(self) -> tuple[str, str]:
        return (self.group, self.name)


@dataclass
class ResolvedDependency:
    dependency: Dependency
    files: list[Path] = field(default_factory=list)
    licenses: list[str] = field(default_factory=list)


def parse_gradle_dependency_report(text: str) -> list[Dependency]:
    """Extract ``group:name:version`` coordinates from a Gradle dependency tree.

    Version conflicts are resolved to the winning version (the part after
    ``->``). Duplicates are collapsed, keeping the latest-seen version.
    """
    resolved: dict[tuple[str, str], Dependency] = {}
    for raw_line in text.splitlines():
        match = _COORD.match(raw_line.rstrip())
        if not match:
            continue
        group, name, version = match.group("group"), match.group("name"), match.group("version")
        winner = _RESOLVED.search(match.group("rest"))
        if winner:
            version = winner.group("version")
        # Skip unresolved/constraint markers that leaked into the version slot.
        if any(ch in version for ch in "{[]}()"):
            continue
        resolved[(group, name)] = Dependency(group, name, version)
    return sorted(resolved.values(), key=lambda d: (d.group, d.name))


def find_module_dir(cache_root: Path, group: str, name: str, version: str) -> Path | None:
    candidate = cache_root / group / name / version
    return candidate if candidate.is_dir() else None


def find_artifacts(cache_root: Path, dep: Dependency) -> list[Path]:
    """Return the primary (non-sources/-javadoc) jar/aar files for a module."""
    module_dir = find_module_dir(cache_root, dep.group, dep.name, dep.version)
    if module_dir is None:
        return []
    files: list[Path] = []
    for path in module_dir.rglob("*"):
        if not path.is_file():
            continue
        lower = path.name.lower()
        if not lower.endswith(_ARTIFACT_SUFFIXES):
            continue
        if any(lower.endswith(skip) for skip in _SKIP_SUFFIXES):
            continue
        files.append(path)
    return sorted(files)


def find_pom(cache_root: Path, dep: Dependency) -> Path | None:
    module_dir = find_module_dir(cache_root, dep.group, dep.name, dep.version)
    if module_dir is None:
        return None
    candidates = sorted(module_dir.rglob(f"{dep.name}-{dep.version}.pom"))
    return candidates[0] if candidates else None


def _strip_namespace(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def read_pom_licenses(pom_path: Path) -> list[tuple[str, str | None]]:
    """Return ``(name, url)`` pairs from a POM's ``<licenses>`` block.

    POMs frequently omit the ``<licenses>`` block entirely (e.g.
    ``com.google.guava:listenablefuture``); callers should treat an empty list as
    "metadata missing" rather than "no license".
    """
    try:
        tree = ET.parse(pom_path)
    except (ET.ParseError, OSError):
        return []
    entries: list[tuple[str, str | None]] = []
    for element in tree.iter():
        if _strip_namespace(element.tag) != "license":
            continue
        name: str | None = None
        url: str | None = None
        for child in element:
            local = _strip_namespace(child.tag)
            if local == "name" and child.text:
                name = child.text.strip()
            elif local == "url" and child.text:
                url = child.text.strip()
        if name:
            entries.append((name, url))
    return entries


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


_LICENSE_ALIASES = {
    "apache license, version 2.0": "Apache-2.0",
    "apache license 2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "apache software license, version 2.0": "Apache-2.0",
    "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "mit license": "MIT",
    "the mit license": "MIT",
    "mit": "MIT",
    "isc license": "ISC",
    "the isc license": "ISC",
    "the bsd 2-clause license": "BSD-2-Clause",
    "bsd 2-clause": "BSD-2-Clause",
    "bsd-2-clause": "BSD-2-Clause",
    "the bsd 3-clause license": "BSD-3-Clause",
    "bsd 3-clause": "BSD-3-Clause",
    "bsd-3-clause": "BSD-3-Clause",
    # A bare "BSD License" conventionally means the 3-clause variant; the URL
    # hint below disambiguates the 2-clause case where a POM is explicit.
    "bsd license": "BSD-3-Clause",
    "bsd": "BSD-3-Clause",
    "bsd licence": "BSD-3-Clause",
    "eclipse public license - v 1.0": "EPL-1.0",
    "eclipse public license v1.0": "EPL-1.0",
    "eclipse public license - v 2.0": "EPL-2.0",
    "eclipse public license v2.0": "EPL-2.0",
    "mozilla public license version 2.0": "MPL-2.0",
    "cc0 1.0 universal": "CC0-1.0",
    "the unlicense": "Unlicense",
    "unlicense": "Unlicense",
    "zlib license": "Zlib",
    "gnu general public license, version 2": "GPL-2.0",
    "gnu general public license, version 3": "GPL-3.0",
    "gnu lesser general public license, version 2.1": "LGPL-2.1",
    "gnu lesser general public license, version 3.0": "LGPL-3.0",
    "gnu affero general public license": "AGPL-3.0",
}

UNKNOWN_LICENSE = "UNKNOWN"


def normalize_license(name: str | None, url: str | None = None) -> str:
    """Best-effort mapping of a free-text license name to an SPDX identifier.

    ``url`` is used as a tie-breaker for ambiguous names such as a bare
    "BSD License".
    """
    if not name:
        return UNKNOWN_LICENSE
    # A URL hint is authoritative for otherwise-generic names such as a bare
    # "BSD License", so consult it before the alias table.
    if url:
        lowered_url = url.lower()
        if "bsd-2" in lowered_url or "2-clause" in lowered_url:
            return "BSD-2-Clause"
        if "bsd-3" in lowered_url or "3-clause" in lowered_url or "libyuv" in lowered_url:
            return "BSD-3-Clause"
        if "apache.org/licenses/license-2.0" in lowered_url:
            return "Apache-2.0"
        if "mit-license" in lowered_url or "opensource.org/licenses/mit" in lowered_url:
            return "MIT"
    cleaned = re.sub(r"\s+", " ", name).strip().rstrip(".").lower()
    if cleaned in _LICENSE_ALIASES:
        return _LICENSE_ALIASES[cleaned]
    # Already looks like an SPDX id (e.g. "Apache-2.0").
    if re.fullmatch(r"[A-Za-z0-9.+\-]+", name.strip()) and "-" in name:
        return name.strip()
    return UNKNOWN_LICENSE


def resolve_dependencies(
    dependencies: list[Dependency], cache_root: Path
) -> list[ResolvedDependency]:
    resolved: list[ResolvedDependency] = []
    for dep in dependencies:
        pom = find_pom(cache_root, dep)
        licenses = (
            [normalize_license(name, url) for name, url in read_pom_licenses(pom)]
            if pom
            else []
        )
        if not licenses:
            licenses = [UNKNOWN_LICENSE]
        resolved.append(
            ResolvedDependency(
                dependency=dep,
                files=find_artifacts(cache_root, dep),
                licenses=licenses,
            )
        )
    return resolved


def default_cache_root() -> Path:
    cached = os.environ.get("GRADLE_USER_HOME")
    if cached:
        return Path(cached) / "caches" / "modules-2" / "files-2.1"
    return Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
