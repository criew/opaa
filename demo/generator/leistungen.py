"""Builds the "Leistungen Meldewesen & Ausweise" (.md) and "Leistungen
Kfz-Zulassung" (.md + .txt) documents from the transformed LHM source files.

See docs/features/demo-instance.md, table "Behördenlandschaft, Bibliotheken
und Formate", for the target library/format split.
"""

from __future__ import annotations

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


def build_meldewesen_documents() -> list[tuple[str, bytes]]:
    documents: list[tuple[str, bytes]] = []
    for index, filename in enumerate(SELECTED_MELDEWESEN, start=1):
        raw = read_raw(filename)
        title, body = transform_service(raw, filename)
        az = aktenzeichen("32.1", index)
        formular = formularnummer("MW", index)
        content = render_markdown(title, body, "Meldewesen & Ausweise", az, formular)
        doc_filename = f"{index:03d}_{slugify(title)}.md"
        documents.append((doc_filename, content.encode("utf-8")))
    return documents


def build_kfz_documents() -> list[tuple[str, bytes]]:
    documents: list[tuple[str, bytes]] = []
    for index, filename in enumerate(SELECTED_KFZ, start=1):
        raw = read_raw(filename)
        title, body = transform_service(raw, filename)
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
