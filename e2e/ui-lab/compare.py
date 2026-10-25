#!/usr/bin/env python3
"""Side-by-side comparison sheets and a metrics summary for capture.mjs runs.

    .venv/bin/python -I ui-lab/compare.py --variants A=<dir>,B=<dir>,... --out <dir>
                       [--full] [--per-sheet 3] [--max-height 2400] [--filter <substring>]

Per screen key (file name without extension, identical across variants) one PNG sheet in
<out>/sheets/ with the variants next to each other, labelled and in their original pixels, never
scaled; more than --per-sheet variants are split over several sheets (<key>__p<n>.png). A missing
screen shows as a grey "nicht erfasst" cell. Viewport shots by default; --full uses the __full
shots (cut, not scaled, at --max-height). <out>/index.html lists all sheets, <out>/metrics-summary.md holds one
table per aspect with the variants as columns.
"""
import argparse
import html
import json
import os
import statistics
from collections import Counter, defaultdict

from PIL import Image, ImageDraw, ImageFont


def positive(value):
    n = int(value)
    if n < 1:
        raise argparse.ArgumentTypeError(f'needs a whole number of at least 1, got {value}')
    return n


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument('--variants', required=True, help='A=dir,B=dir,...')
    p.add_argument('--out', required=True)
    p.add_argument('--full', action='store_true', help='use the __full screenshots')
    p.add_argument('--per-sheet', type=positive, default=3, help='variants per sheet; more go to further sheets')
    p.add_argument('--max-height', type=int, default=2400)
    p.add_argument('--filter', default='', help='only screen keys containing this text')
    a = p.parse_args()
    variants = []
    for part in a.variants.split(','):
        name, _, d = part.partition('=')
        if not d:
            raise SystemExit(f'bad --variants entry: {part}')
        variants.append((name.strip(), d.strip()))
    a.variants = variants
    return a


def font(size):
    for f in ('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
              '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def screen_keys(variants, full, flt):
    keys = set()
    for _, d in variants:
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if not f.endswith('.png'):
                continue
            base = f[:-4]
            is_full = base.endswith('__full')
            if is_full != full:
                continue
            if flt and flt not in base:
                continue
            keys.add(base)
    return sorted(keys)


def shot_width(key, fallback=1280):
    """The capture width named in a screen key (`…__<width>` or `…__<width>__full`)."""
    parts = [p for p in key.split('__') if p != 'full']
    return int(parts[-1]) if parts and parts[-1].isdigit() else fallback


def cell(path, max_h, label_font, placeholder_width):
    """One cell: the screenshot pixel for pixel, cut at `max_h`; a placeholder when it is missing."""
    if path and os.path.exists(path):
        im = Image.open(path).convert('RGB')
        if im.height > max_h:
            im = im.crop((0, 0, im.width, max_h))
        return im
    ph = Image.new('RGB', (placeholder_width, 200), (225, 225, 225))
    ImageDraw.Draw(ph).text((16, 90), 'nicht erfasst', fill=(90, 90, 90), font=label_font)
    return ph


def build_sheets(key, variants, args, out_dir):
    """The variant named `ist` (the current state) opens every partial sheet, so each draft is
    always shown next to it."""
    ist = [v for v in variants if v[0].lower() == 'ist']
    rest = [v for v in variants if v not in ist]
    size = max(1, args.per_sheet - len(ist))
    chunks = [ist + rest[i:i + size] for i in range(0, len(rest), size)] or [ist]
    return [build_sheet(key if len(chunks) == 1 else f'{key}__p{n}', key, chunk, args, out_dir)
            for n, chunk in enumerate(chunks, 1)]


def build_sheet(name, key, variants, args, out_dir):
    lf = font(18)
    hf = font(14)
    cells = []
    for variant, d in variants:
        cells.append((variant, cell(os.path.join(d, key + '.png'), args.max_height, lf, shot_width(key))))
    gap, head = 16, 34
    W = sum(c.width for _, c in cells) + gap * (len(cells) + 1)
    H = max(c.height for _, c in cells) + head + 30 + gap
    sheet = Image.new('RGB', (W, H), (255, 255, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((gap, 6), name, fill=(60, 60, 60), font=hf)
    x = gap
    for variant, c in cells:
        draw.rectangle([x - 1, head + 29, x + c.width, head + 30 + c.height], outline=(180, 180, 180))
        draw.text((x, head + 4), variant, fill=(0, 0, 0), font=lf)
        sheet.paste(c, (x, head + 30))
        x += c.width + gap
    path = os.path.join(out_dir, 'sheets', name + '.png')
    sheet.save(path, 'PNG')
    return name, path


def load_metrics(d):
    rows = []
    p = os.path.join(d, 'metrics.jsonl')
    if not os.path.exists(p):
        return rows
    with open(p, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return rows


def med(values):
    values = [v for v in values if isinstance(v, (int, float))]
    return round(statistics.median(values), 1) if values else '–'


def summarize(rows, run):
    ok = [r for r in rows if r.get('status') == 'ok']
    light = [r for r in ok if r.get('scheme') == 'light']
    s = {}
    s['Laufzeit (s)'] = run.get('seconds', '–')
    s['Screenshots'] = len(ok)
    s['not-found (Zustände)'] = len({(r['area'], r['type'], r['scenario']) for r in rows if r.get('status') == 'not-found'})
    s['Fehler im Ablauf'] = sum(1 for r in rows if r.get('status') == 'error')
    s['Überlauf bei 320 px'] = sum(1 for r in ok if r.get('width') == 320 and r.get('overflowX'))
    s['Shots mit deaktiviertem Absenden'] = sum(1 for r in ok if r.get('disabledSubmits'))
    sev = Counter()
    for r in ok:
        sev.update(r.get('alertSeverities') or {})
    s['Alerts gesamt'] = sum(sev.values())
    s['Alerts je Schwere'] = ', '.join(f'{k} {v}' for k, v in sev.most_common()) or '–'
    s['Validierung: Felder aria-invalid (Median)'] = med([r.get('invalidFields') for r in ok if r['area'] == 'wizard-validation'])
    axe_nodes = Counter()
    for r in light:
        for v in (r.get('axe') or {}).get('violations') or []:
            axe_nodes[v['id']] += v['nodes']
    s['axe-Knoten (hell, gesamt)'] = sum(axe_nodes.values())
    s['axe target-size'] = axe_nodes.get('target-size', 0)
    s['Kontrastverstöße (alle Schemata)'] = sum((r.get('axe') or {}).get('contrastViolations') or 0 for r in ok)
    s['Fokus versteckt / verdeckt'] = f"{sum(r.get('focusHidden') or 0 for r in ok)} / {sum(r.get('focusObscured') or 0 for r in ok)}"
    s['Schriftgrößen (Median)'] = med([r.get('fontSizes') for r in light])
    s['Textfarben (Median)'] = med([r.get('colors') for r in light])
    s['Wörter im Hauptbereich (Median)'] = med([r.get('words') for r in light])
    s['Bedienwörter ohne Daten (Median, small)'] = med([r.get('uiWords') for r in light if r.get('scenario') in ('small', 'default')])
    for key, label in (('helpTextsPerForm', 'Hilfetexte je Formular'), ('helpTextLines', 'Hilfetext > 1 Zeile'), ('uiWords', 'Bedienwörter')):
        s[f'Über Budget: {label} (Shots)'] = sum(1 for r in ok if key in (r.get('budgetViolations') or []))
    s['Schema nicht wie angefordert (Shots)'] = sum(1 for r in ok if r.get('appliedScheme') and r.get('appliedScheme') != r.get('scheme'))
    s['Fokussierbare Elemente (Median)'] = med([r.get('focusable') for r in light])
    s['Dokumenthöhe 1280 px (Median)'] = med([r.get('docHeight') for r in light if r.get('width') == 1280])
    s['Dokumenthöhe 1280 px (Max)'] = max([r.get('docHeight') or 0 for r in light if r.get('width') == 1280] or [0])
    s['Verschiedene Seitentitel'] = len({r.get('title') for r in ok})
    return s, axe_nodes


def md_table(header, rows):
    out = ['| ' + ' | '.join(header) + ' |', '|' + '---|' * len(header)]
    for row in rows:
        out.append('| ' + ' | '.join(str(c) for c in row) + ' |')
    return '\n'.join(out)


def per_area(rows):
    by = defaultdict(list)
    for r in rows:
        if r.get('status') == 'ok' and r.get('scheme') == 'light' and r.get('width') == 1280:
            by[r['area']].append(r)
    return {a: (med([r.get('docHeight') for r in rs]), med([r.get('words') for r in rs]), sum(r.get('alertCount') or 0 for r in rs)) for a, rs in by.items()}


def main():
    args = parse_args()
    os.makedirs(os.path.join(args.out, 'sheets'), exist_ok=True)
    keys = screen_keys(args.variants, args.full, args.filter)
    sheets = []
    for key in keys:
        sheets.extend(build_sheets(key, args.variants, args, args.out))
    # index.html grouped by area
    groups = defaultdict(list)
    for key, path in sheets:
        groups[key.split('__')[0]].append((key, os.path.relpath(path, args.out)))
    parts = ['<!doctype html><meta charset="utf-8"><title>UI-Lab Vergleich</title>',
             '<style>body{font:14px system-ui;margin:16px}img{max-width:100%;border:1px solid #ccc}'
             'h2{margin-top:32px}figure{margin:0 0 24px}</style>',
             f"<h1>Vergleich: {html.escape(', '.join(n for n, _ in args.variants))}</h1>",
             '<p><a href="metrics-summary.md">metrics-summary.md</a></p>']
    for area in sorted(groups):
        parts.append(f'<h2>{html.escape(area)}</h2>')
        for key, rel in groups[area]:
            parts.append(f'<figure><figcaption>{html.escape(key)}</figcaption><img loading="lazy" src="{html.escape(rel)}"></figure>')
    with open(os.path.join(args.out, 'index.html'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(parts))

    # metrics summary
    names = [n for n, _ in args.variants]
    summaries, axes, areas = {}, {}, {}
    for name, d in args.variants:
        rows = load_metrics(d)
        run = {}
        rp = os.path.join(d, 'run.json')
        if os.path.exists(rp):
            with open(rp, encoding='utf-8') as f:
                run = json.load(f)
        summaries[name], axes[name] = summarize(rows, run)
        areas[name] = per_area(rows)
    lines = ['# Kennzahlen je Variante', '']
    metric_names = list(next(iter(summaries.values())).keys()) if summaries else []
    lines.append(md_table(['Kennzahl'] + names, [[m] + [summaries[n][m] for n in names] for m in metric_names]))
    lines += ['', '## Je Bereich (hell, 1280 px): Höhe Median · Wörter Median · Alerts', '']
    all_areas = sorted({a for n in names for a in areas[n]})
    lines.append(md_table(['Bereich'] + names, [[a] + [' · '.join(str(x) for x in areas[n].get(a, ('–', '–', '–'))) for n in names] for a in all_areas]))
    lines += ['', '## axe-Verstöße (hell, Knoten je Regel)', '']
    ids = sorted({i for n in names for i in axes[n]}, key=lambda i: -sum(axes[n].get(i, 0) for n in names))
    lines.append(md_table(['Regel'] + names, [[i] + [axes[n].get(i, 0) for n in names] for i in ids]) if ids else 'keine')
    with open(os.path.join(args.out, 'metrics-summary.md'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print(f'{len(sheets)} sheets -> {args.out}/sheets, summary -> {args.out}/metrics-summary.md')


if __name__ == '__main__':
    main()
