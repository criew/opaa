"""Self-check for pnpm_dependency_snapshot.py.

Pins the scope rule (reachable from dependencies is runtime, the rest development), the merge of
peer-dependency variants into one purl, aliases, skipped link: sources, the package manager
document and the failure on an importer entry without snapshot.
"""

import importlib.util
import textwrap
from pathlib import Path

import pytest
import yaml

SCRIPT = Path(__file__).resolve().parent / "pnpm_dependency_snapshot.py"
SPEC = importlib.util.spec_from_file_location("pnpm_dependency_snapshot", SCRIPT)
snapshot = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(snapshot)

LOCKFILE = textwrap.dedent(
    """\
    ---
    lockfileVersion: '9.0'

    importers:

      .:
        configDependencies: {}
        packageManagerDependencies:
          pnpm:
            specifier: 12.14.0
            version: 12.14.0

    packages:

      pnpm@12.14.0:
        resolution: {integrity: sha512-x}

    snapshots:

      pnpm@12.14.0: {}

    ---
    lockfileVersion: '9.0'

    importers:

      .:
        dependencies:
          '@scope/ui':
            specifier: ^1.0.0
            version: 1.0.0(react@19.0.0)
          react:
            specifier: ^19.0.0
            version: 19.0.0
          strip:
            specifier: npm:strip-ansi@7.1.0
            version: strip-ansi@7.1.0
          local:
            specifier: link:../local
            version: link:../local
        devDependencies:
          vite:
            specifier: ^8.0.0
            version: 8.0.0
          '@scope/ui-test':
            specifier: ^1.0.0
            version: 1.0.0(@scope/ui@1.0.0(react@19.0.0))

    packages: {}

    snapshots:

      '@scope/ui@1.0.0(react@19.0.0)':
        dependencies:
          react: 19.0.0
          js-tokens: 4.0.0

      '@scope/ui@1.0.0(react@18.0.0)':
        dependencies:
          react: 18.0.0

      '@scope/ui-test@1.0.0(@scope/ui@1.0.0(react@19.0.0))':
        dependencies:
          '@scope/ui': 1.0.0(react@18.0.0)

      react@19.0.0: {}

      react@18.0.0: {}

      js-tokens@4.0.0: {}

      strip-ansi@7.1.0:
        optionalDependencies:
          ansi-regex: 6.0.0

      ansi-regex@6.0.0: {}

      vite@8.0.0:
        dependencies:
          rollup: 4.0.0

      rollup@4.0.0: {}
    """
)


def build(tmp_path, text=LOCKFILE):
    lockfile = tmp_path / "pnpm-lock.yaml"
    lockfile.write_text(text, encoding="utf-8")
    result = snapshot.build([str(lockfile)], "abc", "refs/heads/main", "pnpm", "1", "2026-01-01T00:00:00Z")
    return result["manifests"][str(lockfile)]["resolved"]


def test_scope_follows_reachability_from_dependencies(tmp_path):
    resolved = build(tmp_path)
    scopes = {url: entry["scope"] for url, entry in resolved.items()}
    assert scopes == {
        "pkg:npm/pnpm@12.14.0": "development",
        "pkg:npm/%40scope/ui@1.0.0": "runtime",
        "pkg:npm/%40scope/ui-test@1.0.0": "development",
        "pkg:npm/react@19.0.0": "runtime",
        "pkg:npm/react@18.0.0": "development",
        "pkg:npm/js-tokens@4.0.0": "runtime",
        "pkg:npm/strip-ansi@7.1.0": "runtime",
        "pkg:npm/ansi-regex@6.0.0": "runtime",
        "pkg:npm/vite@8.0.0": "development",
        "pkg:npm/rollup@4.0.0": "development",
    }


def test_relationship_and_merged_peer_variants(tmp_path):
    resolved = build(tmp_path)
    ui = resolved["pkg:npm/%40scope/ui@1.0.0"]
    assert ui["relationship"] == "direct"
    assert ui["dependencies"] == [
        "pkg:npm/js-tokens@4.0.0",
        "pkg:npm/react@18.0.0",
        "pkg:npm/react@19.0.0",
    ]
    assert resolved["pkg:npm/rollup@4.0.0"]["relationship"] == "indirect"
    assert resolved["pkg:npm/strip-ansi@7.1.0"]["relationship"] == "direct"
    assert not any("local" in url for url in resolved)


def test_snapshot_envelope(tmp_path):
    lockfile = tmp_path / "pnpm-lock.yaml"
    lockfile.write_text(LOCKFILE, encoding="utf-8")
    result = snapshot.build([str(lockfile)], "abc", "refs/pull/1/merge", "pnpm", "7", "t")
    assert result["sha"] == "abc"
    assert result["ref"] == "refs/pull/1/merge"
    assert result["job"] == {"correlator": "pnpm", "id": "7"}
    assert result["detector"]["name"] == "opaa-pnpm-lockfile"
    assert result["manifests"][str(lockfile)]["file"] == {"source_location": str(lockfile)}


def test_importer_entry_without_snapshot_fails(tmp_path):
    documents = list(yaml.safe_load_all(LOCKFILE))
    del documents[1]["snapshots"]["react@19.0.0"]
    with pytest.raises(ValueError, match="react@19.0.0"):
        build(tmp_path, yaml.safe_dump_all(documents))


def test_repository_lockfiles_parse():
    root = SCRIPT.parents[2]
    lockfiles = sorted(str(p.relative_to(root)) for p in root.glob("**/pnpm-lock.yaml") if "node_modules" not in p.parts)
    assert "frontend/pnpm-lock.yaml" in lockfiles
    result = snapshot.build([str(root / p) for p in lockfiles], "abc", "r", "c", "1", "t")
    frontend = result["manifests"][str(root / "frontend/pnpm-lock.yaml")]["resolved"]
    assert frontend["pkg:npm/react@" + _version(frontend, "react")]["scope"] == "runtime"


def _version(resolved, name):
    prefix = f"pkg:npm/{name}@"
    versions = [url[len(prefix) :] for url, e in resolved.items() if url.startswith(prefix) and e["relationship"] == "direct"]
    assert len(versions) == 1
    return versions[0]


def test_non_registry_sources_are_left_out_but_pass_scope_on(tmp_path):
    documents = list(yaml.safe_load_all(LOCKFILE))
    project = documents[1]
    project["importers"]["."]["dependencies"]["vendored"] = {
        "specifier": "file:../vendored",
        "version": "file:../vendored",
    }
    project["snapshots"]["vendored@file:../vendored"] = {
        "dependencies": {"tar": "https://example.com/tar-1.0.0.tgz"}
    }
    project["snapshots"]["tar@https://example.com/tar-1.0.0.tgz"] = {
        "dependencies": {"leaf": "1.0.0"}
    }
    project["snapshots"]["leaf@1.0.0"] = {}
    resolved = build(tmp_path, yaml.safe_dump_all(documents))
    assert not any("vendored" in url or "tar" in url for url in resolved)
    assert resolved["pkg:npm/leaf@1.0.0"]["scope"] == "runtime"
