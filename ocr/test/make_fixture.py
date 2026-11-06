"""Generates the test page of the OCR smoke test: one A4 page with a paragraph sign and a ruled
table, rendered as an image and saved as an image-only PDF (no text layer), with the slight
rotation, blur and noise of an office scan. Seeded, so a rerun with the same Pillow and font gives
the same page. Writes scan-bescheid.pdf next to this file.

Usage: python3 ocr/test/make_fixture.py [--font /usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf]
"""

import argparse
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

DPI = 200
WIDTH, HEIGHT = int(8.27 * DPI), int(11.69 * DPI)
MARGIN = int(0.9 * DPI)

TITLE = "Bescheid über die Gebührenfestsetzung"
PARAGRAPHS = [
    "Gemäß § 12 Abs. 3 der Verwaltungsgebührensatzung wird für die beantragten",
    "Amtshandlungen eine Gebühr festgesetzt. Die Festsetzung beruht auf § 4 Nr. 2",
    "des Kommunalabgabengesetzes in der geltenden Fassung.",
]
TABLE = [
    ["Nr.", "Amtshandlung", "Gebühr"],
    ["1", "Beglaubigung einer Abschrift", "12,50 Euro"],
    ["2", "Ausstellung einer Bescheinigung", "25,00 Euro"],
    ["3", "Einsicht in die Akte", "8,00 Euro"],
]
CLOSING = [
    "Gegen diesen Bescheid kann innerhalb eines Monats nach Bekanntgabe",
    "Widerspruch erhoben werden.",
]


def render(font_path: str) -> Image.Image:
    page = Image.new("L", (WIDTH, HEIGHT), 255)
    draw = ImageDraw.Draw(page)
    title = ImageFont.truetype(font_path, 44)
    body = ImageFont.truetype(font_path, 34)
    y = MARGIN
    draw.text((MARGIN, y), TITLE, font=title, fill=0)
    y += 110
    for line in PARAGRAPHS:
        draw.text((MARGIN, y), line, font=body, fill=0)
        y += 52
    y += 50
    widths = [140, 760, 300]
    row_height = 66
    for row in TABLE:
        x = MARGIN
        for cell, width in zip(row, widths):
            draw.rectangle([x, y, x + width, y + row_height], outline=0, width=3)
            draw.text((x + 16, y + 14), cell, font=body, fill=0)
            x += width
        y += row_height
    y += 80
    for line in CLOSING:
        draw.text((MARGIN, y), line, font=body, fill=0)
        y += 52
    return page


def scan(page: Image.Image) -> Image.Image:
    rng = random.Random(3600)
    page = page.rotate(0.6, resample=Image.BICUBIC, fillcolor=255)
    page = page.filter(ImageFilter.GaussianBlur(0.7))
    pixels = page.load()
    for _ in range(WIDTH * HEIGHT // 400):
        x, y = rng.randrange(WIDTH), rng.randrange(HEIGHT)
        pixels[x, y] = rng.choice((90, 160, 200))
    return page


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", default="/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf")
    args = parser.parse_args()
    out = Path(__file__).resolve().parent / "scan-bescheid.pdf"
    scan(render(args.font)).save(out, "PDF", resolution=DPI, quality=75)
    print(f"{out}: {out.stat().st_size} bytes")


if __name__ == "__main__":
    main()
