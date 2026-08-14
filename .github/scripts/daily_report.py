#!/usr/bin/env python3
"""Erzeugt den täglichen Projektreport als HTML-Seite und Atom-Feed.

Das Skript sammelt über die GitHub-API, was an einem Tag im Repository
passiert ist, lässt daraus optional Stichpunkte schreiben und legt das
Ergebnis im Ausgabeverzeichnis ab. Der Report ist eine Management Summary:
Kennzahlen, Fortschritt je Epic, Sonstiges — keine vollständigen Listen.

Aus den gespeicherten Rohdaten aller bisherigen Tage werden sämtliche Seiten
jedes Mal neu erzeugt, damit sich Layoutänderungen rückwirkend auf alle
Reports auswirken.

Die Seiten liegen unterhalb von report/ auf der veröffentlichten Seite; im
Wurzelverzeichnis steht die Landing Page aus page/ (siehe page/README.md).

Aufruf:
    daily_report.py --repo criew/opaa --date 2026-08-01 --output site/report

Benötigt die GitHub-CLI (`gh`) mit gültigem Token. Ein API-Schlüssel für die
Zusammenfassung ist optional; fehlt er, treten die Titel der Vorgänge an die
Stelle der Stichpunkte. Der Schlüssel wird bewusst aus OPAA_REPORT_API_KEY
gelesen und nicht aus dem Anwendungsschlüssel OPAA_OPENAI_API_KEY, damit sich
das Aktivieren der Zusammenfassung nicht auf die Integrationstests in der CI
auswirkt.

Die Tests liegen daneben in test_daily_report.py und laufen in der CI.
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
# Beim Rückfall auf UTC verschieben sich die Tagesgrenzen. Das wird im Report
# ausgewiesen, damit ein solcher Lauf nicht unbemerkt bleibt.
TIMEZONE_FALLBACK = str(TIMEZONE) != "Europe/Berlin"
UTC = timezone.utc
SEARCH_PAGE_SIZE = 100
SEARCH_MAX_PAGES = 10
# Obergrenze je Abschnitt im Prompt. An Tagen mit sehr vielen Issues bleibt der
# Aufruf so bezahlbar, ohne dass der Report selbst gekürzt wird.
PROMPT_MAX_ITEMS = 25
# Ziel des Links auf die laufende Instanz. Überschreibbar, damit ein Fork oder
# eine verschobene Umgebung keine Codeänderung erfordert.
DEFAULT_TEST_URL = "https://opaa.ewerlin.com/chat"

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
    """Liefert das halboffene Zeitfenster eines Tages mit Zeitzone.

    Die Suche der GitHub-API rechnet ohne Offset in UTC. Für einen Report, der
    sich an der lokalen Arbeitszeit orientiert, muss der Offset mitgegeben
    werden, sonst wandern Abendereignisse in den Folgetag.

    Beide Grenzen gehören zum Fenster. Ein halboffenes Fenster wäre sauberer,
    ist über die Suche aber nicht ausdrückbar: Zwei Bereichsangaben zum selben
    Feld verknüpft GitHub nicht, die zweite verdrängt die erste. Eine Abfrage
    `merged:>=A merged:<B` liefert daher alles vor B statt des Tages. Nur der
    Bereichsoperator `A..B` grenzt korrekt ein. Da die Zeitstempel der API
    sekundengenau sind, entsteht zum Folgetag dennoch keine Lücke.
    """
    start = datetime.combine(day, datetime.min.time(), tzinfo=TIMEZONE)
    ende = start + timedelta(days=1) - timedelta(seconds=1)
    return start.isoformat(), ende.isoformat()


def zeitraum_qualifier(feld: str, start: str, ende: str) -> str:
    """Baut den Suchausdruck für das Zeitfenster eines Tages."""
    return f"{feld}:{start}..{ende}"


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


def count_issues(repo: str, qualifier: str) -> int | None:
    """Zählt Treffer einer Suche, ohne sie zu holen.

    Für die Kennzahl der offenen Issues genügt die Gesamtzahl; die Einträge
    selbst werden nicht gebraucht. Bei einem Fehler bleibt die Kachel leer,
    statt den Report scheitern zu lassen.
    """
    query = urllib.parse.quote(f"repo:{repo} {qualifier}")
    try:
        response = gh_api(f"search/issues?q={query}&per_page=1")
    except RuntimeError as error:
        print(f"Anzahl offener Issues nicht ermittelbar: {error}", file=sys.stderr)
        return None
    return response.get("total_count")


# Rückfall für Epics, deren Tickets noch nicht als Sub-Issues eingetragen sind:
# Ticketlisten folgen der früheren Vorlage "- [ ] #123 titel", die Nummer muss
# unmittelbar auf die Checkbox folgen. Epic #60 nummeriert seine Befunde dagegen
# als "- [ ] **#1 CORS Wildcard Headers**" — solche Marker sind keine
# Issue-Referenzen und werden durch diese Bedingung ausgeschlossen.
TICKET_MUSTER = re.compile(r"^\s*[-*]\s*\[[ xX]\]\s*#(\d+)\b", re.MULTILINE)

# Ein Epic mit mehr Kindern gibt es nicht; GitHub begrenzt Sub-Issues auf 100.
SUB_ISSUE_LIMIT = 100


def simplify_issue(item: dict) -> dict:
    return {
        "number": item["number"],
        "title": item["title"],
        "url": item["html_url"],
        "author": (item.get("user") or {}).get("login", ""),
        "labels": [label["name"] for label in item.get("labels", [])],
        "body": (item.get("body") or "").strip(),
        # Wird für Pull Requests aus der von GitHub gepflegten Verknüpfung
        # nachgetragen, siehe `add_closing_references`.
        "closes": [],
    }


def gh_graphql(repo: str, felder: str) -> dict:
    """Führt eine GraphQL-Abfrage auf dem Repository aus.

    Erwartet die Auswahl innerhalb von `repository` und gibt deren Ergebnis
    zurück. Mehrere Knoten werden über Aliasse in einer einzigen Abfrage
    geholt, statt je Vorgang einmal anzufragen.
    """
    besitzer, name = repo.split("/", 1)
    query = f'{{ repository(owner: "{besitzer}", name: "{name}") {{\n{felder}\n}} }}'
    ergebnis = subprocess.run(
        ["gh", "api", "graphql", "-f", f"query={query}"],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if ergebnis.returncode != 0:
        raise RuntimeError(f"GraphQL-Abfrage fehlgeschlagen: {ergebnis.stderr.strip()}")
    antwort = json.loads(ergebnis.stdout)
    if antwort.get("errors"):
        meldungen = "; ".join(
            fehler.get("message", "") for fehler in antwort["errors"]
        )
        raise RuntimeError(f"GraphQL-Abfrage fehlgeschlagen: {meldungen}")
    return (antwort.get("data") or {}).get("repository") or {}


def fetch_sub_issues(repo: str, epic_nummern: list[int]) -> dict[int, list[int]]:
    """Holt die Sub-Issues der Epics als Nummernlisten.

    Sub-Issues sind die von GitHub selbst gepflegte Eltern-Kind-Beziehung und
    damit die verlässliche Zuordnung. Kinder aus anderen Repositories werden
    verworfen: Ihre Nummer wäre im Kontext dieses Repositories mehrdeutig.

    Schlägt die Abfrage fehl, ist das Ergebnis leer und der Aufrufer fällt auf
    die Ticketlisten in den Epic-Bodies zurück.
    """
    if not epic_nummern:
        return {}

    felder = "\n".join(
        f"    e{nummer}: issue(number: {nummer}) "
        f"{{ subIssues(first: {SUB_ISSUE_LIMIT}) "
        "{ nodes { number repository { nameWithOwner } } } }"
        for nummer in epic_nummern
    )
    try:
        daten = gh_graphql(repo, felder)
    except (RuntimeError, json.JSONDecodeError, ValueError) as error:
        print(
            f"Sub-Issues nicht abrufbar, weiche auf die Ticketlisten aus: {error}",
            file=sys.stderr,
        )
        return {}

    kinder: dict[int, list[int]] = {}
    for nummer in epic_nummern:
        knoten = ((daten.get(f"e{nummer}") or {}).get("subIssues") or {}).get(
            "nodes", []
        )
        kinder[nummer] = [
            eintrag["number"]
            for eintrag in knoten
            if (eintrag.get("repository") or {}).get("nameWithOwner") == repo
        ]
    return kinder


def add_closing_references(repo: str, pull_requests: list[dict]) -> None:
    """Trägt die von GitHub verknüpften Issues in die Pull Requests ein.

    Ein Ausdruck auf `Closes #N` im Body ist dafür untauglich: PR-Beschreibungen
    enthalten solche Zeichenfolgen auch als Beispiel oder Zitat. GitHub pflegt
    die tatsächliche Verknüpfung selbst; sie wird hier in einer einzigen Abfrage
    für alle Pull Requests des Tages geholt.
    """
    if not pull_requests:
        return

    felder = "\n".join(
        f'    p{pr["number"]}: pullRequest(number: {pr["number"]}) '
        "{ closingIssuesReferences(first: 20) { nodes { number } } }"
        for pr in pull_requests
    )

    try:
        daten = gh_graphql(repo, felder)
    except (RuntimeError, json.JSONDecodeError, ValueError) as error:
        print(f"Verknüpfte Issues nicht abrufbar: {error}", file=sys.stderr)
        return

    for pull_request in pull_requests:
        knoten = (daten.get(f"p{pull_request['number']}") or {}).get(
            "closingIssuesReferences"
        ) or {}
        pull_request["closes"] = sorted(
            eintrag["number"] for eintrag in knoten.get("nodes", [])
        )


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
    """Erhebt die Epics samt Tickets und Fortschritt.

    Maßgeblich sind die nativen Sub-Issues eines Epics. Nur wenn ein Epic
    keine hat, wird auf die Ticketliste in seinem Body zurückgegriffen — so
    fällt während der Migration eines Epics kein Reporttag aus.

    Der Status aller Tickets wird über einen einzigen Abruf aller Issues
    ermittelt statt über eine Abfrage je Ticket.
    """
    try:
        alle = gh_api(
            f"repos/{repo}/issues?state=all&per_page=100&labels=", paginate=True
        )
    except RuntimeError as error:
        print(f"Epics konnten nicht erhoben werden: {error}", file=sys.stderr)
        return []

    # Die Issue-Liste enthält auch Pull Requests; diese sind keine Tickets.
    nur_issues = [
        item
        for item in alle
        if isinstance(item, dict) and "number" in item and "pull_request" not in item
    ]
    status_je_nummer = {item["number"]: item.get("state", "open") for item in nur_issues}
    bekannte_issues = set(status_je_nummer)
    epic_nummern = {
        item["number"]
        for item in nur_issues
        if "epic" in {label["name"] for label in item.get("labels", [])}
    }

    sub_issues = fetch_sub_issues(repo, sorted(epic_nummern))

    epics: list[dict] = []
    for item in nur_issues:
        labels = {label["name"] for label in item.get("labels", [])}
        if "epic" not in labels:
            continue

        def gueltig(nummern: list[int]) -> list[int]:
            # Ein Epic ist kein Ticket eines anderen Epics, und eine Nummer
            # ohne zugehöriges Issue ist ein Aufzählungsmarker.
            return sorted(
                {
                    nummer
                    for nummer in nummern
                    if nummer != item["number"]
                    and nummer in bekannte_issues
                    and nummer not in epic_nummern
                }
            )

        tickets = gueltig(sub_issues.get(item["number"], []))
        if not tickets:
            tickets = gueltig(
                [int(nummer) for nummer in TICKET_MUSTER.findall(item.get("body") or "")]
            )
            if tickets:
                print(
                    f"Epic #{item['number']} hat keine Sub-Issues — verwende die "
                    "Ticketliste aus dem Body.",
                    file=sys.stderr,
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

    closed_issues = [
        simplify_issue(item)
        for item in search_issues(
            repo, "is:issue is:closed " + zeitraum_qualifier("closed", start, end)
        )
    ]
    opened_issues = [
        simplify_issue(item)
        for item in search_issues(
            repo, "is:issue " + zeitraum_qualifier("created", start, end)
        )
    ]
    merged = [
        simplify_issue(item)
        for item in search_issues(
            repo, "is:pr is:merged " + zeitraum_qualifier("merged", start, end)
        )
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
        # Macht nachprüfbar, gegen welche Grenzen abgefragt wurde — besonders
        # für Vorgänge kurz vor Mitternacht.
        "timezone": str(TIMEZONE),
        "timezone_fallback": TIMEZONE_FALLBACK,
        "window_start": start,
        "window_end": end,
        "closed_issues": closed_issues,
        "opened_issues": opened_issues,
        "merged_pull_requests": merged,
        "open_pull_requests": open_pulls,
        # Bestandsgröße, nicht Tagesbewegung: der Stand zum Zeitpunkt des Laufs.
        "open_issues_total": count_issues(repo, "is:issue is:open"),
        "ci": ci_status(repo),
        # Stichpunkte je Epic, siehe `summarize`. Leer, wenn keine
        # Zusammenfassung erzeugt werden konnte.
        "highlights": {},
    }
    add_closing_references(repo, merged)
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
Du schreibst die Stichpunkte eines Tagesreports für ein Softwareprojekt.

Die Eingabe ist nach Epics gegliedert. Ein Epic bündelt thematisch
zusammenhängende Arbeit. Übernimm diese Gliederung unverändert.

Antworte ausschließlich mit einem JSON-Objekt in genau dieser Form:

{
  "198": [
    {"nummer": 201, "text": "Knowledge Library umgesetzt"},
    {"nummer": null, "text": "Schema für Assets festgelegt"}
  ],
  "sonstiges": [
    {"nummer": 293, "text": "Race Condition bei der Anmeldung behoben"}
  ]
}

Regeln für die Struktur:
- Ein Schlüssel je Epic, und zwar dessen Nummer als Zeichenkette. Genau die
  Epics aus der Eingabe, keines auslassen, keines hinzuerfinden.
- Der Schlüssel "sonstiges" nur, wenn die Eingabe einen solchen Abschnitt hat.
- Je Abschnitt höchstens vier Stichpunkte.
- "nummer" ist die Nummer des Vorgangs, um den es geht, oder null, wenn sich
  der Stichpunkt auf mehrere bezieht. Verwende nur Nummern, die im jeweiligen
  Abschnitt der Eingabe stehen.
- Kein Text vor oder nach dem JSON, keine Code-Blöcke, keine Erläuterung.

Regeln für die Stichpunkte:
- Ein Stichpunkt sagt, was fachlich geschehen ist — nicht, dass ein Ticket
  bewegt wurde. "Gruppen als Rechtesubjekte eingeführt", nicht "#200 wurde
  geschlossen".
- Höchstens 15 Wörter. Kein Satzzeichen am Ende.
- Fasse zusammen, was zusammengehört, statt jeden Vorgang einzeln zu nennen.
- Nenne keine Zahlen, die du selbst abgezählt oder ausgerechnet hast. Der
  Fortschritt steht bereits neben der Überschrift.
- Schlichtes, sachliches Deutsch. Keine Werbesprache, keine Bewertung der
  Arbeitsleistung. Technische Begriffe und Bezeichner bleiben unverändert.
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
        # Die Überschrift nennt den JSON-Schlüssel, unter dem die Stichpunkte
        # zu diesem Abschnitt erwartet werden.
        lines.append(f'## Schlüssel "{epic["number"]}" — Epic #{epic["number"]}: {epic["title"]}')
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
        lines.append('## Schlüssel "sonstiges" — ohne Epic-Bezug')
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


def als_nummer(wert: object) -> int | None:
    """Liest eine Vorgangsnummer, auch wenn sie als Zeichenkette kommt.

    Modelle geben die Nummer je nach Laune als Zahl, als "201" oder als
    "#201" zurück. Ohne diese Umwandlung verlöre der Stichpunkt seinen Link.
    """
    if isinstance(wert, bool):
        return None
    if isinstance(wert, int):
        return wert
    if isinstance(wert, str):
        ziffern = wert.strip().lstrip("#")
        if ziffern.isdigit():
            return int(ziffern)
    return None


def parse_highlights(text: str) -> dict[str, list[dict]]:
    """Liest die Stichpunkte aus der Antwort des Modells.

    Trotz der Anweisung, nur JSON zu liefern, umschließen Modelle die Antwort
    gelegentlich mit einem Code-Block oder einem einleitenden Satz. Deshalb
    wird das äußerste Objekt aus dem Text herausgeschnitten. Was sich nicht
    lesen lässt, führt zu einem leeren Ergebnis — der Report entsteht dann mit
    den Titeln der Vorgänge statt mit Stichpunkten.
    """
    anfang, ende = text.find("{"), text.rfind("}")
    if anfang < 0 or ende <= anfang:
        print("Antwort enthält kein JSON-Objekt.", file=sys.stderr)
        return {}
    try:
        roh = json.loads(text[anfang : ende + 1])
    except json.JSONDecodeError as error:
        print(f"Stichpunkte nicht lesbar: {error}", file=sys.stderr)
        return {}
    if not isinstance(roh, dict):
        return {}

    ergebnis: dict[str, list[dict]] = {}
    for schluessel, eintraege in roh.items():
        if not isinstance(eintraege, list):
            continue
        punkte: list[dict] = []
        for eintrag in eintraege:
            if isinstance(eintrag, str):
                eintrag = {"nummer": None, "text": eintrag}
            if not isinstance(eintrag, dict):
                continue
            text_wert = str(eintrag.get("text", "")).strip()
            if not text_wert:
                continue
            punkte.append({"nummer": als_nummer(eintrag.get("nummer")), "text": text_wert})
        if punkte:
            ergebnis[str(schluessel)] = punkte
    return ergebnis


def summarize(data: dict) -> dict[str, list[dict]]:
    """Erzeugt die Stichpunkte je Abschnitt. Bei jedem Fehler bleiben sie leer."""
    api_key = os.environ.get("OPAA_REPORT_API_KEY", "").strip()
    if not api_key:
        print("Kein API-Schlüssel gesetzt — Report ohne Stichpunkte.", file=sys.stderr)
        return {}

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
        return parse_highlights(extract_text(provider, body))
    except urllib.error.HTTPError as error:
        # Der Fehlertext des Anbieters nennt die Ursache, etwa ein unbekanntes
        # Modell oder einen abgelaufenen Schlüssel. Er enthält den Schlüssel
        # selbst nicht und kann daher protokolliert werden.
        detail = error.read().decode("utf-8", errors="replace")[:400]
        print(f"Zusammenfassung fehlgeschlagen ({error.code}): {detail}", file=sys.stderr)
        return {}
    except (urllib.error.URLError, KeyError, IndexError, TimeoutError) as error:
        print(f"Zusammenfassung fehlgeschlagen: {error}", file=sys.stderr)
        return {}


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
  --green: #1a7f37;
  --red: #cf222e;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #14161a;
    --fg: #e6e8eb;
    --muted: #9aa1ab;
    --line: #2a2e35;
    --accent: #6ea0ff;
    --card: #1b1e24;
    --green: #3fb950;
    --red: #f85149;
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
.num { font-variant-numeric: tabular-nums; color: var(--muted); margin-right: .4rem; }
.empty { color: var(--muted); font-style: italic; }

.blaettern { color: var(--muted); font-size: .9rem; }
.blaettern a { text-decoration: none; }
.blaettern a:hover { text-decoration: underline; }

.linkbar { display: flex; gap: .5rem; flex-wrap: wrap; margin-top: .85rem; }
.linkbar a {
  display: inline-flex;
  align-items: center;
  gap: .35rem;
  font-size: .85rem;
  padding: .3rem .7rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--card);
  text-decoration: none;
}
.linkbar a:hover { border-color: var(--accent); }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot.green { background: var(--green); }
.dot.red { background: var(--red); }

.kpi-row { display: flex; gap: .75rem; flex-wrap: wrap; margin-bottom: 1.5rem; }
.kpi {
  flex: 1;
  min-width: 7rem;
  padding: .75rem 1rem;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--card);
  text-align: center;
}
.kpi-value {
  font-size: 1.8rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.kpi-value.green { color: var(--green); }
.kpi-label {
  font-size: .78rem;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: .04em;
}

.epic-section {
  margin-bottom: 1.25rem;
  padding: .85rem 1rem;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--card);
}
.epic-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 1rem;
  margin-bottom: .5rem;
}
.epic-title { font-weight: 600; font-size: .95rem; }
.epic-progress {
  font-size: .8rem;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.progress-bar {
  height: 4px;
  background: var(--line);
  border-radius: 2px;
  margin-bottom: .6rem;
  overflow: hidden;
}
.progress-fill { height: 100%; background: var(--accent); border-radius: 2px; }
.epic-section ul { margin: 0; padding-left: 1.25rem; font-size: .9rem; }
.epic-section li { margin-bottom: .25rem; }

.merged-summary {
  margin-top: .5rem;
  padding-top: .5rem;
  border-top: 1px dashed var(--line);
  font-size: .85rem;
  color: var(--muted);
}
.merged-summary ul { margin: .25rem 0 0; padding-left: 1.25rem; }
.merged-summary li { margin-bottom: .2rem; }

ul.items { list-style: none; margin: 0; padding: 0; }
ul.items li {
  padding: .7rem .9rem;
  border: 1px solid var(--line);
  border-radius: 8px;
  margin-bottom: .5rem;
  background: var(--card);
}
.meta { display: block; color: var(--muted); font-size: .82rem; margin-top: .25rem; }

footer { margin-top: 3rem; padding-top: 1.25rem; border-top: 1px solid var(--line); color: var(--muted); font-size: .85rem; }
.status.bad { color: var(--red); font-weight: 600; }
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


def render_linkbar(repo: str, ci: dict | None, test_url: str) -> str:
    """Baut die Leiste mit den Einstiegspunkten ins Projekt.

    Der CI-Eintrag trägt den Zustand als farbigen Punkt statt als Wort, damit
    die Leiste einzeilig bleibt. Der Tooltip nennt den Zustand im Klartext,
    da Farbe allein ihn nicht zugänglich macht.
    """
    basis = f"https://github.com/{html.escape(repo)}"
    eintraege = [
        f'<a href="{html.escape(test_url)}">Testumgebung</a>' if test_url else "",
        f'<a href="{basis}">Repository</a>',
        f'<a href="{basis}/issues">Issues</a>',
        f'<a href="{basis}/pulls">Pull Requests</a>',
    ]

    if ci:
        ok = ci["conclusion"] == "success"
        zustand = "erfolgreich" if ok else ci["conclusion"]
        titel = f"Letzter CI-Lauf auf dem Hauptbranch: {html.escape(zustand)}"
        farbe = "green" if ok else "red"
        ziel = html.escape(ci["url"]) or f"{basis}/actions"
        eintraege.append(
            f'<a href="{ziel}" title="{titel}">CI <span class="dot {farbe}"></span></a>'
        )
    else:
        eintraege.append(
            f'<a href="{basis}/actions" title="Kein CI-Lauf gefunden">CI</a>'
        )

    return f'<div class="linkbar">{"".join(e for e in eintraege if e)}</div>'


def render_kpis(data: dict) -> str:
    """Zeigt die Kennzahlen des Tages neben dem Bestand an offenen Issues."""
    offen = data.get("open_issues_total")
    kacheln = [
        ("—" if offen is None else str(offen), "Issues offen", ""),
        (f'+{len(data["opened_issues"])}', "Neu angelegt", " green"),
        (str(len(data["closed_issues"])), "Abgeschlossen", ""),
        (str(len(data["merged_pull_requests"])), "PRs gemergt", ""),
    ]
    zellen = "".join(
        f'<div class="kpi"><div class="kpi-value{css}">{wert}</div>'
        f'<div class="kpi-label">{label}</div></div>'
        for wert, label, css in kacheln
    )
    return f'<div class="kpi-row">{zellen}</div>'


# Mehr Stichpunkte je Abschnitt überfordern den Überblick, den die Summary
# geben soll. Die Grenze gilt auch für den Rückfall auf die Titel.
MAX_STICHPUNKTE = 4


def fallback_stichpunkte(gruppe: dict) -> list[dict]:
    """Erzeugt Stichpunkte aus den Titeln, wenn keine Zusammenfassung vorliegt.

    Abgeschlossenes steht vor Neuangelegtem: es trägt den Fortschritt des
    Tages. Ohne diesen Rückfall bliebe der Abschnitt leer, sobald der Aufruf
    beim Anbieter scheitert — der Report soll aber auch dann tragen.
    """
    eintraege = gruppe.get("closed", []) + gruppe.get("opened", [])
    return [
        {"nummer": eintrag["number"], "text": eintrag["title"]}
        for eintrag in eintraege[:MAX_STICHPUNKTE]
    ]


def render_stichpunkte(punkte: list[dict], repo: str) -> str:
    if not punkte:
        return ""
    zeilen = []
    for punkt in punkte[:MAX_STICHPUNKTE]:
        nummer = punkt.get("nummer")
        marke = (
            f'<span class="num"><a href="https://github.com/{html.escape(repo)}'
            f'/issues/{nummer}">#{nummer}</a></span>'
            if isinstance(nummer, int)
            else ""
        )
        zeilen.append(f'<li>{marke}{html.escape(punkt["text"])}</li>')
    return f'<ul>{"".join(zeilen)}</ul>'


def render_merged(merged: list[dict]) -> str:
    """Listet die gemergten Pull Requests eines Abschnitts mit Umfang."""
    if not merged:
        return ""
    zeilen = []
    for pull_request in merged:
        umfang = ""
        if pull_request.get("changed_files"):
            anzahl = pull_request["changed_files"]
            wort = "Datei" if anzahl == 1 else "Dateien"
            umfang = (
                f' (+{pull_request["additions"]}/−{pull_request["deletions"]}, '
                f"{anzahl} {wort})"
            )
        zeilen.append(
            f'<li><span class="num">'
            f'<a href="{html.escape(pull_request["url"])}">#{pull_request["number"]}</a>'
            f'</span>{html.escape(pull_request["title"])}{umfang}</li>'
        )
    return (
        '<div class="merged-summary">Gemergte Pull Requests:'
        f'<ul>{"".join(zeilen)}</ul></div>'
    )


def render_epic(epic: dict, highlights: dict, repo: str) -> str:
    """Rendert den Abschnitt eines Epics samt Fortschritt und Stichpunkten."""
    gesamt = epic.get("tickets_total") or 0
    erledigt = epic.get("tickets_closed") or 0
    anteil = round(erledigt / gesamt * 100) if gesamt else 0
    punkte = highlights.get(str(epic["number"])) or fallback_stichpunkte(epic)
    return f"""<div class="epic-section">
<div class="epic-header">
<span class="epic-title"><a href="https://github.com/{html.escape(repo)}/issues/{epic["number"]}">#{epic["number"]}</a> {html.escape(epic["title"])}</span>
<span class="epic-progress">{erledigt} / {gesamt} erledigt</span>
</div>
<div class="progress-bar"><div class="progress-fill" style="width: {anteil}%"></div></div>
{render_stichpunkte(punkte, repo)}
{render_merged(epic.get("merged", []))}
</div>"""


def render_sonstiges(ohne: dict, highlights: dict, repo: str) -> str:
    """Rendert den Abschnitt für alles, was keinem Epic zugeordnet ist."""
    if not any(ohne.get(k) for k in ("opened", "closed", "merged")):
        return ""
    punkte = highlights.get("sonstiges") or fallback_stichpunkte(ohne)
    return f"""<div class="epic-section">
<div class="epic-header"><span class="epic-title">Sonstiges</span></div>
{render_stichpunkte(punkte, repo)}
{render_merged(ohne.get("merged", []))}
</div>"""


def render_zeitraum(data: dict) -> str:
    """Weist das abgefragte Zeitfenster aus.

    Ältere Reports kennen die Felder nicht; dann entfällt der Hinweis.
    """
    start, ende = data.get("window_start"), data.get("window_end")
    if not start or not ende:
        return ""
    zone = html.escape(str(data.get("timezone", "")))
    hinweis = (
        f'<br><span class="empty">Berichtszeitraum: {html.escape(start[:19])} '
        f"bis {html.escape(ende[:19])} ({zone})</span>"
    )
    if data.get("timezone_fallback"):
        hinweis += (
            '<br><span class="status bad">Ohne Zeitzonendatenbank erzeugt — '
            "die Tagesgrenzen können abweichen.</span>"
        )
    return hinweis


def render_blaettern(vorheriger: str | None, naechster: str | None) -> str:
    """Verlinkt die benachbarten Berichtstage.

    Nachbarn sind die im Bestand tatsächlich vorhandenen Tage, nicht die
    Kalendertage: Tage ohne Bewegung erzeugen keinen Report und werden
    übersprungen. Am Rand des Bestands entfällt der jeweilige Link.
    """
    teile: list[str] = []
    if vorheriger:
        tag = Date.fromisoformat(vorheriger)
        teile.append(
            f'<a href="{vorheriger}.html" rel="prev" '
            f'title="{html.escape(german_date(tag))}">← Vorheriger Tag</a>'
        )
    teile.append('<a href="../index.html">Alle Reports</a>')
    if naechster:
        tag = Date.fromisoformat(naechster)
        teile.append(
            f'<a href="{naechster}.html" rel="next" '
            f'title="{html.escape(german_date(tag))}">Nächster Tag →</a>'
        )
    return f'<nav class="blaettern">{" · ".join(teile)}</nav>'


def render_report(
    data: dict,
    test_url: str,
    vorheriger: str | None = None,
    naechster: str | None = None,
) -> str:
    day = Date.fromisoformat(data["date"])
    repo = data["repo"]
    highlights = data.get("highlights") or {}

    abschnitte = [render_epic(epic, highlights, repo) for epic in data.get("epics", [])]
    abschnitte.append(render_sonstiges(data.get("ohne_epic") or {}, highlights, repo))
    inhalt = "\n".join(a for a in abschnitte if a) or (
        '<p class="empty">Keine Bewegung an diesem Tag.</p>'
    )

    body = f"""<header>
<h1>Tagesreport {html.escape(german_date(day))}</h1>
{render_blaettern(vorheriger, naechster)}
{render_linkbar(repo, data.get("ci"), test_url)}
</header>

{render_kpis(data)}

{inhalt}

<footer>
Erzeugt am {html.escape(data["generated_at"][:16].replace("T", " um "))} Uhr ·
<a href="../feed.xml">Feed abonnieren</a>
{render_zeitraum(data)}
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
<a href="../">Projektseite</a> ·
<a href="feed.xml">Feed abonnieren</a> ·
<a href="https://github.com/{html.escape(repo)}">Repository</a></p>
</header>

{listing}

<footer>
Automatisch erzeugt aus Issues und Pull Requests von
<a href="https://github.com/{html.escape(repo)}">{html.escape(repo)}</a>.
</footer>"""
    return page("OPAA — Tagesreport", body, feed_href="feed.xml")


def feed_text(data: dict) -> str:
    """Fasst einen Report für den Feed als Text zusammen.

    Der Feed trägt keine Formatierung, deshalb werden die Stichpunkte aller
    Abschnitte zu Zeilen zusammengezogen. Reports aus der Zeit vor der
    Umstellung führen ihren Fließtext im Feld `summary`; er wird weiter
    genutzt, damit ältere Einträge im Feed nicht verstummen.
    """
    highlights = data.get("highlights") or {}
    zeilen: list[str] = []

    for epic in data.get("epics", []):
        punkte = highlights.get(str(epic["number"])) or fallback_stichpunkte(epic)
        if punkte:
            zeilen.append(f"{epic['title']}:")
            zeilen.extend(f"— {punkt['text']}" for punkt in punkte[:MAX_STICHPUNKTE])

    ohne = data.get("ohne_epic") or {}
    punkte = highlights.get("sonstiges") or fallback_stichpunkte(ohne)
    if punkte:
        zeilen.append("Sonstiges:")
        zeilen.extend(f"— {punkt['text']}" for punkt in punkte[:MAX_STICHPUNKTE])

    if zeilen:
        return "\n".join(zeilen)
    return data.get("summary") or "Für diesen Tag liegt keine Zusammenfassung vor."


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
        content = feed_text(data)
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


def nachbarn(reports: list[dict], i: int) -> tuple[str | None, str | None]:
    """Liefert die Berichtstage vor und nach `reports[i]`.

    Der Bestand liegt neueste zuerst: der ältere Nachbar folgt im Bestand,
    der neuere steht davor. Am Rand fehlt der jeweilige Nachbar.
    """
    vorheriger = reports[i + 1]["date"] if i + 1 < len(reports) else None
    naechster = reports[i - 1]["date"] if i > 0 else None
    return vorheriger, naechster


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
    parser.add_argument(
        "--test-url",
        default="",
        help=(
            "URL der Testumgebung für die Linkleiste. Ersatzweise aus "
            f"OPAA_REPORT_TEST_URL, sonst {DEFAULT_TEST_URL}."
        ),
    )
    args = parser.parse_args()

    test_url = (
        args.test_url.strip()
        or os.environ.get("OPAA_REPORT_TEST_URL", "").strip()
        or DEFAULT_TEST_URL
    )

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
        # Die bestehenden Reports werden trotzdem neu erzeugt, damit
        # Layoutänderungen auch ohne neuen Report wirksam werden.
    else:
        data["highlights"] = summarize(data)
        (data_dir / f"{day.isoformat()}.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"Daten geschrieben: data/{day.isoformat()}.json")

    # Alle Seiten aus den Rohdaten neu erzeugen, nicht nur die des Berichtstags.
    # Eine Layoutänderung wirkt so rückwirkend auf den gesamten Bestand, ohne
    # dass die Daten erneut von GitHub geholt werden müssten.
    reports = load_existing(data_dir)
    for i, bericht in enumerate(reports):
        vorheriger, naechster = nachbarn(reports, i)
        (reports_dir / f"{bericht['date']}.html").write_text(
            render_report(bericht, test_url, vorheriger, naechster), encoding="utf-8"
        )

    site_url = args.site_url.rstrip("/") or f"https://github.com/{args.repo}"
    (output / "index.html").write_text(render_index(reports, args.repo), encoding="utf-8")
    (output / "feed.xml").write_text(
        render_feed(reports, args.repo, site_url), encoding="utf-8"
    )
    (output / ".nojekyll").write_text("", encoding="utf-8")
    print(f"Seiten erzeugt ({len(reports)} Reports).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
