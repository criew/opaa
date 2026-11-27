#!/usr/bin/env python3
"""Builds the opaa logo files of app, handbook and homepage from the marketing originals beside
this script.

The originals are the vector artwork of the VISION marketing (style guide): `wortmarke.svg` and
`wortmarke-auf-blau.svg` (wordmark without tagline, for light and dark grounds) and
`zeichen-dunkel.svg`/`zeichen-hell.svg` (the Hellblau "a" with its underline on a tile, for
favicon and app icons). They are used unchanged in shape: the style guide forbids rebuilding the
logo. This script only copies them, normalises colours and extracts the path data `BrandMark`
draws. Needs `pnpm install` in frontend/ and e2e/ plus `pnpm run install:browsers` in e2e/
(Prettier, Playwright's Chromium; CHROMIUM_PATH names another Chromium binary).
Run from the repository root after replacing an original:

    python3 docs/design/logo/original/build_logo.py
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SRC = Path(__file__).resolve().parent
OUT = SRC.parent

DUNKELBLAU = "#242855"
HELLBLAU = "#009DDB"
WHITE = "#FFFFFF"


def shapes(svg_text: str):
    """(colour, path) of every path and rect, colours resolved from the file's class styles."""
    classes = {
        name: colour
        for name, colour in re.findall(r"\.(\w+)\s*\{\s*fill:\s*(#[0-9a-fA-F]+);", svg_text)
    }

    def colour(attrs: str) -> str:
        fill = re.search(r'fill="(#[0-9a-fA-F]+)"', attrs)
        cls = re.search(r'class="(\w+)"', attrs)
        value = fill.group(1) if fill else classes[cls.group(1)]
        return value.upper() if len(value) == 7 else "#" + "".join(c * 2 for c in value[1:]).upper()

    result = []
    for tag, attrs in re.findall(r"<(path|rect)\b([^>]*)/>", svg_text):
        if tag == "path":
            result.append((colour(attrs), re.search(r'\bd="([^"]+)"', attrs).group(1)))
        else:
            num = {k: float(v) for k, v in re.findall(r'\b(x|y|width|height)="([-\d.]+)"', attrs)}
            if "rx" in attrs:  # the icon tile, not part of the mark
                continue
            x, y, w, h = num.get("x", 0), num.get("y", 0), num["width"], num["height"]
            result.append((colour(attrs), f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}Z"))
    return result


def view_box(svg_text: str):
    return [float(v) for v in re.search(r'viewBox="([^"]+)"', svg_text).group(1).split()]


def split(svg_text: str):
    """(base path, accent path): Hellblau is the accent, every other colour the text colour."""
    parts = shapes(svg_text)
    base = "".join(d for c, d in parts if c != HELLBLAU)
    accent = "".join(d for c, d in parts if c == HELLBLAU)
    return base, accent


def wordmark_file(text_color: str) -> str:
    original = (SRC / "wortmarke.svg").read_text(encoding="utf-8")
    x, y, w, h = view_box(original)
    base, accent = split(original)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x:g} {y:g} {w:g} {h:g}" '
        f'width="{w:g}" height="{h:g}" role="img" aria-label="opaa">\n'
        "  <!-- Wortmarke ohne Unterzeile, aus der Originaldatei des Marketings -->\n"
        "  <title>opaa</title>\n"
        f'  <path fill="{text_color}" d="{base}"/>\n'
        f'  <path fill="{HELLBLAU}" d="{accent}"/>\n</svg>\n'
    )


def brand_mark_ts() -> str:
    word = (SRC / "wortmarke.svg").read_text(encoding="utf-8")
    sign = (SRC / "zeichen-dunkel.svg").read_text(encoding="utf-8")
    wx, wy, ww, wh = view_box(word)
    w_base, w_accent = split(word)
    _, s_accent = split(sign)
    # The mark is the Zeichen without its tile: the Hellblau "a" and its underline.
    sx, sy, sw, sh = 244.32, 0, 125.72, 156.14
    return f"""/**
 * Outlines of the opaa wordmark and mark, extracted from the marketing originals by
 * docs/design/logo/original/build_logo.py - do not edit by hand.
 * `base` paths take the text colour, `accent` paths the brand's Hellblau.
 */
export const WORDMARK = {{
  x: {wx:g},
  y: {wy:g},
  width: {ww:g},
  height: {wh:g},
  base: '{w_base}',
  accent: '{w_accent}',
}} as const

export const MARK = {{
  x: {sx:g},
  y: {sy:g},
  width: {sw:g},
  height: {sh:g},
  base: '',
  accent: '{s_accent}',
}} as const

/** Hellblau of the brand concept: the accented letter and the underline. */
export const MARK_ACCENT = '{HELLBLAU}'
"""


def homepage_wordmark(page: str) -> str:
    """Replaces the inline wordmark of the homepage navigation between its two markers."""
    original = (SRC / "wortmarke.svg").read_text(encoding="utf-8")
    x, y, w, h = view_box(original)
    base, accent = split(original)
    start, end = "<!-- opaa-wortmarke -->", "<!-- /opaa-wortmarke -->"
    inline = (
        f'<svg class="wordmark" viewBox="{x:g} {y:g} {w:g} {h:g}" role="img" '
        f'aria-label="opaa"><path fill="currentColor" d="{base}"/>'
        f'<path fill="{HELLBLAU}" d="{accent}"/></svg>'
    )
    head, rest = page.split(start, 1)
    _, tail = rest.split(end, 1)
    return f"{head}{start}{inline}{end}{tail}"


def write(path: Path, content) -> None:
    if isinstance(content, bytes):
        path.write_bytes(content)
    else:
        path.write_text(content, encoding="utf-8")
    print(f"wrote {path.relative_to(ROOT)}")


def render_png(svg_path: Path, size: int, target: Path) -> None:
    """Rasterises the icon full-bleed: operating systems mask app icons themselves."""
    script = f"""
const {{ chromium }} = require('@playwright/test');
(async () => {{
  const browser = await chromium.launch({{ executablePath: process.env.CHROMIUM_PATH || undefined }});
  const page = await browser.newPage({{ viewport: {{ width: {size}, height: {size} }} }});
  const svg = require('fs').readFileSync({str(svg_path)!r}, 'utf8').replace(/ rx="[^"]+"/, '');
  await page.setContent(`<style>html,body{{margin:0}}svg{{display:block;width:{size}px;height:{size}px}}</style>${{svg}}`);
  await page.screenshot({{ path: {str(target)!r}, omitBackground: true }});
  await browser.close();
}})();
"""
    subprocess.run(["node", "-e", script], check=True, cwd=ROOT / "e2e")
    print(f"wrote {target.relative_to(ROOT)}")


def check_tools() -> None:
    """Fails before anything is written, so a missing tool never leaves half the files rebuilt."""
    subprocess.run(
        ["node", "-e", "require.resolve('@playwright/test')"], check=True, cwd=ROOT / "e2e"
    )
    subprocess.run(["pnpm", "exec", "prettier", "--version"], check=True, cwd=ROOT / "frontend")


def main() -> int:
    check_tools()
    icon = (SRC / "zeichen-dunkel.svg").read_text(encoding="utf-8")
    files = {
        "opaa-wortmarke.svg": wordmark_file(DUNKELBLAU),
        "opaa-wortmarke-hell.svg": wordmark_file(WHITE),
        "opaa-icon.svg": icon,
        "opaa-icon-weiss.svg": (SRC / "zeichen-hell.svg").read_text(encoding="utf-8"),
    }
    for name, content in files.items():
        write(OUT / name, content)
    copies = {
        "frontend/public/favicon.svg": "opaa-icon.svg",
        "page/img/favicon.svg": "opaa-icon.svg",
        "docs/handbuch/assets/opaa-icon.svg": "opaa-icon.svg",
        "docs/handbuch/assets/opaa-wortmarke-hell.svg": "opaa-wortmarke-hell.svg",
    }
    for target, name in copies.items():
        write(ROOT / target, files[name])
    paths_ts = ROOT / "frontend/src/shared/ui/brandMarkPaths.ts"
    write(paths_ts, brand_mark_ts())
    subprocess.run(
        ["pnpm", "exec", "prettier", "--write", str(paths_ts)], check=True, cwd=ROOT / "frontend"
    )
    homepage = ROOT / "page/index.html"
    write(homepage, homepage_wordmark(homepage.read_text(encoding="utf-8")))
    for size in (32, 180, 192, 512):
        render_png(OUT / "opaa-icon.svg", size, OUT / f"opaa-icon-{size}.png")
    for target, size in (
        ("frontend/public/apple-touch-icon.png", 180),
        ("page/img/apple-touch-icon.png", 180),
        ("page/img/opaa-icon-512.png", 512),
    ):
        write(ROOT / target, (OUT / f"opaa-icon-{size}.png").read_bytes())
    return 0


if __name__ == "__main__":
    sys.exit(main())
