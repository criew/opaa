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

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_DIR = REPO_ROOT / "demo" / "corpus"

LIBRARIES = [
    "leistungen-meldewesen-ausweise",
    "leistungen-kfz-zulassung",
    "satzungen-gebuehrenordnungen",
    "pressemitteilungen",
    "interne-dienstanweisungen-meldewesen",
]


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


def build_leistungen_meldewesen() -> list[tuple[Path, bytes]]:
    directory = CORPUS_DIR / "leistungen-meldewesen-ausweise"
    return [(directory / name, content) for name, content in leistungen.build_meldewesen_documents()]


def build_leistungen_kfz() -> list[tuple[Path, bytes]]:
    directory = CORPUS_DIR / "leistungen-kfz-zulassung"
    return [(directory / name, content) for name, content in leistungen.build_kfz_documents()]


def build_satzungen() -> list[tuple[Path, bytes]]:
    directory = CORPUS_DIR / "satzungen-gebuehrenordnungen"
    written = []
    for index, satzung in enumerate(satzungen.SATZUNGEN, start=1):
        filename = f"{index:02d}_{satzung.slug}.pdf"
        content = satzungen.render_satzung_pdf(satzung)
        written.append((directory / filename, content))
    return written


def build_presse() -> list[tuple[Path, bytes]]:
    directory = CORPUS_DIR / "pressemitteilungen"
    written = [(directory / "rss.xml", presse.render_rss(presse.PRESSEMITTEILUNGEN))]
    for meldung in presse.PRESSEMITTEILUNGEN:
        filename = f"{meldung.slug}.html"
        written.append((directory / filename, presse.render_html(meldung)))
    return written


def build_intern() -> list[tuple[Path, bytes]]:
    directory = CORPUS_DIR / "interne-dienstanweisungen-meldewesen"
    written = []
    index = 1
    for da in intern.DIENSTANWEISUNGEN:
        filename = f"{index:02d}_{da.slug}.docx"
        written.append((directory / filename, intern.render_dienstanweisung_docx(da)))
        index += 1
    for esk in intern.ESKALATIONSREGELN:
        filename = f"{index:02d}_{esk.slug}.docx"
        written.append((directory / filename, intern.render_dienstanweisung_docx(esk)))
        index += 1
    for faq in intern.FAQS:
        filename = f"{index:02d}_{faq.slug}.pdf"
        written.append((directory / filename, intern.render_faq_pdf(faq)))
        index += 1
    for schulung in intern.SCHULUNGEN:
        filename = f"{index:02d}_{schulung.slug}.pptx"
        written.append((directory / filename, intern.render_schulung_pptx(schulung)))
        index += 1
    return written


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


def main() -> None:
    leistungen_quelle.ensure_raw_files()
    clean_library_dirs()

    all_files: list[tuple[Path, bytes]] = []
    all_files += build_leistungen_meldewesen()
    all_files += build_leistungen_kfz()
    all_files += build_satzungen()
    all_files += build_presse()
    all_files += build_intern()

    for path, content in all_files:
        path.write_bytes(content)

    verify_manifest_completeness(all_files)
    write_manifest(all_files)

    per_library = {}
    for path, _ in all_files:
        library = path.relative_to(CORPUS_DIR).parts[0]
        per_library[library] = per_library.get(library, 0) + 1

    print(f"Wrote {len(all_files)} files across {len(per_library)} libraries to {CORPUS_DIR}", file=sys.stderr)
    for library, count in per_library.items():
        print(f"  {library}: {count}", file=sys.stderr)


if __name__ == "__main__":
    main()
