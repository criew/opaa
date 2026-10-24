"""Build one vertical sprite per (variant, type, scenario, width) from light screenshots."""
import json, os, sys
from PIL import Image
S = sys.argv[1]  # scratchpad
V = os.path.join(S, 'variants')
OUT = os.path.join(S, 'site', 'shots')
os.makedirs(OUT, exist_ok=True)
VARIANTS = [v for v in ['ist', 'A', 'B', 'C', 'D', 'E', 'F'] if os.path.isdir(os.path.join(V, v))]
TYPES = ['filesystem', 'confluence', 'sharepoint', 'nextcloud', 'smb', 's3', 'gdrive', 'dropbox']
SCEN = ['small', 'scale', 'failed']
WIDTHS = ['1280', '320']
AREAS = ['wizard-empty', 'wizard-filled', 'wizard-select', 'wizard-check', 'wizard-validation', 'review', 'edit', 'edit-select', 'edit-connection', 'detail']
CAP = 2400
meta = {}
for v in VARIANTS:
    d = os.path.join(V, v)
    files = set(os.listdir(d))
    for t in TYPES:
        for sc in SCEN:
            for w in WIDTHS:
                parts = []
                for a in AREAS:
                    base = f'{a}__{t}__{sc}__light__{w}'
                    src = None
                    for cand in (base + '__full.jpg', base + '__full.png', base + '.jpg', base + '.png'):
                        if cand in files:
                            src = cand; break
                    if not src:
                        continue
                    im = Image.open(os.path.join(d, src)).convert('RGB')
                    if im.height > CAP * im.width / int(w) and int(w) == 320:
                        pass
                    tw = 960 if w == '1280' else 320
                    if im.width != tw:
                        im = im.resize((tw, round(im.height * tw / im.width)), Image.LANCZOS)
                    maxh = round(CAP * tw / int(w)) if w == '1280' else CAP
                    cut = im.height > maxh
                    if cut:
                        im = im.crop((0, 0, tw, maxh))
                    parts.append((a, im, cut))
                if not parts:
                    continue
                tw = parts[0][1].width
                H = sum(p[1].height for p in parts)
                sheet = Image.new('RGB', (tw, H), 'white')
                y = 0; areas = []
                for a, im, cut in parts:
                    sheet.paste(im, (0, y)); areas.append({'a': a, 'y': y, 'h': im.height, 'cut': cut}); y += im.height
                name = f'{v}__{t}__{sc}__{w}.jpg'
                sheet.save(os.path.join(OUT, name), quality=62, optimize=True, progressive=True)
                meta[f'{v}|{t}|{sc}|{w}'] = {'f': 'shots/' + name, 'w': tw, 'h': H, 'areas': areas}
json.dump(meta, open(os.path.join(S, 'site', 'sprites.json'), 'w'))
print(len(meta), 'sprites')
