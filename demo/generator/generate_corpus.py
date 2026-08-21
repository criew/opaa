#!/usr/bin/env python3
"""Deterministic generator for the Rheinfurt demo corpus (Issue #711).

Builds all five demo libraries described in
docs/features/demo-instance.md ("Behördenlandschaft, Bibliotheken und
Formate") under demo/corpus/:

- leistungen-meldewesen-ausweise/  (.md)          — rewritten LHM source
- leistungen-kfz-zulassung/        (.md, .txt)     — rewritten LHM source
- satzungen-gebuehrenordnungen/    (.pdf)          — synthetic, hand-authored
- pressemitteilungen/              (RSS + .html)   — synthetic, hand-authored
- interne-dienstanweisungen-meldewesen/ (.docx/.pdf/.pptx) — synthetic

No network access is required once the pinned LHM raw files are cached under
generator/raw-source/ (see leistungen_quelle.py); no `random`, no wall-clock
timestamps anywhere in this pipeline, so two runs produce byte-identical
output — verified by re-running this script and diffing MANIFEST.sha256.

Usage:
    cd demo/generator
    pip install -r requirements.txt
    python generate_corpus.py

See demo/generator/README.md for the full reproduction procedure and the
tool-choice rationale for PDF/DOCX/PPTX generation.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

import reportlab.rl_config as rl_config

# Must be set before any reportlab document is built: suppresses the
# CreationDate/ModDate and internal /ID that reportlab otherwise derives from
# wall-clock time, which would make every PDF generation non-reproducible.
rl_config.invariant = 1

import intern
import leistungen
import leistungen_quelle
import presse
import satzungen
from rheinfurt_text import RHEINFURT_BIC, RHEINFURT_IBAN, RHEINFURT_PLZ, RHEINFURT_VORWAHL
from validation import validate_all

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_DIR = REPO_ROOT / "demo" / "corpus"

LIBRARIES = [
    "leistungen-meldewesen-ausweise",
    "leistungen-kfz-zulassung",
    "satzungen-gebuehrenordnungen",
    "pressemitteilungen",
    "interne-dienstanweisungen-meldewesen",
]

LIBRARY_LABELS = {
    "leistungen-meldewesen-ausweise": "Leistungen Meldewesen & Ausweise",
    "leistungen-kfz-zulassung": "Leistungen Kfz-Zulassung",
    "satzungen-gebuehrenordnungen": "Satzungen & Gebührenordnungen",
    "pressemitteilungen": "Pressemitteilungen Stadt Rheinfurt",
    "interne-dienstanweisungen-meldewesen": "Interne Dienstanweisungen Meldewesen",
}
LIBRARY_FORMATS = {
    "leistungen-meldewesen-ausweise": "`.md`",
    "leistungen-kfz-zulassung": "`.md`, `.txt`",
    "satzungen-gebuehrenordnungen": "`.pdf`",
    "pressemitteilungen": "RSS-XML, HTML",
    "interne-dienstanweisungen-meldewesen": "`.docx`, `.pdf`, `.pptx`",
}


def sha256_of(data: bytes) -> str:
    digest = hashlib.sha256()
    digest.update(data)
    return digest.hexdigest()


def clean_library_dirs() -> None:
    for library in LIBRARIES:
        directory = CORPUS_DIR / library
        if directory.exists():
            for existing in directory.iterdir():
                if existing.is_file():
                    existing.unlink()
        directory.mkdir(parents=True, exist_ok=True)


def build_leistungen_meldewesen() -> list[tuple[str, str, bytes]]:
    return [
        (f"leistungen-meldewesen-ausweise/{name}", name, content)
        for name, content in leistungen.build_meldewesen_documents()
    ]


def build_leistungen_kfz() -> list[tuple[str, str, bytes]]:
    return [
        (f"leistungen-kfz-zulassung/{name}", name, content)
        for name, content in leistungen.build_kfz_documents()
    ]


def build_satzungen() -> list[tuple[str, str, bytes]]:
    written = []
    for index, satzung in enumerate(satzungen.SATZUNGEN, start=1):
        filename = f"{index:02d}_{satzung.slug}.pdf"
        content = satzungen.render_satzung_pdf(satzung)
        written.append((f"satzungen-gebuehrenordnungen/{filename}", satzung.slug, content))
    return written


def build_presse() -> list[tuple[str, str, bytes]]:
    written = [
        (
            "pressemitteilungen/rss.xml",
            "rss.xml",
            presse.render_rss(presse.PRESSEMITTEILUNGEN),
        )
    ]
    for meldung in presse.PRESSEMITTEILUNGEN:
        filename = f"{meldung.slug}.html"
        written.append(
            (f"pressemitteilungen/{filename}", meldung.slug, presse.render_html(meldung))
        )
    return written


def build_intern() -> list[tuple[str, str, bytes]]:
    written = []
    index = 1
    for da in intern.DIENSTANWEISUNGEN:
        filename = f"{index:02d}_{da.slug}.docx"
        written.append(
            (
                f"interne-dienstanweisungen-meldewesen/{filename}",
                da.slug,
                intern.render_dienstanweisung_docx(da),
            )
        )
        index += 1
    for esk in intern.ESKALATIONSREGELN:
        filename = f"{index:02d}_{esk.slug}.docx"
        written.append(
            (
                f"interne-dienstanweisungen-meldewesen/{filename}",
                esk.slug,
                intern.render_dienstanweisung_docx(esk),
            )
        )
        index += 1
    for faq in intern.FAQS:
        filename = f"{index:02d}_{faq.slug}.pdf"
        written.append(
            (
                f"interne-dienstanweisungen-meldewesen/{filename}",
                faq.slug,
                intern.render_faq_pdf(faq),
            )
        )
        index += 1
    for schulung in intern.SCHULUNGEN:
        filename = f"{index:02d}_{schulung.slug}.pptx"
        written.append(
            (
                f"interne-dienstanweisungen-meldewesen/{filename}",
                schulung.slug,
                intern.render_schulung_pptx(schulung),
            )
        )
        index += 1
    return written


# --- Validation: source text extraction per library (WICHTIG 2) ------------
#
# Leistungen documents are validated against their actually rendered .md/.txt
# bytes (see build_leistungen_meldewesen/build_leistungen_kfz above — the
# third tuple element). Satzungen/Presse/Intern are hand-authored Python data,
# never touched by the München→Rheinfurt transform, so they are validated at
# the source-string level, before rendering, per the two acceptable
# strategies named in the PR #717 review.


def _satzung_text(satzung: satzungen.Satzung) -> str:
    parts = [satzung.titel, satzung.kurzbezeichnung, satzung.aktenzeichen, *satzung.praeambel]
    for paragraf in satzung.paragrafen:
        parts.append(paragraf.ueberschrift)
        parts.extend(paragraf.text)
    for zeile in satzung.gebuehren:
        parts.append(zeile.tatbestand)
        parts.append(zeile.betrag)
    return "\n".join(parts)


def _pressemitteilung_text(meldung: presse.Pressemitteilung) -> str:
    return "\n".join([meldung.titel, meldung.kategorie, meldung.teaser, *meldung.absaetze, meldung.kontakt])


def _dienstanweisung_text(da: intern.Dienstanweisung) -> str:
    parts = [da.titel, da.aktenzeichen]
    for ueberschrift, absaetze in da.abschnitte:
        parts.append(ueberschrift)
        parts.extend(absaetze)
    return "\n".join(parts)


def _faq_text(faq: intern.Faq) -> str:
    parts = [faq.titel, faq.aktenzeichen]
    for frage, antwort in faq.fragen:
        parts.append(frage)
        parts.append(antwort)
    return "\n".join(parts)


def _schulung_text(schulung: intern.Schulung) -> str:
    parts = [schulung.titel]
    for folie in schulung.folien:
        parts.append(folie.titel)
        parts.extend(folie.punkte)
    return "\n".join(parts)


def collect_validation_texts(
    leistungen_files: list[tuple[str, str, bytes]]
) -> list[tuple[str, str]]:
    texts: list[tuple[str, str]] = []
    for relative_path, _slug, content in leistungen_files:
        texts.append((relative_path, content.decode("utf-8")))
    for satzung in satzungen.SATZUNGEN:
        texts.append((f"satzungen-gebuehrenordnungen/{satzung.slug} (Quelltext)", _satzung_text(satzung)))
    for meldung in presse.PRESSEMITTEILUNGEN:
        texts.append((f"pressemitteilungen/{meldung.slug} (Quelltext)", _pressemitteilung_text(meldung)))
    for da in intern.DIENSTANWEISUNGEN + intern.ESKALATIONSREGELN:
        texts.append(
            (f"interne-dienstanweisungen-meldewesen/{da.slug} (Quelltext)", _dienstanweisung_text(da))
        )
    for faq in intern.FAQS:
        texts.append((f"interne-dienstanweisungen-meldewesen/{faq.slug} (Quelltext)", _faq_text(faq)))
    for schulung in intern.SCHULUNGEN:
        texts.append(
            (f"interne-dienstanweisungen-meldewesen/{schulung.slug} (Quelltext)", _schulung_text(schulung))
        )
    return texts


def write_manifest(all_files: list[tuple[Path, bytes]]) -> None:
    manifest_path = CORPUS_DIR / "MANIFEST.sha256"
    lines = []
    for path, content in sorted(all_files, key=lambda item: str(item[0].relative_to(CORPUS_DIR))):
        relative = path.relative_to(CORPUS_DIR).as_posix()
        lines.append(f"{sha256_of(content)} *{relative}")
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def verify_manifest_completeness(all_files: list[tuple[Path, bytes]]) -> None:
    """Guard against stray files left in a library directory by something
    other than this generator run (mirrors eval/generator's
    verify_manifest_completeness)."""
    on_disk = set()
    for library in LIBRARIES:
        for existing in (CORPUS_DIR / library).iterdir():
            if existing.is_file():
                on_disk.add(existing.relative_to(CORPUS_DIR).as_posix())
    written = {path.relative_to(CORPUS_DIR).as_posix() for path, _ in all_files}
    if on_disk != written:
        raise SystemExit(
            "Corpus directory and written-file list diverge after generation: "
            f"only on disk: {sorted(on_disk - written)[:5]}, "
            f"only in manifest: {sorted(written - on_disk)[:5]}"
        )


def render_source_md(per_library: dict[str, int], total_bytes: int) -> str:
    total_docs = sum(per_library.values())
    table_rows = "\n".join(
        f"| {LIBRARY_LABELS[library]} | `{library}/` | {per_library.get(library, 0)} | "
        f"{LIBRARY_FORMATS[library]} |"
        for library in LIBRARIES
    )
    return f"""# Quellen, Lizenzen und Hinweis auf synthetische Inhalte

**Alle Inhalte in diesem Korpus sind synthetisch.** Rheinfurt ist eine erfundene Stadt; jede
Behörde, Adresse, Person, Telefonnummer, E-Mail-Adresse, Bankverbindung, jedes Aktenzeichen und
jeder Euro-Betrag in diesem Verzeichnis ist frei erfunden oder aus realen Quellen deterministisch
umgeschrieben (siehe unten). Übereinstimmungen mit realen Personen oder Behörden sind nicht
beabsichtigt.

Dieser Abschnitt sowie die Datei- und Dokumentzahlen unten werden **vom Generator selbst
geschrieben** ([`generate_corpus.py::render_source_md`](../generator/generate_corpus.py)) — sie
können nicht veralten, weil sie bei jedem Lauf aus der tatsächlich erzeugten Dateiliste neu
berechnet werden.

## Rohmaterial: LHM-Dienstleistungen-Corpus

| | |
|---|---|
| **Datensatz** | [`it-at-m/LHM-Dienstleistungen-Corpus`](https://huggingface.co/datasets/it-at-m/LHM-Dienstleistungen-Corpus) auf HuggingFace (Landeshauptstadt München) |
| **Lizenz** | MIT — vollständiger Lizenztext: [`THIRD-PARTY-LICENSES/LHM-Dienstleistungen-Corpus-MIT.txt`](THIRD-PARTY-LICENSES/LHM-Dienstleistungen-Corpus-MIT.txt) |
| **Abgerufener Commit** | `3def28953f6d8d65bde7b6b3956fe36c9791a4de` |
| **Abrufdatum** | 2026-08-21 |
| **Verwendete Dateien** | 83 von ~740 Leistungsbeschreibungen (kuratierte Auswahl, siehe `generator/leistungen_quelle.py`) |
| **Verwendung** | Rohtext für die Bibliotheken „Leistungen Meldewesen & Ausweise" und „Leistungen Kfz-Zulassung"; deterministisch auf Rheinfurt umgeschrieben (siehe `generator/rheinfurt_text.py`) |

Die verwendeten Leistungsbeschreibungen der Landeshauptstadt München wurden automatisiert
umgeschrieben: Ortsnamen, Behördenbezeichnungen (`Landeshauptstadt München` → `Stadt Rheinfurt`,
`Kreisverwaltungsreferat (KVR)` → `Bürgerbüro Rheinfurt`), Straßennamen und Stadtbezirke (z. B.
`Ruppertstraße` → `Rheinauer Straße`, `Pasing` → `Rheinau`; vollständige Zuordnung in
`rheinfurt_text.py`), Postleitzahlen (auf die erkennbar fiktive `{RHEINFURT_PLZ}`), Bankverbindungen
(auf eine fiktive, prüfziffernkonforme IBAN `{RHEINFURT_IBAN}` und BIC `{RHEINFURT_BIC}`),
E-Mail-Domains (`muenchen.de` → `stadt-rheinfurt.example`), Telefonnummern (`089/…` → deterministisch
abgeleitete `{RHEINFURT_VORWAHL}/44-…`) sowie Gebührenbeträge (deterministisch pro
Dokument skaliert) wurden ersetzt. Externe Links (z. B. ein echter `bzst.de`-Deeplink), veraltete
Corona-Passagen und ins Leere verweisende Formulierungen aus der entfernten Link-Sektion
("... finden Sie hier.") wurden entfernt bzw. umformuliert. Die münchenspezifischen Abschnitte
„Anlaufstellen in Ihrer Nähe" und „Links & Downloads" (reale Adressen, Kartenwidgets,
muenchen.de-Downloadlinks) wurden vollständig entfernt statt umgeschrieben. Jedes generierte
Dokument trägt zusätzlich ein Aktenzeichen- und Formularnummer-Muster sowie einen Hinweis auf die
synthetische Herkunft.

Ein abschließender Validierungslauf (`generator/validation.py`) prüft die erzeugten Inhalte aller
fünf Bibliotheken gegen eine Liste von Verbotsmustern (reale Ortsnamen, Straßen außerhalb einer
Whitelist, reale Postleitzahlen, reale Bankverbindungen) und bricht den Generator-Lauf mit Fehler
ab, falls eines davon gefunden wird.

Reproduktion und SHA-256-Pins der verwendeten Rohdateien: [`generator/leistungen_quelle.py`](../generator/leistungen_quelle.py).

### Entscheidung zu realen Bundesbehörden (Koordinator, PR #717 Review)

Namentliche Nennungen echter Bundesbehörden — z. B. Kraftfahrt-Bundesamt, Bundesdruckerei,
Bundesamt für Justiz, Bundeszentralamt für Steuern, Bundesamt für das Personalmanagement der
Bundeswehr — **bleiben im Korpus erhalten**. Eine fiktive Kommune arbeitet fachlich korrekt mit
real existierenden Bundesbehörden zusammen; das durch eine erfundene Behörde zu ersetzen wäre
sachlich falsch. Entfernt werden ausschließlich **URLs, Postadressen und Kontodaten** dieser
Behörden (siehe `rheinfurt_text.py::strip_external_links`).

## Stilvorlagen (keine Textübernahme)

| Quelle | Lizenz | Verwendung |
|---|---|---|
| [FIM-Portal / LeiKa](https://fimportal.de/) | ungeklärt | Nur Katalog- und Stilreferenz zur Auswahl einer für eine Mittelstadt plausiblen Leistungsauswahl. Keine Textübernahme |
| [Pressemeldungen Stadt Köln](https://offenedaten-koeln.de/dataset/pressemeldungen) | DL-DE-BY-2.0 | Nur Stilvorlage für Ton und Meldungstypen (Sperrung, Öffnungszeiten, Veranstaltung, Jubiläum) der Bibliothek „Pressemitteilungen Stadt Rheinfurt". Kein Text übernommen, daher ohne Namensnennungspflicht nach DL-DE-BY-2.0 — hier dennoch dokumentiert |
| [RSS-Feed Stadt Düsseldorf](https://www.duesseldorf.de/rss-feed) | keine offene Lizenz | Nur Formatvorlage für die RSS-2.0-Feedstruktur. Kein Text übernommen |
| Kommunale Satzungen (Gebühren-, Straßenreinigungssatzung beliebiger deutscher Städte) | gemeinfrei (§ 5 Abs. 1 UrhG) | Nur Strukturvorlage (§§-Gliederung, Gebührenverzeichnis als Anlage) für die 19 synthetischen Satzungen Rheinfurts. Kein Text übernommen |

Details und Begründung der Quellenauswahl: [`docs/features/demo-instance.md`](../../docs/features/demo-instance.md),
Abschnitt „Quellen und Lizenzen" (Recherche Issue #709).

## Wie diese Dateien entstanden sind

Erzeugt durch [`demo/generator/generate_corpus.py`](../generator/generate_corpus.py); siehe
[`demo/generator/README.md`](../generator/README.md) für den vollständigen Reproduktionslauf,
die Werkzeugwahl für PDF/DOCX/PPTX und die Determinismus-Garantien.

## Integritätsprüfung

```bash
cd demo/corpus
sha256sum -c MANIFEST.sha256
```

## Umfang

{total_docs} Dokumente über fünf Bibliotheken (Zielkorridor 150–300 laut Issue #711):

| Bibliothek | Verzeichnis | Anzahl | Formate |
|---|---|---|---|
{table_rows}

Gesamtgröße rund {f"{total_bytes / (1024 * 1024):.1f}".replace(".", ",")} MB.
"""


def main() -> None:
    leistungen_quelle.ensure_raw_files()
    clean_library_dirs()

    leistungen_meldewesen = build_leistungen_meldewesen()
    leistungen_kfz = build_leistungen_kfz()
    satzungen_files = build_satzungen()
    presse_files = build_presse()
    intern_files = build_intern()

    validate_all(collect_validation_texts(leistungen_meldewesen + leistungen_kfz))

    all_files: list[tuple[Path, bytes]] = [
        (CORPUS_DIR / relative_path, content)
        for relative_path, _slug, content in (
            leistungen_meldewesen + leistungen_kfz + satzungen_files + presse_files + intern_files
        )
    ]

    for path, content in all_files:
        path.write_bytes(content)

    verify_manifest_completeness(all_files)
    write_manifest(all_files)

    per_library: dict[str, int] = {}
    for path, _ in all_files:
        library = path.relative_to(CORPUS_DIR).parts[0]
        per_library[library] = per_library.get(library, 0) + 1
    total_bytes = sum(len(content) for _, content in all_files)

    (CORPUS_DIR / "SOURCE.md").write_text(
        render_source_md(per_library, total_bytes), encoding="utf-8", newline="\n"
    )

    print(f"Wrote {len(all_files)} files across {len(per_library)} libraries to {CORPUS_DIR}", file=sys.stderr)
    for library, count in per_library.items():
        print(f"  {library}: {count}", file=sys.stderr)


if __name__ == "__main__":
    main()
