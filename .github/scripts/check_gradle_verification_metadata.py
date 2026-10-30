#!/usr/bin/env python3
"""Compares two versions of backend/gradle/verification-metadata.xml.

A dependency update adds entries for new versions and may drop entries no longer resolved. An entry
that exists on both sides keeps its checksums: a changed or added checksum for the same file means
the bytes behind known coordinates changed, which is what verification exists to catch. Gradle's
--write-verification-metadata (Renovate runs it for every Gradle update) records such a change
silently as <also-trust>, so this check reports it and, with --enforce, fails.

Everything outside <components> (verify-metadata, trusted artifacts, keys) must equal the base:
reported, and with --enforce failing. A component or artifact listed twice is always an error,
because Gradle merges duplicates and a second block could smuggle in an extra checksum.

With --details, the report also lists every removed artifact file and shows <configuration> as a
diff, so a maintainer can judge a regenerated file from the job summary alone.

Usage: check_gradle_verification_metadata.py BASE HEAD [--enforce] [--details]
"""

import argparse
import copy
import difflib
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

Key = tuple[str, str, str, str]


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


# Rule lists Gradle reads as sets; --write-verification-metadata sorts them on every run.
UNORDERED = {"trusted-artifacts", "trusted-keys", "ignored-keys"}


def canonical(element: ET.Element) -> str:
    """Order-independent for attributes and rule lists, otherwise order-preserving for children,
    whitespace-insensitive."""
    attributes = " ".join(f'{k}="{v}"' for k, v in sorted(element.attrib.items()))
    parts = [canonical(child) for child in element]
    if local(element.tag) in UNORDERED:
        parts.sort()
    children = "".join(parts)
    text = (element.text or "").strip()
    return f"<{local(element.tag)} {attributes}>{text}{children}</{local(element.tag)}>"


def one_line(text: str) -> str:
    """Escapes line breaks so an XML value cannot start its own workflow command line."""
    return text.replace("\r", "\\r").replace("\n", "\\n")


def parse(path: Path) -> tuple[str, dict[Key, frozenset[str]], list[str]]:
    root = ET.parse(path).getroot()
    configuration = "".join(canonical(child) for child in root if local(child.tag) != "components")
    entries: dict[Key, frozenset[str]] = {}
    duplicates: list[str] = []
    seen_components: set[tuple[str, str, str]] = set()
    for components in (child for child in root if local(child.tag) == "components"):
        for component in components:
            coordinates = tuple(component.get(a, "") for a in ("group", "name", "version"))
            if coordinates in seen_components:
                duplicates.append(f"component {':'.join(coordinates)} listed twice")
            seen_components.add(coordinates)
            for artifact in component:
                key = (*coordinates, artifact.get("name", ""))
                if key in entries:
                    duplicates.append(f"artifact {':'.join(coordinates)} {key[3]} listed twice")
                # An <also-trust> counts like its algorithm's primary value. Union like Gradle, so a
                # duplicate block also shows up as changed checksums.
                entries[key] = entries.get(key, frozenset()) | frozenset(
                    f"{local(checksum.tag)}:{element.get('value')}"
                    for checksum in artifact
                    for element in checksum.iter()
                    if element.get("value")
                )
    return configuration, entries, duplicates


def configuration_lines(path: Path) -> list[str]:
    """Everything outside <components>, indented one element per line for a readable diff."""
    root = copy.deepcopy(ET.parse(path).getroot())
    for components in [child for child in root if local(child.tag) == "components"]:
        root.remove(components)
    for element in root.iter():
        element.tag = local(element.tag)
        element.attrib = dict(sorted(element.attrib.items()))
        element.text = (element.text or "").strip() or None
        element.tail = None
    ET.indent(root)
    return ET.tostring(root, encoding="unicode").splitlines()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("base", type=Path)
    parser.add_argument("head", type=Path)
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--details", action="store_true")
    args = parser.parse_args()

    base_configuration, base, _ = parse(args.base)
    head_configuration, head, duplicates = parse(args.head)
    changed = sorted(key for key in base.keys() & head.keys() if base[key] != head[key])
    added = len(head.keys() - base.keys())
    removed = sorted(base.keys() - head.keys())
    print(
        f"{added} artifact file(s) added, {len(removed)} removed, "
        f"{len(changed)} with changed checksums."
    )
    if args.details:
        for group, name, version, file in removed:
            print(one_line(f"removed: {group}:{name}:{version} {file}"))

    level = "error" if args.enforce else "warning"
    for line in duplicates:
        print(f"::error::{one_line(line)}; Gradle merges duplicates, list each entry once.")
    if head_configuration != base_configuration:
        print(
            f"::{level}::<configuration> differs from the base branch (verify-metadata, trusted "
            "artifacts or keys). Review it before accepting."
        )
        if args.details:
            diff = difflib.unified_diff(
                configuration_lines(args.base),
                configuration_lines(args.head),
                "base",
                "head",
                lineterm="",
            )
            for line in diff:
                print(line)
    for group, name, version, file in changed:
        key = (group, name, version, file)
        print(
            f"::{level}::{one_line(f'{group}:{name}:{version} {file}')}: checksums changed from "
            f"{sorted(base[key])} to {sorted(head[key])}. Verify against the repository "
            "before accepting (docs/renovate.md, Gradle-Prüfsummen)."
        )
    if duplicates:
        return 1
    return 1 if args.enforce and (changed or head_configuration != base_configuration) else 0


if __name__ == "__main__":
    sys.exit(main())
