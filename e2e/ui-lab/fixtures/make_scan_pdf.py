"""Generates the scanned fixture PDF of the OCR lab (epic #2640): two image-only pages without a
text layer, like a fax or an old scan, plus the frames of the cited passages normalized to the page.

Writes frontend/src/features/library/mocks/scanPdfFixture.ts. Run with python3 -I from anywhere.
"""
import base64
import io
import json
import pathlib
import random

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = ROOT / "frontend/src/features/library/mocks/scanPdfFixture.ts"
W, H = 1050, 1485  # A4 at about 127 dpi
SERIF = "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"
SERIF_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"

random.seed(2640)
body = ImageFont.truetype(SERIF, 21)
bold = ImageFont.truetype(SERIF_BOLD, 23)
title = ImageFont.truetype(SERIF_BOLD, 30)
small = ImageFont.truetype(SERIF, 16)
mono = ImageFont.truetype(MONO, 20)


class Page:
    def __init__(self):
        self.img = Image.new("L", (W, H), 238)
        self.d = ImageDraw.Draw(self.img)
        self.y = 110
        self.marks = {}

    def line(self, text, font=body, x=120, gap=10, key=None):
        box = self.d.textbbox((x, self.y), text, font=font)
        self.d.text((x, self.y), text, font=font, fill=random.randint(20, 45))
        if key:
            self.mark(key, box)
        self.y = box[3] + gap
        return box

    def mark(self, key, box):
        x0, y0, x1, y1 = box
        if key in self.marks:
            a = self.marks[key]
            self.marks[key] = (min(a[0], x0), min(a[1], y0), max(a[2], x1), max(a[3], y1))
        else:
            self.marks[key] = box

    def finish(self):
        # scan look: speckles, a soft blur and a grey edge on the left like a fax feed
        px = self.img.load()
        for _ in range(9000):
            x, y = random.randrange(W), random.randrange(H)
            px[x, y] = random.randint(150, 210)
        self.d.rectangle((0, 0, 14, H), fill=200)
        return self.img.filter(ImageFilter.GaussianBlur(0.7))


p1 = Page()
p1.line("Stadt Rheinfurt", small, gap=4)
p1.line("Der Oberbürgermeister", small, gap=40)
p1.line("Verwaltungsgebührensatzung", title, gap=6)
p1.line("der Stadt Rheinfurt vom 14. März 2003", body, gap=36)
p1.line("§ 1  Gebührenpflicht", bold, gap=12)
for t in [
    "(1) Für Amtshandlungen in Selbstverwaltungsangelegenheiten werden",
    "Verwaltungsgebühren nach dieser Satzung erhoben, soweit nicht",
    "Bundes- oder Landesrecht etwas anderes bestimmt.",
    "(2) Die Höhe der Gebühren richtet sich nach dem Gebührentarif",
    "(Anlage), der Bestandteil dieser Satzung ist.",
]:
    p1.line(t, gap=8)
p1.y += 22
p1.line("§ 2  Gebührenschuldner", bold, gap=12)
for t in [
    "(1) Zur Zahlung der Gebühren ist verpflichtet, wer die Amtshandlung",
    "veranlasst oder in wessen Interesse sie vorgenommen wird.",
    "(2) Mehrere Gebührenschuldner haften als Gesamtschuldner.",
]:
    p1.line(t, gap=8)
p1.y += 22
p1.line("§ 3  Entstehung und Fälligkeit", bold, gap=12)
for i, t in enumerate([
    "(1) Die Gebührenschuld entsteht mit der Beendigung der",
    "Amtshandlung. Die Gebühr wird mit der Bekanntgabe der",
    "Gebührenfestsetzung an den Schuldner fällig.",
    "(2) Die Ausfertigung kann von der Zahlung der Gebühr abhängig",
    "gemacht werden.",
]):
    p1.line(t, gap=8, key="p1-faellig" if i < 3 else None)
p1.y += 22
p1.line("§ 4  Billigkeitsmaßnahmen", bold, gap=12)
for t in [
    "Die Gebühr kann ganz oder teilweise erlassen werden, wenn ihre",
    "Erhebung nach Lage des Einzelfalls unbillig wäre.",
]:
    p1.line(t, gap=8)
p1.y = H - 90
p1.line("- 1 -", small, x=W // 2 - 20)

p2 = Page()
p2.line("Anlage zur Verwaltungsgebührensatzung", bold, gap=6)
p2.line("Gebührentarif", body, gap=34)
cols = (120, 210, 690)
p2.d.line((110, p2.y - 8, W - 110, p2.y - 8), fill=60, width=2)
p2.line("Nr.    Gegenstand                                 Gebühr", mono, gap=14)
p2.d.line((110, p2.y - 6, W - 110, p2.y - 6), fill=60, width=1)
rows = [
    ("1", "Beglaubigung einer Unterschrift", "12,00 Euro"),
    ("2", "Beglaubigung einer Abschrift, je Seite", "4,00 Euro"),
    ("3", "Bescheinigung aus Akten", "18,00 Euro"),
    ("4", "Zweitausfertigung eines Bescheids", "46,00 Euro"),
    ("5", "Akteneinsicht, je angefangene halbe Stunde", "15,00 Euro"),
    ("6", "Auskunft aus dem Melderegister", "10,00 Euro"),
]
for nr, text, fee in rows:
    y0 = p2.y
    boxes = [
        p2.d.textbbox((cols[0], y0), nr, font=mono),
        p2.d.textbbox((cols[1], y0), text, font=body),
        p2.d.textbbox((cols[2] + 120, y0), fee, font=mono),
    ]
    p2.d.text((cols[0], y0), nr, font=mono, fill=30)
    p2.d.text((cols[1], y0), text, font=body, fill=30)
    p2.d.text((cols[2] + 120, y0), fee, font=mono, fill=30)
    if nr == "4":
        for b in boxes:
            p2.mark("p2-row4", b)
    p2.y = max(b[3] for b in boxes) + 18
    p2.d.line((110, p2.y - 9, W - 110, p2.y - 9), fill=150, width=1)
p2.y += 30
p2.line("§ 5  Inkrafttreten", bold, gap=12)
for t in [
    "Diese Satzung tritt am Tage nach ihrer öffentlichen Bekanntmachung",
    "in Kraft.",
]:
    p2.line(t, gap=8)
p2.y += 40
p2.line("Rheinfurt, den 14. März 2003", body, gap=50)
p2.line("gez. Dr. Brandner", body, gap=6)
p2.line("Oberbürgermeister", small)
p2.y = H - 90
p2.line("- 2 -", small, x=W // 2 - 20)

pages = [p1.finish(), p2.finish()]
buf = io.BytesIO()
pages[0].save(buf, "PDF", save_all=True, append_images=pages[1:], resolution=127, quality=45)
pdf = buf.getvalue()


def region(page_no, box, pad=10):
    x0, y0, x1, y1 = box
    x0, y0, x1, y1 = x0 - pad, y0 - pad, x1 + pad, y1 + pad
    return {
        "page": page_no,
        "x": round(x0 / W, 4),
        "y": round(y0 / H, 4),
        "width": round((x1 - x0) / W, 4),
        "height": round((y1 - y0) / H, 4),
    }


row4 = region(2, p2.marks["p2-row4"], pad=8)
due = region(1, p1.marks["p1-faellig"])
b64 = base64.b64encode(pdf).decode("ascii")
chunks = [b64[i : i + 100] for i in range(0, len(b64), 100)]
ts = (
    "// Generated by e2e/ui-lab/fixtures/make_scan_pdf.py - do not edit by hand.\n"
    "import type { ChunkRegion } from '@/shared/api/types'\n\n"
    "/** A two-page scan without a text layer (Verwaltungsgebuehrensatzung), as base64 PDF. */\n"
    "export const SCAN_PDF_BASE64 = [\n"
    + "\n".join(f"  '{c}'," for c in chunks)
    + "\n].join('')\n\n"
    "/** Frame of tariff row Nr. 4 on page 2. */\n"
    f"export const SCAN_REGION_ROW4: ChunkRegion = {json.dumps(row4)}\n\n"
    "/** Frame of section 3 (1) on page 1. */\n"
    f"export const SCAN_REGION_DUE: ChunkRegion = {json.dumps(due)}\n"
)
OUT.write_text(ts, encoding="ascii")
print(len(pdf), "bytes", row4, due)
