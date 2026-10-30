#!/usr/bin/env python3
"""Builds a Dependency Submission API snapshot from pnpm lockfiles.

GitHub's dependency graph reads only the package.json files of a pnpm project, so transitive npm
packages stay invisible to Dependabot and to the dependency review of a pull request. This script
reads every given pnpm-lock.yaml (lockfile version 9, including the leading document pnpm 10+
writes for the package manager itself) and emits one manifest per lockfile with every resolved
package as a purl.

Scope follows package.json: a package reachable from an importer's dependencies or
optionalDependencies is runtime, everything else (devDependencies, the package manager document)
is development. Linked workspace packages and file: or git sources are skipped, since they have
no registry identity an advisory could name.

Usage: pnpm_dependency_snapshot.py --sha SHA --ref REF --correlator NAME --job-id ID
       --output snapshot.json path/to/pnpm-lock.yaml...
"""

import argparse
import datetime
import json
import sys
import urllib.parse
from pathlib import Path

import yaml

DETECTOR = {
    "name": "opaa-pnpm-lockfile",
    "version": "1.0.0",
    "url": "https://github.com/criew/opaa/blob/main/.github/scripts/pnpm_dependency_snapshot.py",
}
RUNTIME_GROUPS = ("dependencies", "optionalDependencies")
DEVELOPMENT_GROUPS = ("devDependencies", "packageManagerDependencies")


def resolve(name, version):
    """Returns the snapshot key of a dependency entry, or None for non-registry sources."""
    if version.startswith(("link:", "file:", "git+", "http:", "https:")):
        return None
    if "@" in version.split("(", 1)[0][1:]:
        # alias ("strip: npm:strip-ansi@7.1.0" in package.json): the lockfile names the real package
        return version
    return f"{name}@{version}"


def split_key(key):
    """Splits "name@version(peer...)" into (name, version without the peer suffix)."""
    base = key.split("(", 1)[0]
    at = base.rindex("@")
    if at == 0:
        raise ValueError(f"no version in snapshot key {key!r}")
    return base[:at], base[at + 1 :]


def purl(key):
    name, version = split_key(key)
    return "pkg:npm/" + urllib.parse.quote(name, safe="/") + "@" + urllib.parse.quote(version)


def children(snapshot):
    for group in RUNTIME_GROUPS:
        for name, version in (snapshot.get(group) or {}).items():
            key = resolve(name, str(version))
            if key is not None:
                yield key


def importer_roots(importer, groups):
    for group in groups:
        for name, entry in (importer.get(group) or {}).items():
            version = entry["version"] if isinstance(entry, dict) else entry
            key = resolve(name, str(version))
            if key is not None:
                yield key


def manifest(path, documents):
    snapshots = {}
    direct = {}
    runtime_roots = []
    for document in documents:
        if not document:
            continue
        snapshots.update(document.get("snapshots") or {})
        for importer in (document.get("importers") or {}).values():
            for key in importer_roots(importer, RUNTIME_GROUPS):
                direct[key] = True
                runtime_roots.append(key)
            for key in importer_roots(importer, DEVELOPMENT_GROUPS):
                direct.setdefault(key, True)

    missing = sorted(key for key in direct if key not in snapshots)
    if missing:
        raise ValueError(f"{path}: importer entries without snapshot: {', '.join(missing)}")

    runtime = set()
    pending = list(runtime_roots)
    while pending:
        key = pending.pop()
        if key in runtime:
            continue
        runtime.add(key)
        pending.extend(children(snapshots.get(key) or {}))

    resolved = {}
    for key, snapshot in snapshots.items():
        package_url = purl(key)
        dependencies = sorted({purl(child) for child in children(snapshot or {})})
        entry = resolved.get(package_url)
        if entry is None:
            entry = resolved[package_url] = {
                "package_url": package_url,
                "relationship": "indirect",
                "scope": "development",
                "dependencies": [],
            }
        # One package version may appear under several peer-dependency variants; the purl merges
        # them, and any runtime or direct variant wins.
        if key in direct:
            entry["relationship"] = "direct"
        if key in runtime:
            entry["scope"] = "runtime"
        entry["dependencies"] = sorted(set(entry["dependencies"]) | set(dependencies))

    return {"name": path, "file": {"source_location": path}, "resolved": resolved}


def build(paths, sha, ref, correlator, job_id, scanned=None):
    manifests = {}
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            documents = list(yaml.safe_load_all(handle))
        manifests[path] = manifest(path, documents)
    return {
        "version": 0,
        "sha": sha,
        "ref": ref,
        "job": {"correlator": correlator, "id": job_id},
        "detector": DETECTOR,
        "scanned": scanned
        or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "manifests": manifests,
    }


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--sha", required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--correlator", required=True)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("lockfiles", nargs="+")
    args = parser.parse_args(argv)
    snapshot = build(args.lockfiles, args.sha, args.ref, args.correlator, args.job_id)
    Path(args.output).write_text(json.dumps(snapshot, indent=1) + "\n", encoding="utf-8")
    for path, entry in snapshot["manifests"].items():
        runtime = sum(1 for p in entry["resolved"].values() if p["scope"] == "runtime")
        print(f"{path}: {len(entry['resolved'])} packages, {runtime} runtime")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
