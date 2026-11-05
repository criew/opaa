"""Variant (d), the yardstick: OCRmyPDF with Tesseract 'deu'. Plain text only, no structure."""

import subprocess
import tempfile
from pathlib import Path

from runlib import cli, run


def convert(_, src):
    with tempfile.TemporaryDirectory() as tmp:
        sidecar = Path(tmp) / "out.txt"
        subprocess.run(
            ["ocrmypdf", "--force-ocr", "-l", "deu", "--output-type", "pdf", "--optimize", "0",
             "--sidecar", str(sidecar), str(src), str(Path(tmp) / "out.pdf")],
            check=True, capture_output=True)
        return sidecar.read_text(encoding="utf-8").replace("\f", "\n")


if __name__ == "__main__":
    a, profiles, docs = cli()
    run("tesseract", convert, a.corpus, a.out, profiles, docs)
