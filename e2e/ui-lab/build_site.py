#!/usr/bin/env python3
"""Static comparison page for the current state ("Ist") and a set of drafts.

    .venv/bin/python -I ui-lab/build_site.py --config site.json --out <dir>
                                        [--scheme light] [--cap 2400] [--video-urls urls.json]

site.json names the variants with their result directories (capture.mjs output; videos from
record-flows.mjs in a "videos" subdirectory with its manifest.json) and the texts of the page; see
site-template/site.example.json. The area of a page (connector, page, ...) is the `type` of the
screenshots, so the page is not tied to one part of the product.

Per (variant, type, scenario, width) one vertical JPEG sprite holds all screens of that selection,
with their offsets in data.json, which keeps a page with hundreds of screens below the file limit
of an Artifact version. Videos are copied to <out>/videos/ unless --video-urls maps their file names
to URLs (e.g. Artifact assets), which then replace the copies.
"""
import argparse
import json
import os
import shutil

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SCENARIOS = {
    'small': {'label': 'wenig', 'text': 'Wenige Einträge je Liste.'},
    'scale': {'label': 'viel', 'text': 'Große Mengen, tiefe Hierarchien, lange Namen.'},
    'failed': {'label': 'Fehler', 'text': 'Auflisten oder Verbindungstest schlägt fehl.'},
}


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument('--config', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--scheme', default='light', choices=['light', 'dark'])
    p.add_argument('--cap', type=int, default=2400, help='maximum screen height in CSS px; longer screens are cut')
    p.add_argument('--video-urls', help='JSON object: video file name -> URL')
    return p.parse_args()


def screens(directory, scheme):
    """(type, scenario, width) -> {area: file}; a __full shot wins over the viewport shot."""
    found = {}
    for name in sorted(os.listdir(directory)):
        stem, ext = os.path.splitext(name)
        parts = stem.split('__')
        full = parts[-1] == 'full'
        if full:
            parts = parts[:-1]
        if ext not in ('.png', '.jpg') or len(parts) != 5 or parts[3] != scheme or not parts[4].isdigit():
            continue
        area, typ, scenario, _, width = parts
        slot = found.setdefault((typ, scenario, width), {})
        if full or area not in slot:
            slot[area] = os.path.join(directory, name)
    return found


def sprite(parts, width, cap, path):
    """Stacks the screens of one selection; returns the offsets of each area."""
    target = 960 if width >= 1280 else width
    max_h = round(cap * target / width)
    images = []
    for area, src in parts:
        im = Image.open(src).convert('RGB')
        if im.width != target:
            im = im.resize((target, round(im.height * target / im.width)), Image.LANCZOS)
        cut = im.height > max_h
        if cut:
            im = im.crop((0, 0, target, max_h))
        images.append((area, im, cut))
    sheet = Image.new('RGB', (target, sum(im.height for _, im, _ in images)), 'white')
    y, areas = 0, []
    for area, im, cut in images:
        sheet.paste(im, (0, y))
        areas.append({'a': area, 'y': y, 'h': im.height, 'cut': cut})
        y += im.height
    sheet.save(path, quality=62, optimize=True, progressive=True)
    return {'w': target, 'h': sheet.height, 'areas': areas}


def main():
    args = parse_args()
    with open(args.config, encoding='utf-8') as f:
        cfg = json.load(f)
    base = os.path.dirname(os.path.abspath(args.config))
    urls = {}
    if args.video_urls:
        with open(args.video_urls, encoding='utf-8') as f:
            urls = json.load(f)
    shots_dir = os.path.join(args.out, 'shots')
    os.makedirs(shots_dir, exist_ok=True)
    scenarios = cfg.get('scenarios') or DEFAULT_SCENARIOS
    area_order = list((cfg.get('areas') or {}).keys())
    sprites, videos, types, widths, areas_seen = {}, {}, [], set(), []

    for variant in cfg['variants']:
        vid = variant['id']
        directory = os.path.join(base, variant['dir'])
        if not os.path.isdir(directory):
            print(f'skip {vid}: {directory} missing')
            continue
        for (typ, scenario, width), by_area in screens(directory, args.scheme).items():
            if scenario not in scenarios:
                continue
            for a in by_area:
                if a not in areas_seen:
                    areas_seen.append(a)
            order = [a for a in area_order if a in by_area] + sorted(a for a in by_area if a not in area_order)
            name = f'{vid}__{typ}__{scenario}__{width}.jpg'
            meta = sprite([(a, by_area[a]) for a in order], int(width), args.cap, os.path.join(shots_dir, name))
            sprites[f'{vid}|{typ}|{scenario}|{width}'] = {'f': f'shots/{name}', **meta}
            if typ not in types:
                types.append(typ)
            widths.add(width)
        manifest = os.path.join(directory, 'videos', 'manifest.json')
        if os.path.exists(manifest):
            with open(manifest, encoding='utf-8') as f:
                for v in json.load(f):
                    if v.get('scheme', 'light') != args.scheme or not v.get('type'):
                        continue
                    src = os.path.join(directory, 'videos', v['file'])
                    published = f'{vid}__{v["file"]}'
                    if published in urls:
                        url = urls[published]
                    elif os.path.exists(src):
                        os.makedirs(os.path.join(args.out, 'videos'), exist_ok=True)
                        shutil.copyfile(src, os.path.join(args.out, 'videos', published))
                        url = f'videos/{published}'
                    else:
                        continue
                    videos.setdefault(f'{vid}|{v["type"]}|{v.get("scenario", "default")}', []).append({'url': url, 'name': v['name']})

    labels = cfg.get('types') or {}
    page = {
        'title': cfg.get('title', 'UI-Lab'),
        'intro': cfg.get('intro', ''),
        'dimension': cfg.get('dimension', 'Bereich'),
        'variants': [{k: v for k, v in variant.items() if k != 'dir'} for variant in cfg['variants']],
        'scoreNote': cfg.get('scoreNote', ''),
        'learned': cfg.get('learned', []),
        'footer': cfg.get('footer', ''),
        'types': {t: labels.get(t, t) for t in list(labels) + [t for t in types if t not in labels] if t in types},
        'scenarios': scenarios,
        'widths': sorted(widths, key=lambda w: -int(w)),
        'areas': {a: (cfg.get('areas') or {}).get(a, a) for a in area_order + [a for a in areas_seen if a not in area_order] if a in areas_seen},
        'cap': args.cap,
        'sprites': sprites,
        'videos': videos,
    }
    with open(os.path.join(args.out, 'data.json'), 'w', encoding='utf-8') as f:
        json.dump(page, f, ensure_ascii=False)
    shutil.copyfile(os.path.join(HERE, 'site-template', 'index.html'), os.path.join(args.out, 'index.html'))
    print(f'{len(sprites)} sprites, {sum(len(v) for v in videos.values())} videos -> {args.out}')


if __name__ == '__main__':
    main()
