"""Reads .github/support-lines.json, the single list of release lines and their support ends.

Each entry names a line "X.Y", its kind ("lts" or "normal") and "supportEnd", the last day with
patch releases as YYYY-MM-DD, or null for a normal line whose successor has not appeared yet. A
line is supported on a day up to and including its support end (docs/releases.md, Supportlinien).

  lines          the lines supported on --date (default: today, UTC), one per line
  base-branches  JSON array for Renovate: main and release/X.Y of every supported line
  scan-targets   JSON array for cve-scan.yml: backend and frontend image of the newest final
                 release of every supported line and of the line of the highest final release
  check-roadmap  the released rows of the roadmap in docs/releases.md match the list

Exit 1 with a message if the file is missing or malformed, or the roadmap differs.
"""

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

DEFAULT_FILE = Path(__file__).resolve().parents[1] / "support-lines.json"
LINE = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
KEYS = {"line", "kind", "supportEnd"}
FINAL_TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


class Malformed(Exception):
    pass


def parse_date(value: str) -> datetime.date:
    if not DATE.fullmatch(value):
        raise ValueError(value)
    return datetime.date.fromisoformat(value)


def load(path: Path) -> list[dict]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise Malformed(f"{path}: {error}") from error
    lines = data.get("lines") if isinstance(data, dict) else None
    if not isinstance(lines, list):
        raise Malformed(f'{path}: expected an object with a list "lines"')
    seen = set()
    for entry in lines:
        if not isinstance(entry, dict) or set(entry) != KEYS:
            raise Malformed(f"{path}: every entry has exactly the keys {sorted(KEYS)}: {entry!r}")
        line, kind, end = entry["line"], entry["kind"], entry["supportEnd"]
        if not isinstance(line, str) or not LINE.fullmatch(line):
            raise Malformed(f"{path}: line {line!r} is not X.Y")
        if line in seen:
            raise Malformed(f"{path}: line {line} is listed twice")
        seen.add(line)
        if kind not in ("lts", "normal"):
            raise Malformed(f'{path}: line {line}: kind {kind!r} is neither "lts" nor "normal"')
        if end is None:
            if kind == "lts":
                raise Malformed(f"{path}: line {line}: an LTS line has its support end from the start")
            continue
        try:
            if not isinstance(end, str):
                raise ValueError(end)
            parse_date(end)
        except ValueError:
            raise Malformed(f"{path}: line {line}: supportEnd {end!r} is not a date YYYY-MM-DD")
    return lines


def supported(lines: list[dict], day: datetime.date) -> list[str]:
    return [
        entry["line"]
        for entry in lines
        if entry["supportEnd"] is None or day <= parse_date(entry["supportEnd"])
    ]


def version_key(tag: str) -> tuple[int, ...]:
    return tuple(int(part) for part in tag[1:].split("."))


def line_of(tag: str) -> str:
    return ".".join(tag[1:].split(".")[:2])


def scan_targets(lines: list[str], releases_path: Path, prefix: str) -> list[dict]:
    """Newest final release of every supported line and of the line of the highest final release."""
    finals = []
    for raw in releases_path.read_text(encoding="utf-8").splitlines():
        if not raw.strip():
            continue
        release = json.loads(raw)
        tag = release.get("tag_name", "")
        if not release.get("prerelease") and not release.get("draft") and FINAL_TAG.fullmatch(tag):
            finals.append(tag)
    newest: dict[str, str] = {}
    for tag in finals:
        line = line_of(tag)
        if line not in newest or version_key(tag) > version_key(newest[line]):
            newest[line] = tag
    wanted = list(lines)
    if finals:
        top_line = line_of(max(finals, key=version_key))
        if top_line not in wanted:
            wanted.append(top_line)
    targets = []
    for line in wanted:
        if line not in newest:
            print(f"::warning::Linie {line} ist unterstützt, hat aber kein finales Release; "
                  "nichts zu scannen", file=sys.stderr)
            continue
        for image in ("backend", "frontend"):
            targets.append({
                "name": f"{image}-{line}",
                "image": f"{prefix}-{image}:{newest[line][1:]}",
                "line": line,
            })
    return targets


def check_roadmap(entries: list[dict], doc: Path) -> list[str]:
    """Differences between the list and the released rows (first cell `X.Y`) of the roadmap."""
    text = doc.read_text(encoding="utf-8")
    section = text[text.index("### Roadmap"):]
    rows = {}
    for row in section.splitlines():
        cells = [cell.strip() for cell in row.strip().strip("|").split("|")]
        match = re.fullmatch(r"`(\d+\.\d+)`", cells[0]) if row.startswith("|") else None
        if match:
            rows[match.group(1)] = cells
    errors = []
    for entry in entries:
        line, cells = entry["line"], rows.pop(entry["line"], None)
        if cells is None or len(cells) != 5:
            errors.append(f"Linie {line} fehlt in der Roadmap (Zeile mit `{line}` und fünf Spalten)")
            continue
        if ("LTS" in cells[3]) != (entry["kind"] == "lts"):
            errors.append(f"Linie {line}: Art in der Roadmap „{cells[3]}“, in der Liste {entry['kind']}")
        dates = re.findall(r"\b(\d{2})\.(\d{2})\.(\d{4})\b", cells[4])
        found = [f"{y}-{m}-{d}" for d, m, y in dates]
        expected = [] if entry["supportEnd"] is None else [entry["supportEnd"]]
        if found != expected:
            errors.append(f"Linie {line}: Supportende in der Roadmap „{cells[4]}“, in der Liste "
                          f"{entry['supportEnd']}")
    for line in rows:
        errors.append(f"Linie {line} steht in der Roadmap, aber nicht in .github/support-lines.json")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=["lines", "base-branches", "scan-targets", "check-roadmap"])
    parser.add_argument("--file", type=Path, default=DEFAULT_FILE)
    parser.add_argument("--date", type=parse_date, default=None)
    parser.add_argument("--releases", type=Path, help="scan-targets: one JSON release per line")
    parser.add_argument("--image-prefix", help="scan-targets: e.g. ghcr.io/criew/opaa")
    parser.add_argument("--releases-doc", type=Path, default=DEFAULT_FILE.parents[1] / "docs" / "releases.md")
    args = parser.parse_args()
    day = args.date or datetime.datetime.now(datetime.timezone.utc).date()
    try:
        entries = load(args.file)
    except Malformed as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    lines = supported(entries, day)
    if args.command == "lines":
        for line in lines:
            print(line)
    elif args.command == "base-branches":
        print(json.dumps(["main", *(f"release/{line}" for line in lines)]))
    elif args.command == "scan-targets":
        if not args.releases or not args.image_prefix:
            parser.error("scan-targets needs --releases and --image-prefix")
        print(json.dumps(scan_targets(lines, args.releases, args.image_prefix)))
    else:
        errors = check_roadmap(entries, args.releases_doc)
        for error in errors:
            print(f"::error::{args.releases_doc.name}, Roadmap: {error}", file=sys.stderr)
        return 1 if errors else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
