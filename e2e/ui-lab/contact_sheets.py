#!/usr/bin/env python3
"""Cross sheets and reviewer file lists for the consistency review of one variant.

    .venv/bin/python -I ui-lab/contact_sheets.py --dir <capture output> --out <dir>
                     [--full] [--schemes light] [--widths 1280,320] [--filter <text>]
                     [--per-reviewer 2] [--cell-width 420] [--max-height 2400]

Cross sheets: per screen (area × scenario × scheme × width) one JPEG in <out>/sheets/ with that
screen of every type (connector, page, ...) side by side, so differences between the types show;
<out>/index.html lists them. Reviewer lists: the types are split into groups of --per-reviewer,
and <out>/reviewers/<n>-<types>.txt names the files one reviewer looks at: every light shot, every
320 px shot and at least one dark shot per area; a __full shot byte-identical to its viewport
shot is left out.
"""
import argparse
import hashlib
import html
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import cell, font  # noqa: E402  (shared drawing helpers of the variant comparison)

from PIL import Image, ImageDraw  # noqa: E402


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument('--dir', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--full', action='store_true', help='cross sheets from the __full shots')
    p.add_argument('--schemes', default='light')
    p.add_argument('--widths', default='1280,320')
    p.add_argument('--filter', default='', help='only screens whose key contains this text')
    p.add_argument('--per-reviewer', type=int, default=2)
    p.add_argument('--cell-width', type=int, default=420)
    p.add_argument('--max-height', type=int, default=2400)
    return p.parse_args()


def shots(directory):
    """file name -> (area, type, scenario, scheme, width, full) for every capture.mjs screenshot."""
    found = {}
    for name in sorted(os.listdir(directory)):
        stem, ext = os.path.splitext(name)
        parts = stem.split('__')
        full = parts[-1] == 'full'
        if full:
            parts = parts[:-1]
        if ext != '.png' or len(parts) != 5 or not parts[4].isdigit():
            continue
        found[name] = (*parts[:4], parts[4], full)
    return found


def md5(path):
    with open(path, 'rb') as f:
        return hashlib.md5(f.read()).hexdigest()


def cross_sheets(args, found, types):
    schemes = args.schemes.split(',')
    widths = args.widths.split(',')
    screens = defaultdict(dict)
    for name, (area, typ, scenario, scheme, width, full) in found.items():
        if full != args.full or scheme not in schemes or width not in widths:
            continue
        key = f'{area}__{scenario}__{scheme}__{width}'
        if args.filter and args.filter not in key:
            continue
        screens[key][typ] = os.path.join(args.dir, name)
    os.makedirs(os.path.join(args.out, 'sheets'), exist_ok=True)
    label_font, head_font = font(16), font(14)
    written = []
    for key in sorted(screens):
        width = int(key.rsplit('__', 1)[1])
        cell_width = min(args.cell_width, width)
        present = [t for t in types if t in screens[key]]
        cells = [(t, cell(screens[key][t], cell_width, args.max_height, label_font)) for t in present]
        gap, head = 12, 30
        sheet = Image.new('RGB', (sum(c.width for _, c in cells) + gap * (len(cells) + 1), max(c.height for _, c in cells) + head + 28 + gap), 'white')
        draw = ImageDraw.Draw(sheet)
        draw.text((gap, 6), key, fill=(60, 60, 60), font=head_font)
        x = gap
        for t, c in cells:
            draw.rectangle([x - 1, head + 25, x + c.width, head + 26 + c.height], outline=(180, 180, 180))
            draw.text((x, head + 2), t, fill=(0, 0, 0), font=label_font)
            sheet.paste(c, (x, head + 26))
            x += c.width + gap
        path = os.path.join(args.out, 'sheets', key + '.jpg')
        sheet.save(path, 'JPEG', quality=80, optimize=True)
        written.append((key, os.path.relpath(path, args.out), len(present)))
    groups = defaultdict(list)
    for key, rel, n in written:
        groups[key.split('__')[0]].append((key, rel, n))
    parts = ['<!doctype html><meta charset="utf-8"><title>UI-Lab Querbögen</title>',
             '<style>body{font:14px system-ui;margin:16px}img{max-width:100%;border:1px solid #ccc}figure{margin:0 0 24px}</style>',
             f'<h1>Querbögen: {html.escape(args.dir)}</h1>']
    for area in sorted(groups):
        parts.append(f'<h2>{html.escape(area)}</h2>')
        for key, rel, n in groups[area]:
            parts.append(f'<figure><figcaption>{html.escape(key)} ({n})</figcaption><img loading="lazy" src="{html.escape(rel)}"></figure>')
    with open(os.path.join(args.out, 'index.html'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(parts))
    return len(written)


def reviewer_lists(args, found, types):
    by_name = {name: meta for name, meta in found.items()}
    os.makedirs(os.path.join(args.out, 'reviewers'), exist_ok=True)
    skipped = 0
    lists = []
    for i in range(0, len(types), args.per_reviewer):
        group = types[i:i + args.per_reviewer]
        chosen, dark_areas = [], set()
        for name, (area, typ, scenario, scheme, width, full) in sorted(by_name.items()):
            if typ not in group:
                continue
            if full:
                viewport = name.replace('__full.png', '.png')
                if viewport in by_name and md5(os.path.join(args.dir, name)) == md5(os.path.join(args.dir, viewport)):
                    skipped += 1
                    continue
            if scheme == 'light' or width == '320':
                chosen.append(name)
            elif (typ, area) not in dark_areas:
                dark_areas.add((typ, area))
                chosen.append(name)
        path = os.path.join(args.out, 'reviewers', f'{len(lists) + 1}-{"-".join(group)}.txt')
        with open(path, 'w', encoding='utf-8') as f:
            f.write('\n'.join(os.path.join(os.path.abspath(args.dir), n) for n in chosen) + '\n')
        lists.append((path, len(chosen)))
    return lists, skipped


def main():
    args = parse_args()
    found = shots(args.dir)
    if not found:
        raise SystemExit(f'no capture.mjs screenshots in {args.dir}')
    types = sorted({meta[1] for meta in found.values()})
    sheets = cross_sheets(args, found, types)
    lists, skipped = reviewer_lists(args, found, types)
    print(f'{sheets} cross sheets -> {args.out}/sheets, {len(lists)} reviewer lists -> {args.out}/reviewers '
          f'({skipped} __full shots identical to their viewport shot left out)')
    for path, n in lists:
        print(f'  {os.path.basename(path)}: {n} files')


if __name__ == '__main__':
    main()
