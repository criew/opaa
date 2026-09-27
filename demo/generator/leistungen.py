"""Builds the "Leistungen Meldewesen & Ausweise" (.md + one .csv) and "Leistungen
Kfz-Zulassung" (.md + .txt) documents from the transformed LHM source files.

See docs/features/demo-instance.md, table "Behördenlandschaft, Bibliotheken
und Formate", for the target library/format split.
"""

from __future__ import annotations

import csv
import io
import re

from leistungen_quelle import SELECTED_KFZ, SELECTED_MELDEWESEN, read_raw
from rheinfurt_text import RHEINFURT_PLZ, aktenzeichen, formularnummer, transform_service

CONTACT_LINE = (
    "Kontakt: buergerbuero@stadt-rheinfurt.example | Bürgerbüro Rheinfurt, "
    f"Rathausplatz 1, {RHEINFURT_PLZ} Rheinfurt"
)
SYNTHETIC_NOTICE = (
    "Diese Leistungsbeschreibung ist Teil des synthetischen Demo-Korpus der fiktiven Stadt "
    "Rheinfurt (siehe SOURCE.md im Wurzelverzeichnis dieses Korpus). Alle Namen, Aktenzeichen "
    "und Kontaktangaben sind frei erfunden."
)


def slugify(title: str) -> str:
    text = title.lower()
    text = re.sub(r"[äöüß]", lambda m: {"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"}[m.group()], text)
    text = re.sub(r"[^a-z0-9]+", "-", text).strip("-")
    return text or "leistung"


def render_markdown(title: str, body: str, sachgebiet: str, az: str, formular: str) -> str:
    return (
        f"# {title}\n\n"
        f"**Zuständige Stelle:** Bürgerbüro Rheinfurt – Sachgebiet {sachgebiet}\n"
        f"**Aktenzeichen (Muster):** {az}\n"
        f"**Formular:** {formular}\n\n"
        f"{body}\n\n"
        f"---\n\n"
        f"{CONTACT_LINE}\n\n"
        f"*{SYNTHETIC_NOTICE}*\n"
    )


def render_plain_text(title: str, body: str, sachgebiet: str, az: str, formular: str) -> str:
    return (
        f"{title}\n"
        f"{'=' * len(title)}\n\n"
        f"Zustaendige Stelle: Buergerbuero Rheinfurt - Sachgebiet {sachgebiet}\n"
        f"Aktenzeichen (Muster): {az}\n"
        f"Formular: {formular}\n\n"
        f"{body}\n\n"
        f"----\n\n"
        f"{CONTACT_LINE}\n\n"
        f"{SYNTHETIC_NOTICE}\n"
    )


# --- Sprechtage des mobilen Bürgerbüros (.csv) --------------------------------
#
# The one synthetic document of this library, not derived from the LHM source. Locations and
# weekdays follow the Hauptausschuss decisions in rat.py (Rheinau and Weststadt since April 2025,
# Nordfeld added on 21 April 2026); times and rooms are stated only here.

SPRECHTAGE_FILE_NAME = "sprechtage-mobiles-buergerbuero.csv"
_SPRECHTAGE_NOTICE = (
    "Diese Übersicht ist Teil des synthetischen Demo-Korpus der fiktiven Stadt Rheinfurt (siehe "
    "SOURCE.md im Wurzelverzeichnis dieses Korpus). Standorte, Zeiten und Räume sind frei erfunden."
)
_SPRECHTAGE_ANLIEGEN = (
    "Personalausweis und Reisepass beantragen, Wohnsitz anmelden oder ummelden; "
    "keine Kfz-Angelegenheiten"
)
SPRECHTAGE_ROWS: list[list[str]] = [
    ["Standort", "Ortsteil", "Sprechtag", "Uhrzeit", "Raum", "Angebot seit", "Anliegen"],
    [
        "Stadtteilzentrum Rheinau",
        "Rheinau",
        "jeden ersten Dienstag im Monat",
        "9:00 bis 12:30 Uhr",
        "Gruppenraum im Erdgeschoss",
        "April 2025",
        _SPRECHTAGE_ANLIEGEN,
    ],
    [
        "Bürgertreff Weststadt",
        "Weststadt",
        "jeden dritten Donnerstag im Monat",
        "14:00 bis 17:30 Uhr",
        "Saal im ersten Obergeschoss",
        "April 2025",
        _SPRECHTAGE_ANLIEGEN,
    ],
    [
        "Gemeindezentrum Nordfeld",
        "Nordfeld",
        "jeden zweiten Mittwoch im Monat",
        "9:00 bis 12:30 Uhr",
        "Besprechungsraum 2",
        "Juli 2026",
        _SPRECHTAGE_ANLIEGEN,
    ],
    [
        "Hinweis",
        "",
        "Termine über die Online-Terminvergabe des Bürgerbüros",
        "",
        "",
        "",
        "Mobiles Bürgerbüro Rheinfurt, buergerbuero@stadt-rheinfurt.example. "
        + _SPRECHTAGE_NOTICE,
    ],
]


def render_sprechtage_csv() -> bytes:
    buffer = io.StringIO()
    writer = csv.writer(buffer, delimiter=";", lineterminator="\n")
    writer.writerows(SPRECHTAGE_ROWS)
    return buffer.getvalue().encode("utf-8")


def build_meldewesen_documents() -> list[tuple[str, bytes]]:
    documents: list[tuple[str, bytes]] = []
    for index, filename in enumerate(SELECTED_MELDEWESEN, start=1):
        raw = read_raw(filename)
        title, body = transform_service(raw)
        az = aktenzeichen("32.1", index)
        formular = formularnummer("MW", index)
        content = render_markdown(title, body, "Meldewesen & Ausweise", az, formular)
        doc_filename = f"{index:03d}_{slugify(title)}.md"
        documents.append((doc_filename, content.encode("utf-8")))
    index = len(SELECTED_MELDEWESEN) + 1
    documents.append((f"{index:03d}_{SPRECHTAGE_FILE_NAME}", render_sprechtage_csv()))
    return documents


def build_kfz_documents() -> list[tuple[str, bytes]]:
    documents: list[tuple[str, bytes]] = []
    for index, filename in enumerate(SELECTED_KFZ, start=1):
        raw = read_raw(filename)
        title, body = transform_service(raw)
        az = aktenzeichen("32.3", index)
        formular = formularnummer("KFZ", index)
        # Alternate .md/.txt deterministically so both formats named in the
        # concept actually occur in this library.
        if index % 2 == 1:
            content = render_markdown(title, body, "Kfz-Zulassung", az, formular)
            extension = "md"
        else:
            content = render_plain_text(title, body, "Kfz-Zulassung", az, formular)
            extension = "txt"
        doc_filename = f"{index:03d}_{slugify(title)}.{extension}"
        documents.append((doc_filename, content.encode("utf-8")))
    return documents
