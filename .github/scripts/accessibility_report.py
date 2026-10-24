"""The accessibility report of the update package (docs/releases.md, "Prüfbericht Barrierefreiheit").

The release run starts the E2E stack from the tagged commit and runs the axe scenarios of the suite
in report mode (e2e/fixtures/a11y.ts): every analysis lands as one JSON record in a directory per
run, beside the Playwright JSON report of that run as `playwright.json` and the spec files the run
has to measure as `expected.txt`, one per line. `render` turns all of them into
barrierefreiheit/pruefbericht.md and barrierefreiheit/axe-ergebnisse.json of the package. A finding
never fails the run; a run without its Playwright report or expected list, and an expected spec
file without a single record, do, because then part of the version was not measured.
`accessibility_report.py <results>` prints the report for a look in the run log. Standard library
only.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

DIRECTORY = "barrierefreiheit/"
REPORT = DIRECTORY + "pruefbericht.md"
RESULTS = DIRECTORY + "axe-ergebnisse.json"
PLAYWRIGHT = "playwright.json"
EXPECTED = "expected.txt"
IMPACTS = ("critical", "serious", "moderate", "minor")
IMPACT_NAMES = {"critical": "kritisch", "serious": "schwerwiegend", "moderate": "mittel",
                "minor": "gering", None: "ohne Einstufung"}
SCHEMES = {"light": "hell", "dark": "dunkel"}
NOTICE = ("> Automatisierte Prüfung mit axe-core. Bedienung mit Tastatur und Screenreader und"
          " Vergrößerung prüft sie nicht; das bleibt einer Prüfung von Hand vorbehalten.")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
ELEMENT = re.compile(r"<[^<>\n]+>")
HELP_URL = re.compile(r"https://[A-Za-z0-9.-]+/[A-Za-z0-9._~/%-]*")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
NAMES_SCHEME = re.compile(r"Farbschema|\((?:hell|dunkel|light|dark)\)", re.IGNORECASE)


class ReportError(Exception):
    """Results that cannot make up a report."""


def text(value: object) -> str:
    """`value` as text for a table cell: one line, no pipe, HTML tags as code, no raw angle brackets."""
    flat = " ".join(str(value).split()).replace("|", "\\|")
    parts = []
    position = 0
    for match in ELEMENT.finditer(flat):
        parts.append(flat[position:match.start()].replace("<", "&lt;").replace(">", "&gt;"))
        parts.append(code(match.group(0)))
        position = match.end()
    parts.append(flat[position:].replace("<", "&lt;").replace(">", "&gt;"))
    return "".join(parts)


def code(value: str) -> str:
    """`value` as a code span that survives backticks inside it."""
    value = " ".join(value.split()).replace("|", "\\|")
    fence = "`" * (max((len(m) for m in re.findall(r"`+", value)), default=0) + 1)
    pad = " " if value.startswith("`") or value.endswith("`") else ""
    return f"{fence}{pad}{value}{pad}{fence}"


def load(results: Path) -> list[dict]:
    """The runs below `results`, one per subdirectory: its name, its expected spec files, its records
    in the order they were taken (only the last attempt of a retried test) and the scenarios of the
    expected spec files that did not pass."""
    if not results.is_dir():
        raise ReportError(f"axe results {results} are missing")
    runs = []
    for directory in sorted(p for p in results.iterdir() if p.is_dir()):
        if not (directory / EXPECTED).is_file():
            raise ReportError(f"{directory / EXPECTED} is missing; the run names no spec files to measure")
        expected = sorted({line.strip() for line in (directory / EXPECTED).read_text(encoding="utf-8")
                           .splitlines() if line.strip()})
        if not expected:
            raise ReportError(f"{directory / EXPECTED} names no spec file")
        if not (directory / PLAYWRIGHT).is_file():
            raise ReportError(f"{directory / PLAYWRIGHT} is missing; the run {directory.name} did not"
                              " reach its scenarios")
        records = []
        for file in sorted(directory.glob("*.json")):
            if file.name == PLAYWRIGHT:
                continue
            try:
                record = json.loads(file.read_text(encoding="utf-8"))
            except ValueError as error:
                raise ReportError(f"{file} is no JSON: {error}") from error
            if not isinstance(record, dict) or not isinstance(record.get("violations"), list):
                raise ReportError(f"{file} is no axe record of e2e/fixtures/a11y.ts")
            records.append(record)
        last = {}
        for record in records:
            key = (record.get("file"), record.get("test"))
            last[key] = max(last.get(key, 0), int(record.get("retry") or 0))
        # measuredAt orders across worker restarts, which start sequence anew; ties stay stable.
        records = sorted((r for r in records if int(r.get("retry") or 0) == last[(r.get("file"), r.get("test"))]),
                         key=lambda r: (str(r.get("measuredAt") or ""), int(r.get("sequence") or 0),
                                        str(r.get("test")), str(r.get("context"))))
        silent = [name for name in expected if not any(r.get("file") == name for r in records)]
        if silent:
            raise ReportError(f"the run {directory.name} measured nothing in {', '.join(silent)};"
                              " the accessibility run measured nothing there")
        runs.append({"name": directory.name, "expected": expected, "records": records,
                     "unfinished": unfinished(directory / PLAYWRIGHT, set(expected))})
    if not runs:
        raise ReportError(f"no run below {results}; the accessibility run measured nothing")
    return runs


def unfinished(report: Path, files: set[str]) -> list[dict]:
    """Scenarios of a Playwright JSON report whose last attempt neither passed nor was skipped, with
    the first line of their error, from the spec files in `files` only."""
    try:
        data = json.loads(report.read_text(encoding="utf-8"))
    except ValueError as error:
        raise ReportError(f"{report} is no JSON: {error}") from error
    found = []

    def walk(suite: dict, path: list[str]) -> None:
        title = suite.get("title")
        here = [*path, title] if title else path
        for spec in suite.get("specs") or []:
            results = [r for t in spec.get("tests") or [] for r in t.get("results") or []]
            last = results[-1] if results else None
            if last is not None and last.get("status") in ("passed", "skipped"):
                continue
            error = next((r["error"]["message"] for r in results
                          if isinstance(r.get("error"), dict) and r["error"].get("message")), "")
            found.append({"scenario": " › ".join([*here, spec.get("title", "")]),
                          "status": last.get("status") if last else "not run",
                          "error": ANSI.sub("", error).split("\n", 1)[0]})
        for child in suite.get("suites") or []:
            walk(child, here)

    for suite in data.get("suites") or []:
        if suite.get("title") in files:
            walk(suite, [])
    return found


def count_by_impact(violations: list[dict]) -> str:
    if not violations:
        return "keine"
    counts = {}
    for violation in violations:
        impact = violation.get("impact")
        counts[impact] = counts.get(impact, 0) + 1
    order = [*IMPACTS, None]
    parts = [f"{counts[i]} {IMPACT_NAMES[i]}" for i in sorted(counts, key=lambda i: order.index(i) if i in order else len(order))]
    return f"{len(violations)} ({', '.join(parts)})"


def state(record: dict) -> str:
    """The context of a record, with its colour scheme unless the context already names one."""
    context = str(record.get("context", ""))
    if NAMES_SCHEME.search(context):
        return text(context)
    return f"{text(context)} ({SCHEMES.get(record.get('colorScheme'), 'Farbschema unbekannt')})"


def page(record: dict) -> str:
    """The path of a record with identifiers folded, so one page counts once whatever it shows."""
    return UUID.sub("<id>", str(record.get("path", "")))


def day(value: object) -> str:
    match = re.match(r"\d{4}-\d{2}-\d{2}", str(value or ""))
    return match.group(0) if match else ""


def render(runs: list[dict], version: str, commit: str) -> dict[str, bytes]:
    """The report and the raw results of `runs` (as `load` returns them) for `version` at `commit`."""
    records = [r for run in runs for r in run["records"]]
    dates = sorted(d for d in (day(r.get("measuredAt")) for r in records) if d)
    period = (dates[0] if dates[0] == dates[-1] else f"{dates[0]} bis {dates[-1]}") if dates else "unbekannt"
    versions = sorted({code(str(r.get("axeVersion"))) for r in records if r.get("axeVersion")})
    tags = sorted({t for r in records for t in r.get("tags") or []})
    violations = [v for r in records for v in r["violations"]]
    unclear = sorted({i["id"] for r in records for i in r.get("incomplete") or []})
    stopped = [s for run in runs for s in run["unfinished"] or []]
    schemes = {s: sum(1 for r in records if r.get("colorScheme") == s) for s in SCHEMES}
    by_page: dict[str, set[str]] = {}
    for record in records:
        by_page.setdefault(page(record), set()).add(str(record.get("colorScheme")))
    both = sorted(p for p, s in by_page.items() if {"light", "dark"} <= s)
    single = sorted(p for p in by_page if p not in both)

    lines = [
        "---",
        "erzeugung: generiert",
        f"gilt-fuer: {version}",
        f"commit: {commit}",
        f"geprueft-am: {period}",
        "---",
        "",
        f"# Prüfbericht Barrierefreiheit OPAA {version}",
        "",
        NOTICE,
        "",
        f"Der Release-Lauf startet OPAA aus dem Commit `{commit}` dieser Version mit den Testdaten der"
        " E2E-Suite und prüft jede Seite und jeden Zustand, den die Barrierefreiheitsszenarien der Suite"
        " aufrufen, mit axe-core gegen WCAG 2.1, Stufen A und AA. Jeder Zustand ist in dem Farbschema"
        " geprüft, das sein Szenario einstellt; ein Teil der Seiten in beiden, die übrigen nur in einem"
        " (Abschnitt „Farbschemata“). Jeder Befund steht unten; ein Befund hält das Release nicht auf.",
        "",
        "## Ergebnis",
        "",
        "| Angabe | Wert |",
        "|---|---|",
        f"| Prüfdatum | {period} |",
        f"| Werkzeug | axe-core {', '.join(versions) or 'Version unbekannt'} |",
        f"| Regelsatz | {', '.join(code(t) for t in tags) or 'unbekannt'} |",
        f"| Geprüfte Zustände | {len(records)} (hell {schemes['light']}, dunkel {schemes['dark']})"
        f" auf {len(by_page)} Seiten |",
        f"| Seiten in beiden Farbschemata | {len(both)} von {len(by_page)} |",
        f"| Befunde | {count_by_impact(violations)} |",
        f"| Von Hand zu prüfen | {len(unclear)} Regeln, die axe nicht entscheiden konnte |",
        f"| Szenarien nicht vollständig | {len(stopped)} |",
        f"| Geprüfte Szenario-Dateien | {', '.join(code(f) for run in runs for f in run['expected'])} |",
        "",
        "Schwerwiegende und kritische Befunde lassen im Entwicklungsstand jede Änderung scheitern;"
        " mittlere und geringe werden dort nur angezeigt. Dieser Bericht führt alle vier Stufen.",
        "",
        "## Geprüfte Seiten und Zustände",
        "",
        "| Zustand | Seite | Befunde | Ausnahmen |",
        "|---|---|---|---|",
    ]
    for record in records:
        exceptions = [f"Regel {code(r)} aus" for r in record.get("disabledRules") or []]
        exceptions += [f"ohne {code(s)}" for s in record.get("excluded") or []]
        lines.append(f"| {state(record)} | {code(page(record))} |"
                     f" {count_by_impact(record['violations'])} | {', '.join(exceptions) or 'keine'} |")
    lines += ["", "Eine Ausnahme nimmt eine Regel oder einen Bereich der Seite für dieses Szenario aus der"
              " Prüfung; ihre Begründung steht am Szenario in der E2E-Suite. `<id>` steht für die"
              " Kennung eines Space, einer Bibliothek oder eines Chats.", "", "## Farbschemata", "",
              f"In beiden Farbschemata geprüft sind {len(both)} von {len(by_page)} Seiten; die übrigen nur"
              " in dem Farbschema, das ihr Szenario einstellt.", ""]
    if single:
        lines += ["| Seite | Farbschema |", "|---|---|"]
        for path in single:
            names = [SCHEMES.get(s, "unbekannt") for s in ("light", "dark") if s in by_page[path]]
            lines.append(f"| {code(path)} | nur {', '.join(names) or 'unbekannt'} |")
        lines.append("")
    lines += ["## Befunde", ""]
    found = [r for r in records if r["violations"]]
    if not found:
        lines += ["Keine Befunde.", ""]
    for record in found:
        lines += [f"### {state(record)}", "", f"Seite {code(page(record))}.", "",
                  "| Regel | Stufe | Elemente | Beschreibung | Betroffen, bis zu fünf |", "|---|---|---|---|---|"]
        for violation in record["violations"]:
            help_url = str(violation.get("helpUrl") or "")
            described = text(violation.get("help", ""))
            if HELP_URL.fullmatch(help_url):
                described += f" ([Erläuterung]({help_url}))"
            lines.append(f"| {code(str(violation.get('id')))} | {IMPACT_NAMES.get(violation.get('impact'), 'ohne Einstufung')}"
                         f" | {number(violation.get('nodes'))} | {described} |"
                         f" {'; '.join(code(t) for t in violation.get('targets') or [])} |")
        lines.append("")

    lines += ["## Von Hand zu prüfen", ""]
    if unclear:
        lines += ["axe konnte diese Regeln für einige Elemente nicht entscheiden, etwa den Kontrast von Text"
                  " auf einem Hintergrundbild. Sie sind weder bestanden noch Befund.", "",
                  "| Regel | Zustände | Elemente |", "|---|---|---|"]
        for rule in unclear:
            hits = [i for r in records for i in r.get("incomplete") or [] if i["id"] == rule]
            lines.append(f"| {code(rule)} | {len(hits)} | {sum(number(i.get('nodes')) for i in hits)} |")
    else:
        lines.append("axe hat jede angewandte Regel entschieden.")
    lines += ["", "## Geprüfte Regeln", "",
              "Jede Regel, die axe auf mindestens einem Zustand angewandt hat, mit der Zahl der Zustände je"
              " Ergebnis. Regeln ohne passendes Element erscheinen nicht.", "",
              "| Regel | bestanden | Befund | von Hand zu prüfen |", "|---|---|---|---|"]
    rules = sorted({p for r in records for p in r.get("passes") or []} | {v["id"] for v in violations}
                   | set(unclear))
    for rule in rules:
        passed = sum(1 for r in records if rule in (r.get("passes") or []))
        failed = sum(1 for r in records if any(v["id"] == rule for v in r["violations"]))
        open_ = sum(1 for r in records if any(i["id"] == rule for i in r.get("incomplete") or []))
        lines.append(f"| {code(rule)} | {passed} | {failed} | {open_} |")

    lines += ["", "## Nicht vollständig geprüft", ""]
    if stopped:
        lines += ["Diese Szenarien brachen vor ihrem Ende ab, etwa weil eine Seite nicht wie erwartet"
                  " erschien. Zustände nach dem Abbruch fehlen oben.", "",
                  "| Szenario | Ergebnis | Meldung |", "|---|---|---|"]
        for scenario in stopped:
            lines.append(f"| {text(scenario['scenario'])} | {code(scenario['status'])} | {text(scenario['error']) or '–'} |")
    else:
        lines.append("Alle Szenarien der geprüften Szenario-Dateien liefen vollständig durch.")
    lines += ["", "## Grenzen", "",
              "- Geprüft sind nur die Seiten und Zustände oben, mit den Testdaten der Suite; eigenes Branding"
              " und eigene Dokumente der Behörde nicht.",
              "- Automatische Werkzeuge finden nur einen Teil der Barrieren. Tastaturbedienung, Screenreader"
              " und Vergrößerung belegt nur eine Prüfung von Hand.",
              f"- Die Einzelergebnisse stehen in `{RESULTS}`, für Werkzeuge.", ""]
    raw = {"version": version, "commit": commit,
           "runs": [{"name": run["name"], "expected": run["expected"], "records": run["records"],
                     "unfinished": run["unfinished"]}
                    for run in runs]}
    return {
        REPORT: ("\n".join(lines).rstrip() + "\n").encode("utf-8"),
        RESULTS: (json.dumps(raw, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    }


def number(value: object) -> int:
    """A count from a record; anything that is no whole number counts as 0."""
    return value if isinstance(value, int) and not isinstance(value, bool) else 0


def not_measured(version: str, commit: str) -> dict[str, bytes]:
    """The report of a trial run without axe results; a release run always passes them."""
    lines = [
        "---", "erzeugung: generiert", f"gilt-fuer: {version}", f"commit: {commit}", "---", "",
        f"# Prüfbericht Barrierefreiheit OPAA {version}", "", NOTICE, "",
        "**Nicht geprüft.** Dieses Paket entstand ohne den Prüflauf mit axe-core, etwa in einem"
        " Probelauf. Das Paket eines Release enthält hier den Bericht der Version.", "",
    ]
    return {REPORT: "\n".join(lines).encode("utf-8")}


def main(argv: list[str]) -> int:
    """`accessibility_report.py <results>` prints the report of the results for a look in the run log;
    exit code 1 when they cannot make up a report."""
    if len(argv) != 1:
        print("usage: accessibility_report.py <results>", file=sys.stderr)
        return 2
    try:
        report = render(load(Path(argv[0])), "0.0.0-probe", "HEAD")
    except ReportError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    sys.stdout.write(report[REPORT].decode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
