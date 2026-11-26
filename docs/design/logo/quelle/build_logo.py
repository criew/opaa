#!/usr/bin/env python3
"""Builds the temporary OPAA logo files from the free font Saira (SIL OFL, files beside this script).

The wordmark "opaa" and the mark (an underlined "o") are converted to outlines, so no file needs the
font at display time. The second "a" and the underline are Hellblau; the rest is Dunkelblau, or white
on dark surfaces. Needs `pnpm install` in frontend/ and e2e/ plus `pnpm run install:browsers` in e2e/
(Prettier, Playwright's Chromium; CHROMIUM_PATH names another Chromium binary).
Run from the repository root after changing anything here:

    pip install fonttools==4.69.1 brotli==1.2.1 && python3 docs/design/logo/quelle/build_logo.py

It rewrites the SVGs in docs/design/logo/, the copies used by app, handbook and homepage, the path
data of frontend/src/shared/ui/brandMarkPaths.ts (formatted with Prettier), the homepage's inline
wordmark and the PNG icons.
Temporary until the vector artwork of the brand concept replaces it.
"""

import subprocess
import sys
from pathlib import Path

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[4]
SRC = Path(__file__).resolve().parent
OUT = SRC.parent

DUNKELBLAU = "#242855"
HELLBLAU = "#009DDB"
WHITE = "#FFFFFF"

WORD = "opaa"
TAGLINE = "open project ai assistant"
# Underline below the accented letter, in em: gap below the baseline and stroke thickness.
UNDERLINE_GAP = 0.12
UNDERLINE_THICKNESS = 0.08
# Tagline in em of the wordmark: gap below the underline; its width matches the wordmark.
TAGLINE_GAP = 0.2


def fmt(value: float) -> str:
    text = f"{value:.1f}"
    return text[:-2] if text.endswith(".0") else text


def outline(font_file: Path, text: str, size: float, x0: float, y0: float):
    """Paths of each glyph (baseline at y0) and the ink bounds of each, in output units."""
    font = TTFont(font_file)
    glyph_set = font.getGlyphSet()
    cmap = font.getBestCmap()
    scale = size / font["head"].unitsPerEm
    pen_x = 0.0
    glyphs = []
    for char in text:
        name = cmap[ord(char)]
        glyph = glyph_set[name]
        if char != " ":
            path_pen = SVGPathPen(glyph_set, ntos=fmt)
            glyph.draw(TransformPen(path_pen, (scale, 0, 0, -scale, x0 + pen_x * scale, y0)))
            bounds_pen = BoundsPen(glyph_set)
            glyph.draw(bounds_pen)
            x_min, y_min, x_max, y_max = bounds_pen.bounds
            box = (x0 + (pen_x + x_min) * scale, y0 - y_max * scale,
                   x0 + (pen_x + x_max) * scale, y0 - y_min * scale)
            glyphs.append((char, path_pen.getCommands(), box))
        pen_x += glyph.width
    return glyphs


def rect(x0, y0, x1, y1) -> str:
    return f"M{fmt(x0)} {fmt(y0)}H{fmt(x1)}V{fmt(y1)}H{fmt(x0)}Z"


def wordmark(size: float, with_tagline: bool):
    """Returns (base path, accent path, width, height) with the ink starting at 0/0."""
    glyphs = outline(SRC / "saira-latin-600.woff", WORD, size, 0, 0)
    left = min(g[2][0] for g in glyphs)
    top = min(g[2][1] for g in glyphs)
    right = max(g[2][2] for g in glyphs)
    under_top = UNDERLINE_GAP * size
    under_bottom = under_top + UNDERLINE_THICKNESS * size
    bottom = max(max(g[2][3] for g in glyphs), under_bottom)
    # Shift so the ink box starts at 0/0.
    dx, dy = -left, -top
    glyphs = outline(SRC / "saira-latin-600.woff", WORD, size, dx, dy)
    base_d = "".join(g[1] for g in glyphs[:-1])
    a_box = glyphs[-1][2]
    accent_d = glyphs[-1][1] + rect(a_box[0], dy + under_top, a_box[2], dy + under_bottom)
    width = right - left
    height = bottom - top
    if with_tagline:
        probe = outline(SRC / "saira-latin-500.woff", TAGLINE, 100, 0, 0)
        probe_width = max(g[2][2] for g in probe) - min(g[2][0] for g in probe)
        tag_size = 100 * width / probe_width
        probe = outline(SRC / "saira-latin-500.woff", TAGLINE, tag_size, 0, 0)
        tag_left = min(g[2][0] for g in probe)
        tag_top = min(g[2][1] for g in probe)
        tag_bottom = max(g[2][3] for g in probe)
        y_top = height + TAGLINE_GAP * size
        tag = outline(SRC / "saira-latin-500.woff", TAGLINE, tag_size, -tag_left, y_top - tag_top)
        base_d += "".join(g[1] for g in tag)
        height = y_top + (tag_bottom - tag_top)
    return base_d, accent_d, width, height


def mark(size: float):
    """The underlined "o": (glyph path, underline path, width, height), ink at 0/0."""
    glyph = outline(SRC / "saira-latin-600.woff", "o", size, 0, 0)[0]
    x0, y0, x1, _ = glyph[2]
    under_top = UNDERLINE_GAP * size
    under_bottom = under_top + UNDERLINE_THICKNESS * size
    glyph = outline(SRC / "saira-latin-600.woff", "o", size, -x0, -y0)[0]
    underline = rect(0, under_top - y0, x1 - x0, under_bottom - y0)
    return glyph[1], underline, x1 - x0, under_bottom - y0


def svg(width, height, body, comment):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(width)} {fmt(height)}" '
        f'width="{fmt(width)}" height="{fmt(height)}" role="img" aria-label="opaa">\n'
        f"  <!-- {comment} -->\n  <title>opaa</title>\n{body}</svg>\n"
    )


def logo_file(with_tagline: bool, text_color: str) -> str:
    base, accent, width, height = wordmark(64, with_tagline)
    body = f'  <path fill="{text_color}" d="{base}"/>\n  <path fill="{HELLBLAU}" d="{accent}"/>\n'
    kind = "Wortmarke mit Unterzeile" if with_tagline else "Wortmarke"
    return svg(width, height, body, f"Temporäre {kind} (Saira, in Pfade gewandelt) bis zur Reinzeichnung")


def mark_file(text_color: str) -> str:
    glyph, underline, width, height = mark(64)
    pad = (height - width) / 2
    body = (
        f'  <g transform="translate({fmt(pad)} 0)">\n'
        f'    <path fill="{text_color}" d="{glyph}"/>\n'
        f'    <path fill="{HELLBLAU}" d="{underline}"/>\n  </g>\n'
    )
    return svg(height, height, body, "Temporäres Bildzeichen: unterstrichenes o, bis zur Reinzeichnung")


def icon_file(tile: str, text_color: str) -> str:
    # The mark fills 72 % of the tile height, centred.
    target = 64 * 0.72
    glyph, underline, width, height = mark(100)
    scale = target / height
    tx = (64 - width * scale) / 2
    ty = (64 - height * scale) / 2
    body = (
        f'  <rect width="64" height="64" rx="14" fill="{tile}"/>\n'
        f'  <g transform="translate({fmt(tx)} {fmt(ty)}) scale({scale:.4f})">\n'
        f'    <path fill="{text_color}" d="{glyph}"/>\n'
        f'    <path fill="{HELLBLAU}" d="{underline}"/>\n  </g>\n'
    )
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64" '
        'role="img" aria-label="opaa">\n'
        "  <!-- Temporäres App-Icon: unterstrichenes o auf Kachel, bis zur Reinzeichnung -->\n"
        f"  <title>opaa</title>\n{body}</svg>\n"
    )


def brand_mark_ts() -> str:
    base, accent, width, height = wordmark(64, False)
    tbase, taccent, twidth, theight = wordmark(64, True)
    glyph, underline, mwidth, mheight = mark(64)
    return f"""/**
 * Outlines of the temporary OPAA wordmark and mark, generated by
 * docs/design/logo/quelle/build_logo.py from the free font Saira - do not edit by hand.
 * `base` paths take the text colour, `accent` paths the brand's Hellblau.
 */
export const WORDMARK = {{
  width: {fmt(width)},
  height: {fmt(height)},
  base: '{base}',
  accent: '{accent}',
}} as const

export const WORDMARK_WITH_TAGLINE = {{
  width: {fmt(twidth)},
  height: {fmt(theight)},
  base: '{tbase}',
  accent: '{taccent}',
}} as const

export const MARK = {{
  width: {fmt(mwidth)},
  height: {fmt(mheight)},
  base: '{glyph}',
  accent: '{underline}',
}} as const

/** Hellblau of the brand concept: the accented letter and the underline. */
export const MARK_ACCENT = '{HELLBLAU}'
"""


def homepage_wordmark(page: str) -> str:
    """Replaces the inline wordmark of the homepage navigation between its two markers."""
    base, accent, width, height = wordmark(64, False)
    start, end = "<!-- opaa-wortmarke -->", "<!-- /opaa-wortmarke -->"
    inline = (
        f'<svg class="wordmark" viewBox="0 0 {fmt(width)} {fmt(height)}" role="img" '
        f'aria-label="opaa"><path fill="currentColor" d="{base}"/>'
        f'<path fill="{HELLBLAU}" d="{accent}"/></svg>'
    )
    head, rest = page.split(start, 1)
    _, tail = rest.split(end, 1)
    return f"{head}{start}{inline}{end}{tail}"


def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    print(f"wrote {path.relative_to(ROOT)}")


def render_png(svg_path: Path, size: int, target: Path) -> None:
    script = f"""
const {{ chromium }} = require('@playwright/test');
(async () => {{
  const browser = await chromium.launch({{ executablePath: process.env.CHROMIUM_PATH || undefined }});
  const page = await browser.newPage({{ viewport: {{ width: {size}, height: {size} }} }});
  const svg = require('fs').readFileSync({str(svg_path)!r}, 'utf8')
    .replace('rx="14"', 'rx="0"');
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
    files = {
        "opaa-logo.svg": logo_file(True, DUNKELBLAU),
        "opaa-logo-hell.svg": logo_file(True, WHITE),
        "opaa-wortmarke.svg": logo_file(False, DUNKELBLAU),
        "opaa-wortmarke-hell.svg": logo_file(False, WHITE),
        "opaa-mark.svg": mark_file(DUNKELBLAU),
        "opaa-mark-hell.svg": mark_file(WHITE),
        "opaa-icon.svg": icon_file(DUNKELBLAU, WHITE),
        "opaa-icon-weiss.svg": icon_file(WHITE, DUNKELBLAU),
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
        (ROOT / target).write_bytes((OUT / f"opaa-icon-{size}.png").read_bytes())
        print(f"wrote {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
