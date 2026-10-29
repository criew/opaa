"""Suppressions of the ZAP baseline scan: .github/zap/rules.yaml (docs/entwicklung/zap-baseline.md).

Every entry suppresses one ZAP rule and carries the rule's id and name, a reason and an expiry
date. Optional "paths" (regular expressions matched from the start of the URL path) and "params"
(exact values of the finding's parameter, such as a cookie name) narrow it down. An entry expires at
the start of its date (UTC); from then on the finding counts again.

  check    exit 1 if an entry is malformed, expired or expires more than MAX_DAYS days ahead
  render   ZAP's tab-separated rule file (-c) of all entries still valid; malformed and expired
           entries are left out, so the finding reappears even if check is not looked at. ZAP's
           file knows no parameters, so "params" is enforced by summary alone.
  summary  Markdown of a JSON report (-J) for the job summary; exit 1 if a finding of risk Low or
           higher is not suppressed or ZAP's own exit status (--zap-exit) is not 0. Informational
           findings are listed but never fail, as zap-baseline.py ignores them as well.
"""

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

import yaml

MAX_DAYS = 92
KEYS = {"id", "name", "reason", "expires"}
OPTIONAL_KEYS = {"paths", "params"}
RULE_ID = re.compile(r"[1-9][0-9]*")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
RISKS = {"0": "Informational", "1": "Low", "2": "Medium", "3": "High"}


class Malformed(Exception):
    pass


def load(path: Path) -> list:
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        raise Malformed(f"{path}: {error}") from error
    rules = data.get("rules") if isinstance(data, dict) else None
    if not isinstance(rules, list):
        raise Malformed(f'{path}: expected a mapping with a list "rules"')
    return rules


def problems(entry: object, today: datetime.date) -> list[str]:
    """Everything wrong with one entry; an empty list means it is valid on `today`."""
    if not isinstance(entry, dict):
        return [f"entry is not a mapping: {entry!r}"]
    found = []
    missing = KEYS - set(entry)
    unknown = set(entry) - KEYS - OPTIONAL_KEYS
    if missing:
        found.append(f"missing {', '.join(sorted(missing))}")
    if unknown:
        found.append(f"unknown key {', '.join(sorted(unknown))}")
    rule_id = str(entry.get("id", ""))
    if "id" in entry and not RULE_ID.fullmatch(rule_id):
        found.append(f"id {entry['id']!r} is not a ZAP rule id")
    for key in ("name", "reason"):
        if key in entry and (not isinstance(entry[key], str) or not entry[key].strip()):
            found.append(f"{key} is empty")
    if "expires" in entry:
        expires = entry["expires"]
        # PyYAML reads an unquoted YYYY-MM-DD as a date, a quoted one as a string.
        if isinstance(expires, str) and DATE.fullmatch(expires):
            try:
                expires = datetime.date.fromisoformat(expires)
            except ValueError:
                pass
        if not isinstance(expires, datetime.date) or isinstance(expires, datetime.datetime):
            found.append(f"expires {entry['expires']!r} is not a date YYYY-MM-DD")
        elif expires <= today:
            found.append(f"expired on {expires.isoformat()}")
        elif (expires - today).days > MAX_DAYS:
            found.append(f"expires {expires.isoformat()}, more than {MAX_DAYS} days ahead")
    if "paths" in entry:
        paths = entry["paths"]
        if not isinstance(paths, list) or not paths:
            found.append("paths is not a non-empty list")
        else:
            for path in paths:
                if not isinstance(path, str) or not path.startswith("/"):
                    found.append(f"path {path!r} does not start with /")
                    continue
                try:
                    re.compile(path)
                except re.error as error:
                    found.append(f"path {path!r} is no regular expression: {error}")
    if "params" in entry:
        params = entry["params"]
        if not isinstance(params, list) or not params:
            found.append("params is not a non-empty list")
        elif not all(isinstance(param, str) and param for param in params):
            found.append("params holds an empty or non-string value")
    return found


def label(entry: object) -> str:
    if isinstance(entry, dict) and "id" in entry:
        return f"{entry['id']} ({entry.get('name', '?')})"
    return "(entry without id)"


def valid_entries(rules: list, today: datetime.date) -> list[dict]:
    """The entries in force on `today`; a rule listed twice drops both copies."""
    counts: dict[str, int] = {}
    for entry in rules:
        if isinstance(entry, dict) and "id" in entry:
            counts[str(entry["id"])] = counts.get(str(entry["id"]), 0) + 1
    return [
        entry
        for entry in rules
        if not problems(entry, today) and counts[str(entry["id"])] == 1
    ]


def all_problems(rules: list, today: datetime.date) -> list[str]:
    found = [f"{label(entry)}: {problem}" for entry in rules for problem in problems(entry, today)]
    seen = set()
    for entry in rules:
        if isinstance(entry, dict) and "id" in entry:
            rule_id = str(entry["id"])
            if rule_id in seen:
                found.append(f"{label(entry)}: rule listed twice; merge the entries")
            seen.add(rule_id)
    return found


def url_pattern(path: str) -> str:
    """ZAP matches OUTOFSCOPE patterns from the start of the full URL; any scheme and host."""
    return "^https?://[^/]+" + path


def render(rules: list, today: datetime.date) -> str:
    lines = ["# Generated by .github/scripts/zap_rules.py from .github/zap/rules.yaml"]
    for entry in valid_entries(rules, today):
        rule_id, name = str(entry["id"]), " ".join(entry["name"].split())
        if "paths" in entry:
            for path in entry["paths"]:
                lines.append(f"{rule_id}\tOUTOFSCOPE\t{url_pattern(path)}")
        else:
            lines.append(f"{rule_id}\tIGNORE\t({name})")
    return "\n".join(lines) + "\n"


def covers(entry: dict, instance: dict) -> bool:
    if "paths" in entry:
        url = instance.get("uri", "")
        if not any(re.match(url_pattern(path), url) for path in entry["paths"]):
            return False
    return "params" not in entry or instance.get("param", "") in entry["params"]


def suppressing_entry(alert: dict, entries: dict[str, dict]):
    """The entry that covers every instance of the alert, or None."""
    entry = entries.get(str(alert.get("pluginid")))
    instances = alert.get("instances") or []
    if entry is not None and instances and all(covers(entry, i) for i in instances):
        return entry
    return None


def cell(text: object) -> str:
    return " ".join(str(text).split()).replace("|", "\\|")


def table(title: str, header: list[str], rows: list[list[object]]) -> list[str]:
    lines = ["", f"### {title}", "", "| " + " | ".join(header) + " |"]
    lines.append("|" + "---|" * len(header))
    return lines + ["| " + " | ".join(cell(value) for value in row) + " |" for row in rows]


def summary(rules: list, report: dict, zap_exit: int, today: datetime.date) -> tuple[str, bool]:
    """Markdown for the job summary and whether the run passes."""
    entries = {str(e["id"]): e for e in valid_entries(rules, today)}
    new, suppressed, info = [], [], []
    seen_ids = set()
    for site in report.get("site") or []:
        for alert in site.get("alerts") or []:
            rule_id = str(alert.get("pluginid"))
            seen_ids.add(rule_id)
            urls = [i.get("uri", "") for i in alert.get("instances") or []]
            risk = RISKS.get(str(alert.get("riskcode")), "?")
            row = (rule_id, alert.get("name") or alert.get("alert") or "?", risk, len(urls), urls)
            entry = suppressing_entry(alert, entries)
            if entry is not None:
                suppressed.append((*row, entry))
            elif risk == "Informational":
                info.append(row)
            else:
                new.append(row)

    passed = not new and zap_exit == 0
    out = ["## ZAP-Baseline-Scan", ""]
    if passed:
        out.append("Keine neuen Warnungen.")
    else:
        out.append(f"**Neue Warnungen: {len(new)}** · Exit-Status von zap-baseline.py: {zap_exit}")
        if not new:
            out.append("")
            out.append(
                "zap-baseline.py meldet einen Fehler oder einen Befund, den diese Auswertung nicht "
                "sieht; siehe `zap.log` im Artefakt."
            )
    if new:
        out += table(
            "Neu",
            ["Regel", "Name", "Risiko", "Fundstellen", "Beispiel"],
            [[i, name, risk, n, urls[0] if urls else ""] for i, name, risk, n, urls in new],
        )
    if suppressed:
        out += table(
            "Unterdrückt",
            ["Regel", "Name", "Risiko", "Fundstellen", "bis"],
            [[i, name, risk, n, e["expires"]] for i, name, risk, n, _, e in suppressed],
        )
    if info:
        out += table(
            "Informativ (zählt nicht)",
            ["Regel", "Name", "Fundstellen"],
            [[i, name, n] for i, name, _, n, _ in info],
        )
    unused = sorted(set(entries) - seen_ids, key=int)
    if unused:
        out += ["", "### Unterdrückungen ohne Befund", ""]
        out.append(
            "Diese Einträge in `.github/zap/rules.yaml` haben in diesem Lauf nichts unterdrückt und "
            "können entfallen: " + ", ".join(label(entries[i]) for i in unused)
        )
    return "\n".join(out) + "\n", passed


def parse_today(value: str | None) -> datetime.date:
    if value is None:
        return datetime.datetime.now(datetime.timezone.utc).date()
    return datetime.date.fromisoformat(value)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("command", choices=["check", "render", "summary"])
    parser.add_argument("--rules", type=Path, required=True)
    parser.add_argument("--out", type=Path, help="render: file to write")
    parser.add_argument("--report", type=Path, help="summary: ZAP JSON report")
    parser.add_argument(
        "--zap-exit", type=int, default=0, help="summary: exit status of zap-baseline.py"
    )
    parser.add_argument("--today", help="YYYY-MM-DD instead of today (UTC)")
    args = parser.parse_args(argv)
    today = parse_today(args.today)

    try:
        rules = load(args.rules)
    except Malformed as error:
        print(f"::error file={args.rules}::{error}")
        return 1

    if args.command == "check":
        found = all_problems(rules, today)
        for problem in found:
            print(f"::error file={args.rules}::{problem}")
        if not found:
            print(f"{len(rules)} suppression(s) valid on {today.isoformat()}.")
        return 1 if found else 0

    if args.command == "render":
        if args.out is None:
            parser.error("render needs --out")
        for problem in all_problems(rules, today):
            print(f"::warning file={args.rules}::left out: {problem}")
        args.out.write_text(render(rules, today), encoding="utf-8")
        return 0

    if args.report is None:
        parser.error("summary needs --report")
    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print("## ZAP-Baseline-Scan\n\n**Kein auswertbarer Bericht.** Siehe `zap.log` im Artefakt.")
        print(f"::error::no readable ZAP report {args.report}: {error}", file=sys.stderr)
        return 1
    text, passed = summary(rules, report, args.zap_exit, today)
    print(text, end="")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
