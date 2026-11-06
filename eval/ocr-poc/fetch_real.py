"""Builds the real-scan corpus from real/sources.json: downloads each source PDF, keeps the listed
pages and rasterizes them at 300 dpi into an image-only PDF (scan-real.pdf), so the old OCR text
layer of the source is gone and every variant reads the pixels itself. Writes manifest.json in the
format of build_corpus.py, so the run_*.py runners work unchanged with --profiles real.

The source PDFs are official works (§ 5 UrhG) and stay outside the repository; see real/SOURCES.md.

Usage: python fetch_real.py --out work/corpus-real   (needs pypdfium2 and pillow)
"""

import argparse
import json
import urllib.request
from pathlib import Path

import pypdfium2 as pdfium

HERE = Path(__file__).resolve().parent
# some municipal servers reject non-browser user agents
UA = {"User-Agent": "Mozilla/5.0 (compatible; opaa-ocr-poc)"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--dpi", type=int, default=300)
    args = p.parse_args()
    sources = json.loads((HERE / "real" / "sources.json").read_text(encoding="utf-8"))
    manifest = []
    for s in sources:
        d = args.out / s["id"]
        d.mkdir(parents=True, exist_ok=True)
        src = d / "source.pdf"
        if not src.exists():
            src.write_bytes(urllib.request.urlopen(urllib.request.Request(s["url"], headers=UA), timeout=120).read())
        pdf = pdfium.PdfDocument(str(src))
        imgs = [pdf[n - 1].render(scale=args.dpi / 72, grayscale=True).to_pil().convert("L") for n in s["pages"]]
        imgs[0].save(d / "scan-real.pdf", save_all=True, append_images=imgs[1:], resolution=args.dpi)
        for n, img in zip(s["pages"], imgs):
            img.save(d / f"page-{n}.png")
        manifest.append({"id": s["id"], "pages": len(imgs)})
        print(s["id"], len(imgs), "pages", flush=True)
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
