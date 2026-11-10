#!/usr/bin/env python3
"""Lists the slowest test classes of a Gradle test run as a Markdown table for the job summary.

Reads every TEST-*.xml JUnit report below the given directory and sorts the classes by the time
of their testsuite element, which includes class setup such as a Spring context or container
boot. The sum is CPU-side test time, not wall time: the CI runs two test JVMs side by side.
A missing or empty directory prints a note and exits 0, so a compile failure does not hide the
test step's own error. Standard library only.
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def collect(results: Path) -> list[tuple[str, float, int]]:
    classes = []
    for report in sorted(results.rglob("TEST-*.xml")):
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        classes.append((suite.get("name", report.stem), float(suite.get("time", "0") or 0),
                        int(suite.get("tests", "0") or 0)))
    return sorted(classes, key=lambda c: c[1], reverse=True)


def render(classes: list[tuple[str, float, int]], top: int) -> str:
    if not classes:
        return "Keine JUnit-Berichte gefunden.\n"
    total = sum(c[1] for c in classes)
    lines = [
        f"### Langsamste Testklassen ({len(classes)} Klassen, Summe {total / 60:.1f} min)",
        "",
        "| Klasse | Zeit (s) | Tests |",
        "|---|---:|---:|",
    ]
    lines += [f"| `{name}` | {seconds:.1f} | {tests} |" for name, seconds, tests in classes[:top]]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="directory with the JUnit XML reports")
    parser.add_argument("--top", type=int, default=25)
    args = parser.parse_args(argv)
    classes = collect(args.results) if args.results.is_dir() else []
    sys.stdout.write(render(classes, args.top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
