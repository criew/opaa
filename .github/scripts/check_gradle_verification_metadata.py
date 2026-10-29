#!/usr/bin/env python3
"""Compares two versions of backend/gradle/verification-metadata.xml.

A dependency update adds entries for new versions and may drop entries no longer resolved. An entry
that exists on both sides keeps its checksums: a changed or added checksum for the same file means
the bytes behind known coordinates changed, which is what verification exists to catch. Gradle's
--write-verification-metadata (Renovate runs it for every Gradle update) records such a change
silently as <also-trust>, so this check reports it and, with --enforce, fails.

Usage: check_gradle_verification_metadata.py BASE HEAD [--enforce]
"""

import argparse
import re
import sys
from pathlib import Path

COMPONENT = re.compile(
    r'<component group="([^"]+)" name="([^"]+)" version="([^"]+)">(.*?)</component>', re.S
)
ARTIFACT = re.compile(r'<artifact name="([^"]+)">(.*?)</artifact>', re.S)
CHECKSUM = re.compile(r'<(?:sha256|sha512|also-trust) value="([0-9a-f]+)"')


def checksums(path: Path) -> dict[tuple[str, str, str, str], frozenset[str]]:
    text = path.read_text(encoding="utf-8")
    entries = {}
    for group, name, version, body in COMPONENT.findall(text):
        for file, artifact in ARTIFACT.findall(body):
            entries[(group, name, version, file)] = frozenset(CHECKSUM.findall(artifact))
    return entries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("base", type=Path)
    parser.add_argument("head", type=Path)
    parser.add_argument("--enforce", action="store_true")
    args = parser.parse_args()

    base = checksums(args.base)
    head = checksums(args.head)
    changed = sorted(key for key in base.keys() & head.keys() if base[key] != head[key])
    added = len(head.keys() - base.keys())
    removed = len(base.keys() - head.keys())
    print(f"{added} artifact file(s) added, {removed} removed, {len(changed)} with changed checksums.")

    level = "error" if args.enforce else "warning"
    for group, name, version, file in changed:
        key = (group, name, version, file)
        print(
            f"::{level}::{group}:{name}:{version} {file}: checksums changed from "
            f"{sorted(base[key])} to {sorted(head[key])}. Verify against the repository before "
            "accepting (docs/renovate.md, Gradle-Prüfsummen)."
        )
    return 1 if changed and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
