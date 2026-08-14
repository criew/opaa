"""Tests für die Aufbereitung des Tagesreports.

Geprüft wird, was ohne GitHub und ohne Modellaufruf prüfbar ist: das Lesen
der Stichpunkte aus der Antwort des Modells und das Rendern der Seite. Die
Datenerhebung selbst bleibt außen vor — sie besteht aus API-Aufrufen.

Aufruf aus dem Repositorywurzelverzeichnis:
    pytest .github/scripts/test_daily_report.py
"""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

import daily_report as dr  # noqa: E402


TEST_URL = "https://opaa.example/chat"


@pytest.fixture
def report() -> dict:
    """Ein Report mit zwei Epics, einem Rest und einem grünen CI-Lauf."""
    return {
        "repo": "criew/opaa",
        "date": "2026-08-03",
        "generated_at": "2026-08-04T09:16:00+02:00",
        "timezone": "Europe/Berlin",
        "timezone_fallback": False,
        "window_start": "2026-08-03T00:00:00+02:00",
        "window_end": "2026-08-03T23:59:59+02:00",
        "closed_issues": [_issue(201), _issue(227)],
        "opened_issues": [_issue(310)],
        "merged_pull_requests": [_pull_request(305)],
        "open_pull_requests": [],
        "open_issues_total": 80,
        "ci": {
            "conclusion": "success",
            "url": "https://github.com/criew/opaa/actions/runs/1",
            "title": "Letzter Lauf",
            "finished_at": "",
        },
        "highlights": {},
        "epics": [
            {
                "number": 198,
                "title": "Space and asset model",
                "tickets_total": 25,
                "tickets_closed": 5,
                "opened": [],
                "closed": [_issue(201)],
                "merged": [_pull_request(305)],
            },
            {
                "number": 224,
                "title": "Suchqualität messbar machen",
                "tickets_total": 14,
                "tickets_closed": 6,
                "opened": [],
                "closed": [_issue(227)],
                "merged": [],
            },
        ],
        "ohne_epic": {"opened": [_issue(310)], "closed": [], "merged": []},
    }


def _issue(nummer: int) -> dict:
    return {
        "number": nummer,
        "title": f"Titel {nummer}",
        "url": f"https://github.com/criew/opaa/issues/{nummer}",
        "author": "criew",
        "labels": [],
        "body": "",
        "closes": [],
    }


def _pull_request(nummer: int) -> dict:
    return {
        **_issue(nummer),
        "url": f"https://github.com/criew/opaa/pull/{nummer}",
        "additions": 100,
        "deletions": 20,
        "changed_files": 3,
    }


# --------------------------------------------------------------------------
# Stichpunkte aus der Antwort des Modells
# --------------------------------------------------------------------------

ANTWORT = json.dumps(
    {
        "198": [{"nummer": 201, "text": "Wissensbibliothek eingeführt"}],
        "sonstiges": [{"nummer": None, "text": "Dokumentation ergänzt"}],
    }
)


def test_stichpunkte_werden_gelesen():
    punkte = dr.parse_highlights(ANTWORT)
    assert punkte["198"] == [{"nummer": 201, "text": "Wissensbibliothek eingeführt"}]
    assert punkte["sonstiges"] == [{"nummer": None, "text": "Dokumentation ergänzt"}]


@pytest.mark.parametrize(
    "umhuellung",
    [
        "Hier die Stichpunkte:\n{antwort}",
        "```json\n{antwort}\n```",
        "```\n{antwort}\n```\nViel Erfolg!",
    ],
    ids=["vorrede", "codeblock-json", "codeblock-nachwort"],
)
def test_stichpunkte_trotz_umhuellung(umhuellung):
    """Modelle halten sich nicht immer daran, nur JSON zu liefern."""
    assert dr.parse_highlights(umhuellung.format(antwort=ANTWORT)) == dr.parse_highlights(
        ANTWORT
    )


@pytest.mark.parametrize(
    "antwort",
    ["{kaputt,,,", "Tut mir leid, das kann ich nicht.", "", "[]", "null", "{}"],
)
def test_unbrauchbare_antwort_ergibt_keine_stichpunkte(antwort):
    assert dr.parse_highlights(antwort) == {}


def test_unbrauchbare_eintraege_werden_uebergangen():
    """Ein einzelner unpassender Eintrag darf den Abschnitt nicht verwerfen."""
    punkte = dr.parse_highlights(
        json.dumps(
            {
                "198": ["Nur Text", {"text": ""}, 42, {"nummer": 1, "text": "Gut"}],
                "224": "kein Array",
            }
        )
    )
    assert punkte["198"] == [
        {"nummer": None, "text": "Nur Text"},
        {"nummer": 1, "text": "Gut"},
    ]
    assert "224" not in punkte


@pytest.mark.parametrize(
    ("wert", "erwartet"),
    [(201, 201), ("201", 201), ("#201", 201), (" 42 ", 42), (None, None), (True, None), ("abc", None), (3.5, None)],
)
def test_nummern_werden_nachsichtig_gelesen(wert, erwartet):
    assert dr.als_nummer(wert) == erwartet


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------


def test_report_zeigt_stichpunkte_des_modells(report):
    report["highlights"] = {"198": [{"nummer": 201, "text": "Bibliothek eingeführt"}]}
    seite = dr.render_report(report, TEST_URL)
    assert "Bibliothek eingeführt" in seite
    # Die Nummer im Stichpunkt wird verlinkt.
    assert 'href="https://github.com/criew/opaa/issues/201">#201</a>' in seite


def test_report_faellt_ohne_stichpunkte_auf_titel_zurueck(report):
    """Ohne Zusammenfassung bleibt der Abschnitt nicht leer."""
    seite = dr.render_report(report, TEST_URL)
    assert "Titel 201" in seite
    assert "Titel 227" in seite


def test_stichpunkte_nur_fuer_ein_epic_lassen_andere_zurueckfallen(report):
    report["highlights"] = {"198": [{"nummer": None, "text": "Eigener Stichpunkt"}]}
    seite = dr.render_report(report, TEST_URL)
    assert "Eigener Stichpunkt" in seite
    assert "Titel 227" in seite


def test_report_zeigt_kennzahlen(report):
    seite = dr.render_report(report, TEST_URL)
    for wert, label in (("80", "Issues offen"), ("+1", "Neu angelegt"), ("2", "Abgeschlossen")):
        assert wert in seite and label in seite


def test_fehlende_gesamtzahl_wird_ausgewiesen(report):
    """Scheitert die Abfrage, steht ein Platzhalter statt einer falschen Zahl."""
    report["open_issues_total"] = None
    assert "—</div>" in dr.render_report(report, TEST_URL)


def test_report_zeigt_keine_detaillisten(report):
    seite = dr.render_report(report, TEST_URL)
    assert 'class="items"' not in seite
    assert "Offen zum Tagesende" not in seite


def test_linkleiste_enthaelt_alle_einstiege(report):
    seite = dr.render_report(report, TEST_URL)
    for eintrag in ("Testumgebung", "Repository", "Issues", "Pull Requests", "CI"):
        assert f">{eintrag}" in seite or f"{eintrag} <span" in seite
    assert TEST_URL in seite


def test_ci_punkt_ist_gruen_bei_erfolg(report):
    assert 'dot green' in dr.render_report(report, TEST_URL)


def test_ci_punkt_ist_rot_bei_fehlschlag(report):
    report["ci"]["conclusion"] = "failure"
    seite = dr.render_report(report, TEST_URL)
    assert "dot red" in seite
    # Der Zustand steht im Klartext im Tooltip, nicht nur in der Farbe.
    assert "failure" in seite


def test_fehlender_ci_lauf_bricht_nicht_ab(report):
    report["ci"] = None
    assert "Kein CI-Lauf gefunden" in dr.render_report(report, TEST_URL)


def test_ohne_testumgebung_entfaellt_der_link(report):
    assert "Testumgebung" not in dr.render_report(report, "")


def test_fortschritt_ohne_tickets_bricht_nicht_ab(report):
    """Ein Epic ohne Ticketliste darf keine Division durch null auslösen."""
    report["epics"] = [
        {
            "number": 9,
            "title": "Leer",
            "tickets_total": 0,
            "tickets_closed": 0,
            "opened": [],
            "closed": [],
            "merged": [],
        }
    ]
    assert "width: 0%" in dr.render_report(report, TEST_URL)


def test_tag_ohne_bewegung(report):
    report["epics"] = []
    report["ohne_epic"] = {"opened": [], "closed": [], "merged": []}
    assert "Keine Bewegung an diesem Tag" in dr.render_report(report, TEST_URL)


def test_altdaten_werden_im_neuen_layout_gerendert(report):
    """Reports von vor der Umstellung kennen die neuen Felder nicht."""
    alt = copy.deepcopy(report)
    del alt["highlights"]
    del alt["open_issues_total"]
    alt["summary"] = "Fließtext aus der alten Fassung."

    seite = dr.render_report(alt, TEST_URL)
    assert "—</div>" in seite
    assert seite.count('class="epic-section"') == 3
    assert "Titel 201" in seite


# --------------------------------------------------------------------------
# Feed
# --------------------------------------------------------------------------


def test_feed_nutzt_die_stichpunkte(report):
    report["highlights"] = {"198": [{"nummer": None, "text": "Bibliothek eingeführt"}]}
    assert "Bibliothek eingeführt" in dr.feed_text(report)


def test_feed_faellt_auf_fliesstext_alter_reports_zurueck(report):
    """Ein alter Report ohne Tagesbewegung verstummt im Feed nicht."""
    alt = copy.deepcopy(report)
    alt["epics"] = []
    alt["ohne_epic"] = {"opened": [], "closed": [], "merged": []}
    alt["highlights"] = {}
    alt["summary"] = "Fließtext aus der alten Fassung."
    assert dr.feed_text(alt) == "Fließtext aus der alten Fassung."
