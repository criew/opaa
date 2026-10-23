"""Reads .github/support-lines.json, the single list of release lines and their support ends.

Each entry names a line "X.Y", its kind ("lts" or "normal") and "supportEnd", the last day with
patch releases as YYYY-MM-DD, or null for a normal line whose successor has not appeared yet. A
line is supported on a day up to and including its support end (docs/releases.md, Supportlinien).

  lines          the lines supported on --date (default: today, UTC), one per line
  base-branches  JSON array for Renovate: main and release/X.Y of every supported line

Exit 1 with a message if the file is missing or malformed.
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=["lines", "base-branches"])
    parser.add_argument("--file", type=Path, default=DEFAULT_FILE)
    parser.add_argument("--date", type=parse_date, default=None)
    args = parser.parse_args()
    day = args.date or datetime.datetime.now(datetime.timezone.utc).date()
    try:
        lines = supported(load(args.file), day)
    except Malformed as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    if args.command == "lines":
        for line in lines:
            print(line)
    else:
        print(json.dumps(["main", *(f"release/{line}" for line in lines)]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
