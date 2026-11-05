"""Shared loop for the variant runners: one process per variant, models loaded once, every
document timed on its own, the process's peak RSS recorded at the end."""

import json
import resource
import sys
import time
from pathlib import Path


def load_manifest(corpus):
    """The corpus manifest; while the build still runs, the documents rendered so far."""
    path = corpus / "manifest.json"
    if path.exists():
        return json.loads(path.read_text())
    import subprocess

    out = []
    for d in sorted(p for p in corpus.iterdir() if (p / "truth.json").exists()):
        info = subprocess.run(["pdfinfo", str(d / "text.pdf")], capture_output=True, text=True).stdout
        pages = int(next(l.split()[-1] for l in info.splitlines() if l.startswith("Pages:")))
        out.append({"id": d.name, "pages": pages})
    return out


def run(variant, convert, corpus, out, profiles, docs=None, setup=None):
    t0 = time.perf_counter()
    state = setup() if setup else None
    load_s = time.perf_counter() - t0
    manifest = load_manifest(corpus)
    results = []
    for prof in profiles:
        for entry in manifest:
            if docs and entry["id"] not in docs:
                continue
            src = corpus / entry["id"] / f"scan-{prof}.pdf"
            dst = out / variant / prof / f"{entry['id']}.md"
            dst.parent.mkdir(parents=True, exist_ok=True)
            t = time.perf_counter()
            try:
                text = convert(state, src)
                err = None
            except Exception as e:  # a failure is a result, not an abort
                text, err = "", repr(e)
            secs = time.perf_counter() - t
            dst.write_text(text, encoding="utf-8")
            results.append({"id": entry["id"], "profile": prof, "pages": entry["pages"], "seconds": round(secs, 2), "error": err})
            print(variant, prof, entry["id"], f"{secs:.1f}s", err or "", flush=True)
    rss_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    (out / variant / "timing.json").write_text(json.dumps({"variant": variant, "model_load_seconds": round(load_s, 1), "peak_rss_mb": round(rss_mb), "runs": results}, indent=1))


def cli():
    import argparse

    p = argparse.ArgumentParser()
    p.add_argument("--corpus", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--profiles", default="gut,mittel,fax")
    p.add_argument("--docs", default="")
    p.add_argument("--variant", default="")
    a = p.parse_args()
    return a, [x for x in a.profiles.split(",") if x], {d for d in a.docs.split(",") if d}
