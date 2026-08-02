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
import re
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


CLOSES_MUSTER = re.compile(
    r"\b(?:closes|fixes|resolves|schliesst|schließt)\s+#(\d+)", re.IGNORECASE
)


def simplify_issue(item: dict) -> dict:
    body = (item.get("body") or "").strip()
    return {
        "number": item["number"],
        "title": item["title"],
        "url": item["html_url"],
        "author": (item.get("user") or {}).get("login", ""),
        "labels": [label["name"] for label in item.get("labels", [])],
        "body": body,
        # Erlaubt es, einen Pull Request über das von ihm geschlossene Issue
        # einem Epic zuzuordnen.
        "closes": sorted({int(n) for n in CLOSES_MUSTER.findall(body)}),
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


def collect_epics(repo: str) -> list[dict]:
    """Erhebt die Epics samt Ticketliste und Fortschritt.

    Native Sub-Issues werden im Repository nicht verwendet — die Zuordnung
    steht als Ticketliste im Body des Epic-Issues. Der Status aller Tickets
    wird über einen einzigen Abruf aller Issues ermittelt statt über eine
    Abfrage je Ticket.
    """
    try:
        alle = gh_api(
            f"repos/{repo}/issues?state=all&per_page=100&labels=", paginate=True
        )
    except RuntimeError as error:
        print(f"Epics konnten nicht erhoben werden: {error}", file=sys.stderr)
        return []

    status_je_nummer = {
        item["number"]: item.get("state", "open")
        for item in alle
        if isinstance(item, dict) and "number" in item
    }

    epics: list[dict] = []
    for item in alle:
        labels = {label["name"] for label in item.get("labels", [])}
        if "epic" not in labels:
            continue
        tickets = sorted(
            {
                int(nummer)
                for nummer in re.findall(r"#(\d+)", item.get("body") or "")
                if int(nummer) != item["number"]
            }
        )
        if not tickets:
            continue
        erledigt = sum(
            1 for nummer in tickets if status_je_nummer.get(nummer) == "closed"
        )
        epics.append(
            {
                "number": item["number"],
                "title": item["title"],
                "url": item["html_url"],
                "state": item.get("state", "open"),
                "tickets": tickets,
                "tickets_total": len(tickets),
                "tickets_closed": erledigt,
            }
        )
    return epics


def assign_to_epics(data: dict, epics: list[dict]) -> dict:
    """Ordnet die Bewegungen des Tages den Epics zu.

    Ein Vorgang gehört zu einem Epic, wenn seine Nummer in dessen Ticketliste
    steht. Pull Requests werden über die von ihnen geschlossenen Issues
    zugeordnet. Was sich nicht zuordnen lässt, bleibt bewusst ungruppiert
    statt geraten zu werden.
    """
    zuordnung: dict[int, int] = {}
    for epic in epics:
        for nummer in epic["tickets"]:
            zuordnung.setdefault(nummer, epic["number"])

    gruppen: dict[int, dict] = {
        epic["number"]: {
            "number": epic["number"],
            "title": epic["title"],
            "tickets_total": epic["tickets_total"],
            "tickets_closed": epic["tickets_closed"],
            "opened": [],
            "closed": [],
            "merged": [],
        }
        for epic in epics
    }
    ohne = {"opened": [], "closed": [], "merged": []}

    def einsortieren(schluessel: str, eintraege: list[dict]) -> None:
        for eintrag in eintraege:
            epic_nummer = zuordnung.get(eintrag["number"])
            if epic_nummer is None:
                # Pull Requests tragen ihre Issue-Nummer im Body ("Closes #N").
                for referenz in eintrag.get("closes", []):
                    if referenz in zuordnung:
                        epic_nummer = zuordnung[referenz]
                        break
            ziel = gruppen[epic_nummer] if epic_nummer in gruppen else ohne
            ziel[schluessel].append(eintrag)

    einsortieren("opened", data["opened_issues"])
    einsortieren("closed", data["closed_issues"])
    einsortieren("merged", data["merged_pull_requests"])

    aktiv = [
        gruppe
        for gruppe in gruppen.values()
        if gruppe["opened"] or gruppe["closed"] or gruppe["merged"]
    ]
    # Die stärkste Tagesbewegung zuerst, damit der Absatz dazu oben steht.
    aktiv.sort(
        key=lambda g: len(g["opened"]) + len(g["closed"]) + len(g["merged"]),
        reverse=True,
    )
    return {"epics": aktiv, "ohne_epic": ohne}


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

    ergebnis = {
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
    ergebnis.update(assign_to_epics(ergebnis, collect_epics(repo)))
    return ergebnis


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

Die Eingabe ist bereits nach Epics gegliedert. Ein Epic bündelt thematisch
zusammenhängende Arbeit. Übernimm diese Gliederung unverändert.

Aufbau:
- Ein Absatz je Epic, in der vorgegebenen Reihenfolge. Kein Epic auslassen,
  keines hinzuerfinden, keine zwei Epics in einem Absatz zusammenfassen.
- Danach höchstens ein Absatz für die Vorgänge ohne Epic-Bezug. Fasse ihn
  nach Themen zusammen, etwa Projektsetup, Sicherheit oder Dokumentation,
  und halte ihn kürzer als die Epic-Absätze.
- Jeder Absatz höchstens drei Sätze, und jeder Satz höchstens 25 Wörter.

Inhalt je Absatz:
- Nenne das Epic beim Namen und sage, was an diesem Tag darin geschehen ist:
  überwiegend Definition neuer Tickets, überwiegend Umsetzung, oder beides.
- Ordne es in den Gesamtfortschritt ein. Jede Zahl, die du nennst, muss
  wörtlich in der Eingabe stehen. Zähle nichts selbst ab, rechne nichts aus
  und schätze nichts. Im Zweifel nenne gar keine Zahl.
- Nenne höchstens zwei Vorgänge beispielhaft mit Nummer, und nur solche, die
  den Schwerpunkt des Tages tragen. Zähle nicht alles auf; der Leser sieht
  die Listen darunter.

Sprache:
- Schlichtes, sachliches Deutsch. Reiner Fließtext.
- Keine Aufzählungszeichen, keine Überschriften, kein Markdown.
- Keine Werbesprache, keine Bewertung der Arbeitsleistung.
- Technische Begriffe und Bezeichner bleiben in ihrer Originalform.
"""


def truncate(text: str, limit: int) -> str:
    text = text.strip()
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + " […]"


def build_summary_prompt(data: dict) -> str:
    """Baut den Prompt entlang der Epic-Gliederung.

    Die Struktur wird hier festgelegt und nicht dem Modell überlassen, damit
    die Gliederung von Tag zu Tag gleich bleibt und die Fortschrittszahlen aus
    den Daten stammen statt aus einer Schätzung.
    """
    lines: list[str] = [f"Datum: {german_date(Date.fromisoformat(data['date']))}", ""]

    def eintraege(titel: str, items: list[dict], *, body_limit: int) -> None:
        if not items:
            return
        lines.append(f"{titel}:")
        for item in items[:PROMPT_MAX_ITEMS]:
            lines.append(f"- #{item['number']} {item['title']}")
            if body_limit and item.get("body"):
                lines.append(f"  {truncate(item['body'], body_limit)}")
        if len(items) > PROMPT_MAX_ITEMS:
            lines.append(f"- … und {len(items) - PROMPT_MAX_ITEMS} weitere")
        lines.append("")

    def kennzahlen(gruppe: dict) -> str:
        return (
            f"Heute: {len(gruppe.get('opened', []))} neu angelegt, "
            f"{len(gruppe.get('closed', []))} abgeschlossen, "
            f"{len(gruppe.get('merged', []))} gemergt."
        )

    for epic in data.get("epics", []):
        lines.append(f"## Epic #{epic['number']}: {epic['title']}")
        lines.append(
            f"Gesamtfortschritt: {epic['tickets_closed']} von "
            f"{epic['tickets_total']} Tickets erledigt."
        )
        lines.append(kennzahlen(epic))
        lines.append("")
        eintraege("Heute neu angelegt", epic["opened"], body_limit=300)
        eintraege("Heute abgeschlossen", epic["closed"], body_limit=300)
        eintraege("Heute gemergt", epic["merged"], body_limit=500)

    ohne = data.get("ohne_epic") or {}
    if any(ohne.get(k) for k in ("opened", "closed", "merged")):
        lines.append("## Ohne Epic-Bezug")
        lines.append(kennzahlen(ohne))
        lines.append("")
        eintraege("Heute neu angelegt", ohne.get("opened", []), body_limit=300)
        eintraege("Heute abgeschlossen", ohne.get("closed", []), body_limit=300)
        eintraege("Heute gemergt", ohne.get("merged", []), body_limit=500)

    return "\n".join(lines)


ANTHROPIC_VERSION = "2023-06-01"
DEFAULT_MODELS = {
    "anthropic": "claude-haiku-4-5-20251001",
    "openai": "gpt-4o",
}
DEFAULT_BASE_URLS = {
    "anthropic": "https://api.anthropic.com",
    "openai": "https://api.openai.com",
}


def detect_provider(api_key: str) -> str:
    """Bestimmt den Anbieter, vorrangig aus der Konfiguration.

    Ohne gesetzte Variable entscheidet das Präfix des Schlüssels. Anthropic
    vergibt Schlüssel mit `sk-ant-`, alles andere wird als OpenAI-kompatibel
    behandelt.
    """
    configured = os.environ.get("OPAA_REPORT_PROVIDER", "").strip().lower()
    if configured in DEFAULT_MODELS:
        return configured
    if configured:
        print(
            f"Unbekannter Anbieter '{configured}' — erkenne anhand des Schlüssels.",
            file=sys.stderr,
        )
    return "anthropic" if api_key.startswith("sk-ant-") else "openai"


def build_request(provider: str, api_key: str, base_url: str, model: str, prompt: str):
    """Baut die Anfrage im Format des jeweiligen Anbieters."""
    if provider == "anthropic":
        payload = {
            "model": model,
            "max_tokens": 900,
            "temperature": 0.3,
            "system": SUMMARY_SYSTEM_PROMPT,
            "messages": [{"role": "user", "content": prompt}],
        }
        headers = {
            "x-api-key": api_key,
            "anthropic-version": ANTHROPIC_VERSION,
            "content-type": "application/json",
        }
        path = "/v1/messages"
    else:
        payload = {
            "model": model,
            "max_tokens": 900,
            "temperature": 0.3,
            "messages": [
                {"role": "system", "content": SUMMARY_SYSTEM_PROMPT},
                {"role": "user", "content": prompt},
            ],
        }
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }
        path = "/v1/chat/completions"

    return urllib.request.Request(
        f"{base_url}{path}",
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
    )


def extract_text(provider: str, body: dict) -> str:
    """Liest den Antworttext aus der Struktur des jeweiligen Anbieters."""
    if provider == "anthropic":
        return body["content"][0]["text"].strip()
    return body["choices"][0]["message"]["content"].strip()


def summarize(data: dict) -> str:
    """Erzeugt die Zusammenfassung. Bei jedem Fehler bleibt sie leer."""
    api_key = os.environ.get("OPAA_REPORT_API_KEY", "").strip()
    if not api_key:
        print("Kein API-Schlüssel gesetzt — Report ohne Zusammenfassung.", file=sys.stderr)
        return ""

    provider = detect_provider(api_key)
    # Nicht gesetzte Repository-Variablen erreichen den Prozess als leerer
    # String, nicht als fehlender Eintrag. Der Vorgabewert von `get` würde
    # deshalb nie greifen.
    model = os.environ.get("OPAA_REPORT_MODEL", "").strip() or DEFAULT_MODELS[provider]
    base_url = (
        os.environ.get("OPAA_REPORT_BASE_URL", "").strip() or DEFAULT_BASE_URLS[provider]
    ).rstrip("/")

    print(f"Zusammenfassung über {provider}, Modell {model}.", file=sys.stderr)
    request = build_request(
        provider, api_key, base_url, model, build_summary_prompt(data)
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            body = json.loads(response.read().decode("utf-8"))
        return extract_text(provider, body)
    except urllib.error.HTTPError as error:
        # Der Fehlertext des Anbieters nennt die Ursache, etwa ein unbekanntes
        # Modell oder einen abgelaufenen Schlüssel. Er enthält den Schlüssel
        # selbst nicht und kann daher protokolliert werden.
        detail = error.read().decode("utf-8", errors="replace")[:400]
        print(f"Zusammenfassung fehlgeschlagen ({error.code}): {detail}", file=sys.stderr)
        return ""
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
