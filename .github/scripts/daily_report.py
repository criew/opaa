#!/usr/bin/env python3
"""Erzeugt den täglichen Projektreport als HTML-Seite und Atom-Feed.

Das Skript sammelt über die GitHub-API, was an einem Tag im Repository
passiert ist, lässt daraus optional eine Zusammenfassung schreiben und legt
das Ergebnis im Ausgabeverzeichnis ab. Aus den gespeicherten Rohdaten aller
bisherigen Tage werden Übersichtsseite und Feed jedes Mal neu erzeugt, damit
sich Layoutänderungen rückwirkend auf alle Reports auswirken.

Aufruf:
    daily_report.py --repo criew/opaa --date 2026-08-01 --output site/

Benötigt die GitHub-CLI (`gh`) mit gültigem Token. Ein API-Schlüssel für die
Zusammenfassung ist optional; fehlt er, entsteht der Report ohne Fließtext.
Der Schlüssel wird bewusst aus OPAA_REPORT_API_KEY gelesen und nicht aus dem
Anwendungsschlüssel OPAA_OPENAI_API_KEY, damit sich das Aktivieren der
Zusammenfassung nicht auf die Integrationstests in der CI auswirkt.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date as Date
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


def _local_timezone() -> ZoneInfo | timezone:
    """Zeitzone für die Tagesgrenzen des Reports.

    Fehlt die Zeitzonendatenbank — etwa bei einer Python-Installation ohne
    tzdata unter Windows — wird auf UTC ausgewichen. Der Report entsteht dann
    trotzdem, die Tagesgrenzen verschieben sich aber um ein bis zwei Stunden.
    """
    try:
        return ZoneInfo("Europe/Berlin")
    except ZoneInfoNotFoundError:
        print(
            "Zeitzone Europe/Berlin nicht verfügbar (tzdata fehlt) — weiche auf UTC aus.",
            file=sys.stderr,
        )
        return timezone.utc


TIMEZONE = _local_timezone()
UTC = timezone.utc
SEARCH_PAGE_SIZE = 100
SEARCH_MAX_PAGES = 10
# Obergrenze je Abschnitt im Prompt. An Tagen mit sehr vielen Issues bleibt der
# Aufruf so bezahlbar, ohne dass der Report selbst gekürzt wird.
PROMPT_MAX_ITEMS = 25

WEEKDAYS = (
    "Montag",
    "Dienstag",
    "Mittwoch",
    "Donnerstag",
    "Freitag",
    "Samstag",
    "Sonntag",
)
MONTHS = (
    "Januar",
    "Februar",
    "März",
    "April",
    "Mai",
    "Juni",
    "Juli",
    "August",
    "September",
    "Oktober",
    "November",
    "Dezember",
)


def german_date(day: Date) -> str:
    return f"{WEEKDAYS[day.weekday()]}, {day.day}. {MONTHS[day.month - 1]} {day.year}"


# --------------------------------------------------------------------------
# GitHub-Abfragen
# --------------------------------------------------------------------------


def gh_api(path: str, *, paginate: bool = False) -> object:
    """Ruft die GitHub-API über die CLI auf und gibt die Antwort als JSON zurück."""
    command = ["gh", "api"]
    if paginate:
        command.append("--paginate")
    command.append(path)
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"gh api {path} fehlgeschlagen: {result.stderr.strip()}")
    return json.loads(result.stdout)


def day_bounds(day: Date) -> tuple[str, str]:
    """Liefert Beginn und Ende eines Tages als ISO-Zeitstempel mit Zeitzone.

    Die Suche der GitHub-API rechnet ohne Offset in UTC. Für einen Report, der
    sich an der lokalen Arbeitszeit orientiert, muss der Offset mitgegeben
    werden, sonst wandern Abendereignisse in den Folgetag.
    """
    start = datetime.combine(day, datetime.min.time(), tzinfo=TIMEZONE)
    end = start + timedelta(days=1) - timedelta(seconds=1)
    return start.isoformat(), end.isoformat()


def search_issues(repo: str, qualifier: str) -> list[dict]:
    """Führt eine Suche aus und gibt alle Treffer zurück."""
    items: list[dict] = []
    for page in range(1, SEARCH_MAX_PAGES + 1):
        query = urllib.parse.quote(f"repo:{repo} {qualifier}")
        path = f"search/issues?q={query}&per_page={SEARCH_PAGE_SIZE}&page={page}"
        response = gh_api(path)
        batch = response.get("items", [])
        items.extend(batch)
        if len(batch) < SEARCH_PAGE_SIZE:
            break
    return items


def simplify_issue(item: dict) -> dict:
    return {
        "number": item["number"],
        "title": item["title"],
        "url": item["html_url"],
        "author": (item.get("user") or {}).get("login", ""),
        "labels": [label["name"] for label in item.get("labels", [])],
        "body": (item.get("body") or "").strip(),
    }


def pull_request_stats(repo: str, number: int) -> dict:
    """Holt den Änderungsumfang eines Pull Requests."""
    try:
        detail = gh_api(f"repos/{repo}/pulls/{number}")
    except RuntimeError:
        return {}
    return {
        "additions": detail.get("additions", 0),
        "deletions": detail.get("deletions", 0),
        "changed_files": detail.get("changed_files", 0),
    }


def ci_status(repo: str) -> dict | None:
    """Ermittelt den letzten CI-Lauf auf dem Hauptbranch."""
    try:
        response = gh_api(
            f"repos/{repo}/actions/workflows/ci.yml/runs?branch=main&per_page=1"
        )
    except RuntimeError:
        return None
    runs = response.get("workflow_runs", [])
    if not runs:
        return None
    run = runs[0]
    return {
        "conclusion": run.get("conclusion") or run.get("status", "unbekannt"),
        "url": run.get("html_url", ""),
        "title": (run.get("display_title") or "").strip(),
        "finished_at": run.get("updated_at", ""),
    }


def collect(repo: str, day: Date) -> dict:
    """Trägt alle Daten für einen Tag zusammen."""
    start, end = day_bounds(day)
    window = f"{start}..{end}"

    closed_issues = [
        simplify_issue(item)
        for item in search_issues(repo, f"is:issue is:closed closed:{window}")
    ]
    opened_issues = [
        simplify_issue(item)
        for item in search_issues(repo, f"is:issue created:{window}")
    ]
    merged = [
        simplify_issue(item)
        for item in search_issues(repo, f"is:pr is:merged merged:{window}")
    ]
    for pull_request in merged:
        pull_request.update(pull_request_stats(repo, pull_request["number"]))

    open_pulls = [
        simplify_issue(item) for item in search_issues(repo, "is:pr is:open")
    ]
    # Der Body offener PRs wird im Report nicht gezeigt und spart so Platz.
    for pull_request in open_pulls:
        pull_request["body"] = ""

    return {
        "repo": repo,
        "date": day.isoformat(),
        "generated_at": datetime.now(tz=TIMEZONE).isoformat(),
        "closed_issues": closed_issues,
        "opened_issues": opened_issues,
        "merged_pull_requests": merged,
        "open_pull_requests": open_pulls,
        "ci": ci_status(repo),
        "summary": "",
    }


def has_activity(data: dict) -> bool:
    """Ein Tag ohne Issue- oder PR-Bewegung bekommt keinen Report."""
    return bool(
        data["closed_issues"]
        or data["opened_issues"]
        or data["merged_pull_requests"]
    )


# --------------------------------------------------------------------------
# Zusammenfassung
# --------------------------------------------------------------------------

SUMMARY_SYSTEM_PROMPT = """\
Du schreibst die Zusammenfassung eines Tagesreports für ein Softwareprojekt.

Schreibe zwei bis vier Absätze in schlichtem, sachlichem Deutsch. Beschreibe,
was sich inhaltlich geändert hat und was neu angesetzt wurde — nicht, wie viele
Issues bewegt wurden. Der Leser sieht die Listen ohnehin.

Regeln:
- Beziehe dich auf Issues und Pull Requests mit ihrer Nummer, etwa "#221".
- Gruppiere nach Thema, nicht nach Issue-Reihenfolge.
- Keine Aufzählungszeichen, keine Überschriften, kein Markdown, reiner Fließtext.
- Keine Werbesprache und keine Bewertung der Arbeitsleistung.
- Wenn ein Vorhaben erkennbar über mehrere Issues zusammenhängt, benenne den
  gemeinsamen Faden.
- Technische Begriffe und Bezeichner bleiben in ihrer Originalform.
"""


def truncate(text: str, limit: int) -> str:
    text = text.strip()
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + " […]"


def build_summary_prompt(data: dict) -> str:
    lines: list[str] = [f"Datum: {german_date(Date.fromisoformat(data['date']))}", ""]

    def section(title: str, items: list[dict], *, body_limit: int) -> None:
        if not items:
            return
        lines.append(f"## {title}")
        for item in items[:PROMPT_MAX_ITEMS]:
            labels = ", ".join(item["labels"]) if item["labels"] else "ohne Label"
            lines.append(f"- #{item['number']} {item['title']} [{labels}]")
            if body_limit and item.get("body"):
                lines.append(f"  {truncate(item['body'], body_limit)}")
        if len(items) > PROMPT_MAX_ITEMS:
            lines.append(f"- … und {len(items) - PROMPT_MAX_ITEMS} weitere")
        lines.append("")

    section("Abgeschlossene Issues", data["closed_issues"], body_limit=600)
    section("Gemergte Pull Requests", data["merged_pull_requests"], body_limit=900)
    section("Neu angelegte Issues", data["opened_issues"], body_limit=600)
    return "\n".join(lines)


def summarize(data: dict) -> str:
    """Erzeugt die Zusammenfassung. Bei jedem Fehler bleibt sie leer."""
    api_key = os.environ.get("OPAA_REPORT_API_KEY", "").strip()
    if not api_key:
        print("Kein API-Schlüssel gesetzt — Report ohne Zusammenfassung.", file=sys.stderr)
        return ""

    # Nicht gesetzte Repository-Variablen erreichen den Prozess als leerer
    # String, nicht als fehlender Eintrag. Der Vorgabewert von `get` würde
    # deshalb nie greifen.
    model = os.environ.get("OPAA_REPORT_MODEL", "").strip() or "gpt-4o"
    base_url = (
        os.environ.get("OPAA_REPORT_BASE_URL", "").strip() or "https://api.openai.com"
    ).rstrip("/")
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SUMMARY_SYSTEM_PROMPT},
            {"role": "user", "content": build_summary_prompt(data)},
        ],
        "temperature": 0.3,
        "max_tokens": 900,
    }
    request = urllib.request.Request(
        f"{base_url}/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            body = json.loads(response.read().decode("utf-8"))
        return body["choices"][0]["message"]["content"].strip()
    except (urllib.error.URLError, KeyError, IndexError, TimeoutError) as error:
        print(f"Zusammenfassung fehlgeschlagen: {error}", file=sys.stderr)
        return ""


# --------------------------------------------------------------------------
# Darstellung
# --------------------------------------------------------------------------

STYLESHEET = """\
:root {
  color-scheme: light dark;
  --bg: #ffffff;
  --fg: #1a1a1a;
  --muted: #5c6370;
  --line: #e2e5e9;
  --accent: #2f6feb;
  --card: #f7f8fa;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #14161a;
    --fg: #e6e8eb;
    --muted: #9aa1ab;
    --line: #2a2e35;
    --accent: #6ea0ff;
    --card: #1b1e24;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0;
  padding: 2rem 1.25rem 4rem;
  background: var(--bg);
  color: var(--fg);
  font: 16px/1.65 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}
main { max-width: 46rem; margin: 0 auto; }
a { color: var(--accent); }
header { border-bottom: 1px solid var(--line); padding-bottom: 1.25rem; margin-bottom: 2rem; }
h1 { font-size: 1.6rem; margin: 0 0 .35rem; letter-spacing: -.01em; }
h2 { font-size: 1.1rem; margin: 2.25rem 0 .85rem; letter-spacing: -.01em; }
.sub { color: var(--muted); font-size: .9rem; margin: 0; }
.summary p { margin: 0 0 .9rem; }
ul.items { list-style: none; margin: 0; padding: 0; }
ul.items li {
  padding: .7rem .9rem;
  border: 1px solid var(--line);
  border-radius: 8px;
  margin-bottom: .5rem;
  background: var(--card);
}
.num { font-variant-numeric: tabular-nums; color: var(--muted); margin-right: .4rem; }
.meta { display: block; color: var(--muted); font-size: .82rem; margin-top: .25rem; }
.tag {
  display: inline-block;
  font-size: .72rem;
  padding: .1rem .45rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  margin-right: .3rem;
  color: var(--muted);
}
.status { font-weight: 600; }
.status.ok { color: #1a7f37; }
.status.bad { color: #cf222e; }
@media (prefers-color-scheme: dark) {
  .status.ok { color: #3fb950; }
  .status.bad { color: #f85149; }
}
footer { margin-top: 3rem; padding-top: 1.25rem; border-top: 1px solid var(--line); color: var(--muted); font-size: .85rem; }
.empty { color: var(--muted); font-style: italic; }
"""


def page(title: str, body: str, *, feed_href: str) -> str:
    return f"""<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<link rel="alternate" type="application/atom+xml" title="OPAA Tagesreport" href="{feed_href}">
<style>{STYLESHEET}</style>
</head>
<body>
<main>
{body}
</main>
</body>
</html>
"""


def render_items(items: list[dict], *, show_stats: bool = False) -> str:
    if not items:
        return '<p class="empty">Keine.</p>'
    parts = ['<ul class="items">']
    for item in items:
        tags = "".join(
            f'<span class="tag">{html.escape(label)}</span>' for label in item["labels"]
        )
        meta = tags
        if show_stats and item.get("changed_files"):
            count = item["changed_files"]
            noun = "Datei" if count == 1 else "Dateien"
            meta += f'{count} {noun}, +{item["additions"]} / −{item["deletions"]}'
        elif item.get("author"):
            meta += html.escape(item["author"])
        parts.append(
            "<li>"
            f'<span class="num">#{item["number"]}</span>'
            f'<a href="{html.escape(item["url"])}">{html.escape(item["title"])}</a>'
            f'<span class="meta">{meta}</span>'
            "</li>"
        )
    parts.append("</ul>")
    return "\n".join(parts)


def render_summary(summary: str) -> str:
    if not summary:
        return (
            '<p class="empty">Für diesen Tag liegt keine Zusammenfassung vor.</p>'
        )
    paragraphs = [p.strip() for p in summary.split("\n\n") if p.strip()]
    return "\n".join(f"<p>{html.escape(p)}</p>" for p in paragraphs)


def render_ci(ci: dict | None) -> str:
    if not ci:
        return '<p class="empty">Kein CI-Lauf gefunden.</p>'
    ok = ci["conclusion"] == "success"
    label = "grün" if ok else html.escape(ci["conclusion"])
    css = "ok" if ok else "bad"
    title = html.escape(ci["title"]) if ci["title"] else "letzter Lauf"
    return (
        f'<p>Hauptbranch: <span class="status {css}">{label}</span> — '
        f'<a href="{html.escape(ci["url"])}">{title}</a></p>'
    )


def render_report(data: dict) -> str:
    day = Date.fromisoformat(data["date"])
    body = f"""<header>
<h1>Tagesreport {html.escape(german_date(day))}</h1>
<p class="sub"><a href="../index.html">Alle Reports</a> · <a href="https://github.com/{html.escape(data["repo"])}">{html.escape(data["repo"])}</a></p>
</header>

<section class="summary">
{render_summary(data["summary"])}
</section>

<h2>Abgeschlossene Issues</h2>
{render_items(data["closed_issues"])}

<h2>Gemergte Pull Requests</h2>
{render_items(data["merged_pull_requests"], show_stats=True)}

<h2>Neu angelegte Issues</h2>
{render_items(data["opened_issues"])}

<h2>Offen zum Tagesende</h2>
{render_items(data["open_pull_requests"])}

<h2>Stand der CI</h2>
{render_ci(data["ci"])}

<footer>
Erzeugt am {html.escape(data["generated_at"][:16].replace("T", " um "))} Uhr ·
<a href="../feed.xml">Feed abonnieren</a>
</footer>"""
    return page(f"Tagesreport {day.isoformat()}", body, feed_href="../feed.xml")


def render_index(reports: list[dict], repo: str) -> str:
    if reports:
        rows = ['<ul class="items">']
        for data in reports:
            day = Date.fromisoformat(data["date"])
            counts = (
                f'{len(data["closed_issues"])} abgeschlossen · '
                f'{len(data["merged_pull_requests"])} gemergt · '
                f'{len(data["opened_issues"])} neu'
            )
            rows.append(
                "<li>"
                f'<a href="reports/{day.isoformat()}.html">{html.escape(german_date(day))}</a>'
                f'<span class="meta">{counts}</span>'
                "</li>"
            )
        rows.append("</ul>")
        listing = "\n".join(rows)
    else:
        listing = '<p class="empty">Noch keine Reports.</p>'

    body = f"""<header>
<h1>OPAA — Tagesreport</h1>
<p class="sub">Was sich im Projekt bewegt hat, Tag für Tag ·
<a href="feed.xml">Feed abonnieren</a> ·
<a href="https://github.com/{html.escape(repo)}">Repository</a></p>
</header>

{listing}

<footer>
Automatisch erzeugt aus Issues und Pull Requests von
<a href="https://github.com/{html.escape(repo)}">{html.escape(repo)}</a>.
</footer>"""
    return page("OPAA — Tagesreport", body, feed_href="feed.xml")


def render_feed(reports: list[dict], repo: str, site_url: str) -> str:
    """Erzeugt einen Atom-Feed über die jüngsten Reports."""
    updated = (
        datetime.fromisoformat(reports[0]["generated_at"]).astimezone(UTC)
        if reports
        else datetime.now(tz=UTC)
    )
    entries: list[str] = []
    for data in reports[:50]:
        day = Date.fromisoformat(data["date"])
        url = f"{site_url}/reports/{day.isoformat()}.html"
        published = datetime.fromisoformat(data["generated_at"]).astimezone(UTC)
        content = data["summary"] or "Für diesen Tag liegt keine Zusammenfassung vor."
        entries.append(
            f"""  <entry>
    <title>Tagesreport {html.escape(german_date(day))}</title>
    <link href="{html.escape(url)}"/>
    <id>{html.escape(url)}</id>
    <updated>{published.strftime("%Y-%m-%dT%H:%M:%SZ")}</updated>
    <summary type="text">{html.escape(content)}</summary>
  </entry>"""
        )

    return f"""<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>OPAA — Tagesreport</title>
  <subtitle>Was sich im Projekt bewegt hat, Tag für Tag</subtitle>
  <link href="{html.escape(site_url)}/feed.xml" rel="self"/>
  <link href="{html.escape(site_url)}/"/>
  <id>{html.escape(site_url)}/</id>
  <updated>{updated.strftime("%Y-%m-%dT%H:%M:%SZ")}</updated>
  <author><name>{html.escape(repo)}</name></author>
{chr(10).join(entries)}
</feed>
"""


# --------------------------------------------------------------------------
# Ablauf
# --------------------------------------------------------------------------


def load_existing(data_dir: Path) -> list[dict]:
    """Lädt die Rohdaten aller bisherigen Reports, neueste zuerst."""
    reports: list[dict] = []
    for path in sorted(data_dir.glob("*.json"), reverse=True):
        try:
            reports.append(json.loads(path.read_text(encoding="utf-8")))
        except json.JSONDecodeError:
            print(f"Überspringe fehlerhafte Datei: {path}", file=sys.stderr)
    return reports


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="Repository als owner/name")
    parser.add_argument(
        "--date",
        default="",
        help="Berichtstag als JJJJ-MM-TT. Standard ist der Vortag.",
    )
    parser.add_argument(
        "--output", default="site", help="Zielverzeichnis für die Seite"
    )
    parser.add_argument(
        "--site-url", default="", help="Basis-URL der veröffentlichten Seite"
    )
    args = parser.parse_args()

    if args.date:
        day = Date.fromisoformat(args.date)
    else:
        day = datetime.now(tz=TIMEZONE).date() - timedelta(days=1)

    output = Path(args.output)
    data_dir = output / "data"
    reports_dir = output / "reports"
    data_dir.mkdir(parents=True, exist_ok=True)
    reports_dir.mkdir(parents=True, exist_ok=True)

    print(f"Sammle Daten für {day.isoformat()} aus {args.repo} …")
    data = collect(args.repo, day)

    if not has_activity(data):
        print("Keine Aktivität an diesem Tag — kein Report.")
        # Index und Feed werden trotzdem neu erzeugt, damit Layoutänderungen
        # auch ohne neuen Report wirksam werden.
    else:
        data["summary"] = summarize(data)
        (data_dir / f"{day.isoformat()}.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        (reports_dir / f"{day.isoformat()}.html").write_text(
            render_report(data), encoding="utf-8"
        )
        print(f"Report geschrieben: reports/{day.isoformat()}.html")

    reports = load_existing(data_dir)
    site_url = args.site_url.rstrip("/") or f"https://github.com/{args.repo}"
    (output / "index.html").write_text(render_index(reports, args.repo), encoding="utf-8")
    (output / "feed.xml").write_text(
        render_feed(reports, args.repo, site_url), encoding="utf-8"
    )
    (output / ".nojekyll").write_text("", encoding="utf-8")
    print(f"Übersicht und Feed erzeugt ({len(reports)} Reports).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
