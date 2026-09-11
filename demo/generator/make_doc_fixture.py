#!/usr/bin/env python3
"""One-off producer of the committed Word 97 file of the "Formattest auf S3" library (#1519).

`.doc` is the one admitted extension no pinned library of this generator can write, so this script
renders the text declared in formate.py to a temporary `.docx` and lets LibreOffice convert it to
Word 97. The result is committed once and preserved by generate_corpus.py (PRESERVED_FILES); two
LibreOffice runs do not produce identical bytes, which is exactly why this is not part of the
regular generator run.

    python make_doc_fixture.py [--soffice <Pfad zu soffice>] [--force]

Ohne --force bricht der Lauf ab, wenn die Zieldatei schon existiert: Ein erneuter Export erzeugt
andere Bytes und würde MANIFEST.sha256 ungültig machen, ohne am Inhalt etwas zu ändern.

Same "run once, commit the result" pattern as
backend/src/test/resources/test-documents/generate-odf-fixtures.py.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import formate

REPO_ROOT = Path(__file__).resolve().parents[2]
TARGET = REPO_ROOT / "demo" / "corpus" / "formate" / formate.DOC_FILE_NAME

DEFAULT_SOFFICE_CANDIDATES = [
    "soffice",
    r"C:\Program Files\LibreOffice\program\soffice.exe",
    "/usr/bin/soffice",
    "/Applications/LibreOffice.app/Contents/MacOS/soffice",
]


def find_soffice(explicit: str | None) -> str:
    if explicit:
        return explicit
    for candidate in DEFAULT_SOFFICE_CANDIDATES:
        resolved = shutil.which(candidate) or (candidate if Path(candidate).exists() else None)
        if resolved:
            return resolved
    raise SystemExit(
        "LibreOffice (soffice) nicht gefunden. Pfad mit --soffice angeben; ohne LibreOffice lässt "
        "sich die Word-97-Datei nicht erzeugen (siehe generator/README.md, 'Formate ohne Writer')."
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--soffice", default=None)
    parser.add_argument(
        "--force",
        action="store_true",
        help="Vorhandene Datei überschreiben (der neue Lauf erzeugt andere Bytes)",
    )
    args = parser.parse_args(argv)
    # Two LibreOffice runs never produce identical bytes, so an accidental call would invalidate
    # MANIFEST.sha256 without changing a single character of content.
    if TARGET.exists() and not args.force:
        raise SystemExit(
            f"{TARGET} existiert bereits. Ein erneuter Lauf erzeugt andere Bytes und macht "
            "demo/corpus/MANIFEST.sha256 ungültig, ohne den Inhalt zu ändern. Absichtlich neu "
            "erzeugen: --force, danach 'python generate_corpus.py' für Manifest und SOURCE.md."
        )
    soffice = find_soffice(args.soffice)

    with tempfile.TemporaryDirectory() as work_dir:
        work = Path(work_dir)
        source = work / "formattest.docx"
        source.write_bytes(formate.render_doc_source_docx())
        subprocess.run(
            [
                soffice,
                "--headless",
                "--norestore",
                "--convert-to",
                "doc:MS Word 97",
                "--outdir",
                str(work),
                str(source),
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=300,
        )
        converted = work / "formattest.doc"
        if not converted.exists():
            raise SystemExit(f"LibreOffice hat keine Word-97-Datei erzeugt: {converted}")
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_bytes(converted.read_bytes())

    print(f"Geschrieben: {TARGET} ({TARGET.stat().st_size} Bytes)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
