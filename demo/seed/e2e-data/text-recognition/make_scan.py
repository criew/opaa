"""Builds the scanned statute of the text recognition scenarios (e2e/tests/text-recognition.spec.ts).

Typesets the Baugenehmigungsgebührensatzung of the synthetic Verwaltung corpus (CC0,
eval/corpus/verwaltung) with its fee schedule from the matching Gebührenordnung as a ruled table on
a page of its own, rasterises every page at 200 dpi and degrades it like the scan profile "mittel"
of eval/ocr-poc/build_corpus.py (slight skew, blur, grey paper, noise). The result is an image-only
PDF without a text layer, A4 in PDF points.

Besides the PDF it writes satzung-scan.layout.json: where the scenarios' passages lie on the
original, as fractions of the page with the origin top left - the frame drawn in the viewer is
compared against these, never against the recognition's own boxes.

Deterministic: same input, same bytes. Needs reportlab, Pillow, numpy and pdftoppm (poppler).
Usage: python make_scan.py
"""

import io
import json
import random
import re
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from pypdf import PdfReader, PdfWriter
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import simpleSplit
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
CORPUS = REPO / "eval" / "corpus" / "verwaltung"
STATUTE = CORPUS / "verwaltung-0010_baugenehmigungsgebuehrensatzung-fassung-2024.md"
FEE_ORDER = CORPUS / "verwaltung-0011_gebuehrenordnung-bauamt.md"
OUT_PDF = HERE / "satzung-scan.pdf"
OUT_LAYOUT = HERE / "satzung-scan.layout.json"

FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")
DPI = 200
JPEG_QUALITY = 40
WIDTH, HEIGHT = A4
MARGIN_X = 62
MARGIN_TOP = 70
MARGIN_BOTTOM = 72
BODY_SIZE = 10.5
LEADING = 14.2

# Section 5 (1) lists the fees inline in the corpus; here it refers to the fee schedule instead, so
# a fee is found only in the table and the frame of a fee question has exactly one right place.
FEE_REFERENCE = (
    "(1) Die Gebühren für die Erteilung von Baugenehmigungen bemessen sich nach dem "
    "Gebührenverzeichnis in der Anlage zu dieser Satzung."
)
CROSS_REFERENCE = re.compile(r"\s*\(siehe verwaltung-[a-z-]+\.md\)|\s*\(verwaltung-[a-z-]+\.md\)")
# The text of the sideways page; written for this fixture, CC0 like the corpus.
ROTATED_PAGE_PARAGRAPHS = [
    "(1) Gebühren nach dieser Satzung sind an die Stadtkasse der Stadt Kalkstadt zu entrichten.",
    "(2) Bei jeder Zahlung ist das Kassenzeichen anzugeben, das im Gebührenbescheid des Bauamts "
    "oben rechts aufgeführt ist. Zahlungen ohne Kassenzeichen können nicht zugeordnet werden und "
    "gelten bis zur Klärung als nicht geleistet.",
    "(3) Barzahlungen nimmt die Stadtkasse im Rathaus Kalkstadt während der Öffnungszeiten an.",
]
POSITION = re.compile(r"^### Gebührenposition (\d+): (.+)$")
AMOUNT = re.compile(r"Gebühr in Höhe von ([\d.,]+ Euro)")


def front_matter_stripped(text):
    if text.startswith("---"):
        return text[text.index("\n---", 3) + 4 :].lstrip("\n")
    return text


def statute_blocks():
    """(kind, text) in reading order: title, intro, section headings and their paragraphs."""
    blocks = []
    for line in front_matter_stripped(STATUTE.read_text(encoding="utf-8")).splitlines():
        line = CROSS_REFERENCE.sub("", line).strip()
        if not line:
            continue
        if line.startswith("## Zusammenfassung"):
            break
        if line.startswith("# "):
            blocks.append(("title", line[2:]))
        elif line.startswith("## "):
            blocks.append(("heading", line[3:]))
        else:
            if blocks and blocks[-1] == ("heading", "§ 5 Höhe der Gebühren") and line.startswith("(1)"):
                line = FEE_REFERENCE
            blocks.append(("paragraph", line))
    return blocks


def fee_rows():
    rows, current = [], None
    for line in front_matter_stripped(FEE_ORDER.read_text(encoding="utf-8")).splitlines():
        match = POSITION.match(line)
        if match:
            current = (match.group(1), match.group(2).replace(" (Baugenehmigung)", ""))
            continue
        amount = AMOUNT.search(line) if current else None
        if amount:
            rows.append([current[0], current[1], amount.group(1)])
            current = None
    return rows


def box(page, left, top, right, bottom):
    """A box in PDF points (origin bottom left) as fractions of the page, origin top left."""
    return {
        "page": page,
        "x": round(left / WIDTH, 4),
        "y": round((HEIGHT - top) / HEIGHT, 4),
        "width": round((right - left) / WIDTH, 4),
        "height": round((top - bottom) / HEIGHT, 4),
    }


def typeset(path):
    """The clean PDF and the boxes of the passages the scenarios ask about."""
    pdfmetrics.registerFont(TTFont("Serif", str(FONT_DIR / "DejaVuSerif.ttf")))
    pdfmetrics.registerFont(TTFont("Serif-Bold", str(FONT_DIR / "DejaVuSerif-Bold.ttf")))
    pdf = canvas.Canvas(str(path), pagesize=A4, invariant=1)
    pdf.setTitle("")
    layout = {}
    page = 1
    y = HEIGHT - MARGIN_TOP
    text_width = WIDTH - 2 * MARGIN_X

    def footer():
        pdf.setFont("Serif", 8.5)
        pdf.drawCentredString(WIDTH / 2, 40, f"Stadt Kalkstadt · Bauamt · BAU-S-2024 · Seite {page}")

    def new_page():
        nonlocal page, y
        footer()
        pdf.showPage()
        page += 1
        y = HEIGHT - MARGIN_TOP

    blocks = statute_blocks()
    for index, (kind, text) in enumerate(blocks):
        font, size, before, after = {
            "title": ("Serif-Bold", 15, 0, 10),
            "heading": ("Serif-Bold", 11.5, 7, 3),
            "paragraph": ("Serif", BODY_SIZE, 0, 5),
        }[kind]
        lines = simpleSplit(text, font, size, text_width)
        leading = size * 1.35 if kind != "paragraph" else LEADING
        needed = before + len(lines) * leading + after
        # a heading keeps its first paragraph line on the same page
        if kind == "heading":
            needed += LEADING * 2
        if y - needed < MARGIN_BOTTOM:
            new_page()
        y -= before
        top = y
        pdf.setFont(font, size)
        for line in lines:
            y -= leading
            pdf.drawString(MARGIN_X, y + (leading - size) * 0.6, line)
        bottom = y
        y -= after
        if kind == "paragraph" and "Freistellungsbescheids" in text:
            right = MARGIN_X + max(pdfmetrics.stringWidth(line, font, size) for line in lines)
            layout["freistellungsbescheid"] = box(page, MARGIN_X, top, right, bottom)
        if kind == "heading" and text.startswith("§ 13"):
            layout["paragraph13Heading"] = box(page, MARGIN_X, top, MARGIN_X + text_width, bottom)

    # The fee schedule, a ruled table on a page of its own.
    new_page()
    pdf.setFont("Serif-Bold", 13)
    y -= 13 * 1.35
    pdf.drawString(MARGIN_X, y, "Anlage zur Baugenehmigungsgebührensatzung")
    y -= 8
    pdf.setFont("Serif", BODY_SIZE)
    for line in simpleSplit(
        "Gebührenverzeichnis des Bauamts der Stadt Kalkstadt für die Erteilung von Baugenehmigungen "
        "(Fassung 2024)",
        "Serif",
        BODY_SIZE,
        text_width,
    ):
        y -= LEADING
        pdf.drawString(MARGIN_X, y, line)
    y -= 14
    columns = [MARGIN_X, MARGIN_X + 52, MARGIN_X + 52 + 290, WIDTH - MARGIN_X]
    row_height = 24
    header = ["Nr.", "Amtshandlung", "Gebühr"]
    table_top = y
    pdf.setLineWidth(0.9)
    rows = [header] + fee_rows()
    for row_index, row in enumerate(rows):
        top, bottom = y, y - row_height
        for column, value in enumerate(row):
            pdf.setFont("Serif-Bold" if row_index == 0 else "Serif", BODY_SIZE)
            pdf.drawString(columns[column] + 6, bottom + 8, value)
        pdf.line(columns[0], bottom, columns[-1], bottom)
        if row_index > 0 and row[1] == "Zweitausfertigung":
            layout["zweitausfertigung"] = box(page, columns[0], top, columns[-1], bottom)
            layout["zweitausfertigungAmount"] = row[2]
        y = bottom
    pdf.line(columns[0], table_top, columns[-1], table_top)
    for x in columns:
        pdf.line(x, table_top, x, y)
    layout["table"] = box(page, columns[0], table_top, columns[-1], y)

    # A page scanned sideways: typeset upright here, stored turned and shown upright through
    # /Rotate 90 (see main), the way a scanner files a landscape-fed sheet.
    new_page()
    pdf.setFont("Serif-Bold", 13)
    y -= 13 * 1.35
    pdf.drawString(MARGIN_X, y, "Anlage 2 zur Baugenehmigungsgebührensatzung")
    y -= 12
    pdf.setFont("Serif", BODY_SIZE)
    for text in ROTATED_PAGE_PARAGRAPHS:
        lines = simpleSplit(text, "Serif", BODY_SIZE, text_width)
        top = y
        for line in lines:
            y -= LEADING
            pdf.drawString(MARGIN_X, y + (LEADING - BODY_SIZE) * 0.6, line)
        if "Kassenzeichen" in text:
            right = MARGIN_X + max(pdfmetrics.stringWidth(line, "Serif", BODY_SIZE) for line in lines)
            layout["kassenzeichen"] = box(page, MARGIN_X, top, right, y)
        y -= 6
    layout["rotatedPage"] = page
    footer()
    pdf.showPage()
    pdf.save()
    layout["pages"] = page
    return layout


def degrade(image, rng):
    """Scan profile "mittel" of eval/ocr-poc/build_corpus.py, at 200 instead of 300 dpi."""
    grey = image.convert("L")
    grey = grey.rotate(rng.uniform(-0.8, 0.8), resample=Image.BICUBIC, expand=False, fillcolor=255)
    grey = grey.filter(ImageFilter.GaussianBlur(0.5))
    pixels = 30 + np.asarray(grey).astype(np.float32) * 0.80
    noise = np.random.default_rng(rng.randrange(1 << 30)).normal(0, 6, pixels.shape)
    return Image.fromarray(np.clip(pixels + noise, 0, 255).astype(np.uint8))


def main():
    with tempfile.TemporaryDirectory() as work:
        clean = Path(work) / "clean.pdf"
        layout = typeset(clean)
        subprocess.run(
            ["pdftoppm", "-r", str(DPI), "-gray", "-png", str(clean), str(Path(work) / "page")],
            check=True,
        )
        rng = random.Random("opaa-e2e-satzung-scan")
        pages = []
        for number, png in enumerate(sorted(Path(work).glob("page-*.png")), start=1):
            image = degrade(Image.open(png), rng)
            if number == layout["rotatedPage"]:
                # stored turned a quarter counter-clockwise; /Rotate 90 turns it back on display
                image = image.transpose(Image.Transpose.ROTATE_90)
            buffer = io.BytesIO()
            image.save(buffer, "JPEG", quality=JPEG_QUALITY)
            pages.append(Image.open(io.BytesIO(buffer.getvalue())))
        raw = io.BytesIO()
        pages[0].save(raw, "PDF", save_all=True, append_images=pages[1:], resolution=DPI)
        # Rewritten without the creation date Pillow stamps, so the bytes do not change per run.
        writer = PdfWriter(clone_from=PdfReader(raw))
        writer.pages[layout["rotatedPage"] - 1].rotate(90)
        writer.metadata = None
        writer._ID = None
        with OUT_PDF.open("wb") as out:
            writer.write(out)
    OUT_LAYOUT.write_text(json.dumps(layout, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{OUT_PDF.name}: {layout['pages']} pages, {OUT_PDF.stat().st_size} bytes")


if __name__ == "__main__":
    main()
