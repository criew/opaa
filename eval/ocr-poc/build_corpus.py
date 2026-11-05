"""Builds the OCR measurement corpus from eval/corpus/verwaltung.

Each Markdown document becomes: a ground-truth text file, a rendered text PDF, and per scan
profile an image-only PDF (the "scan") whose text is known. Gebührenordnungen get their fee
positions appended as a ruled table so that table recognition has a known answer.

Usage: python build_corpus.py --out work/corpus [--docs id,id,...] [--profiles gut,mittel,fax]
"""

import argparse
import io
import json
import random
import re
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "eval" / "corpus" / "verwaltung"

CSS = """
@page { size: A4; margin: 22mm 20mm; }
body { font-family: 'DejaVu Serif', serif; font-size: 11pt; line-height: 1.35; }
h1 { font-size: 16pt; } h2 { font-size: 13pt; } h3 { font-size: 11.5pt; }
table { border-collapse: collapse; width: 100%; margin-top: 8pt; }
th, td { border: 0.8pt solid #000; padding: 3pt 5pt; text-align: left; vertical-align: top; }
"""

POSITION = re.compile(r"^### Gebührenposition (\d+): (.+)$")
AMOUNT = re.compile(r"Gebühr in Höhe von ([\d.,]+ Euro)")


def strip_front_matter(text):
    if text.startswith("---"):
        end = text.index("\n---", 3)
        return text[end + 4 :].lstrip("\n")
    return text


def fee_table(body):
    """Rows (Nr, Amtshandlung, Gebühr) for every fee position with an amount."""
    rows, current = [], None
    for line in body.splitlines():
        m = POSITION.match(line)
        if m:
            current = (m.group(1), m.group(2))
            continue
        if current:
            a = AMOUNT.search(line)
            if a:
                rows.append([current[0], current[1], a.group(1)])
                current = None
    return rows


def table_markdown(rows):
    out = ["", "## Gebührenverzeichnis (Übersicht)", "", "| Nr. | Amtshandlung | Gebühr |", "|---|---|---|"]
    out += [f"| {r[0]} | {r[1]} | {r[2]} |" for r in rows]
    return "\n".join(out) + "\n"


def plain_text(markdown):
    """The ground-truth reading text: Markdown syntax removed, one line per block."""
    lines = []
    for line in markdown.splitlines():
        line = re.sub(r"^#+\s*", "", line)
        if re.match(r"^\|?-{3}", line.replace("|", "-").strip("-|")) or set(line.strip()) <= set("|-"):
            continue
        line = line.strip().strip("|").replace(" | ", " ")
        line = re.sub(r"\*\*|__|\*|`", "", line)
        if line.strip():
            lines.append(line.strip())
    return "\n".join(lines)


def degrade(img, profile, rng):
    """A deterministic imitation of a scan: skew, blur, noise, contrast; 'fax' binarizes at 200 dpi."""
    g = img.convert("L")
    if profile == "gut":
        angle, noise, blur, dpi_scale = rng.uniform(-0.4, 0.4), 4, 0.0, 1.0
    elif profile == "mittel":
        angle, noise, blur, dpi_scale = rng.uniform(-1.5, 1.5), 12, 0.6, 1.0
    elif profile == "fax":
        angle, noise, blur, dpi_scale = rng.uniform(-1.0, 1.0), 6, 0.0, 200 / 300
    else:
        raise ValueError(profile)
    g = g.rotate(angle, resample=Image.BICUBIC, expand=False, fillcolor=255)
    if blur:
        g = g.filter(ImageFilter.GaussianBlur(blur))
    a = np.asarray(g).astype(np.float32)
    if profile == "mittel":
        a = 30 + a * 0.80  # greyish paper, weaker toner
    nprng = np.random.default_rng(rng.randrange(1 << 30))
    a = a + nprng.normal(0, noise, a.shape)
    if profile == "fax":
        g = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
        g = g.resize((int(g.width * dpi_scale), int(g.height * dpi_scale)), Image.BILINEAR)
        a = np.asarray(g).astype(np.float32)
        a = np.where(a > 150, 255, 0).astype(np.float32)
        speckle = nprng.random(a.shape) < 0.0015
        a[speckle] = 255 - a[speckle]
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--docs", default="")
    p.add_argument("--profiles", default="gut,mittel,fax")
    args = p.parse_args()

    import weasyprint  # imported late: only the render step needs it

    wanted = {d for d in args.docs.split(",") if d}
    profiles = [x for x in args.profiles.split(",") if x]
    files = sorted(f for f in SOURCE.glob("verwaltung-*.md") if not wanted or f.stem.split("_")[0] in wanted)
    manifest = []
    for f in files:
        doc_id = f.stem.split("_")[0]
        body = strip_front_matter(f.read_text(encoding="utf-8"))
        rows = fee_table(body)
        if rows:
            body += table_markdown(rows)
        d = args.out / doc_id
        d.mkdir(parents=True, exist_ok=True)
        (d / "truth.md").write_text(body, encoding="utf-8")
        (d / "truth.txt").write_text(plain_text(body), encoding="utf-8")
        headings = [re.sub(r"^#+\s*", "", l) for l in body.splitlines() if re.match(r"^#{1,3} ", l)]
        (d / "truth.json").write_text(json.dumps({"file": f.name, "headings": headings, "table": rows}, ensure_ascii=False, indent=1), encoding="utf-8")
        html = subprocess.run(["pandoc", "-f", "gfm", "-t", "html"], input=body, capture_output=True, text=True, check=True).stdout
        weasyprint.HTML(string=f"<html lang='de'><meta charset='utf-8'><style>{CSS}</style><body>{html}</body></html>").write_pdf(d / "text.pdf")
        subprocess.run(["pdftoppm", "-r", "300", "-gray", "-png", str(d / "text.pdf"), str(d / "page")], check=True)
        pages = sorted(d.glob("page-*.png"))
        rng = random.Random(doc_id)
        for prof in profiles:
            imgs = [degrade(Image.open(pg), prof, rng) for pg in pages]
            dpi = 200 if prof == "fax" else 300
            imgs[0].save(d / f"scan-{prof}.pdf", save_all=True, append_images=imgs[1:], resolution=dpi)
            imgs[0].save(d / f"scan-{prof}-p1.png")
        for pg in pages:
            pg.unlink()
        manifest.append({"id": doc_id, "file": f.name, "pages": len(pages), "table_rows": len(rows)})
        print(doc_id, len(pages), "pages", len(rows), "table rows", flush=True)
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
