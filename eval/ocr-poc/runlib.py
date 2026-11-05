"""Shared loop for the variant runners: one process per variant, models loaded once, every
document timed on its own, the process's peak RSS recorded at the end."""

import json
import os
import time
from pathlib import Path

try:
    import resource
except ImportError:  # Windows
    resource = None


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


def peak_rss_mb():
    if resource:
        # an external engine (ocrmypdf/tesseract) runs as a child process; count the larger peak
        return max(resource.getrusage(r).ru_maxrss for r in (resource.RUSAGE_SELF, resource.RUSAGE_CHILDREN)) / 1024
    import psutil  # Windows (ROCm builds of torch for Radeon run natively there): peak working set

    return psutil.Process().memory_info().peak_wset / 2**20


def run(variant, convert, corpus, out, profiles, docs=None, setup=None):
    """Converts every scan of the selected profiles and documents. timing.json is rewritten after each
    document, so an aborted run keeps the times of what it finished; a later run (another profile, a
    re-run) merges into it, replacing only the entries it repeats. Each entry names its device."""
    device = os.environ.get("OCR_DEVICE", "cpu")
    t0 = time.perf_counter()
    state = setup() if setup else None
    load_s = time.perf_counter() - t0
    manifest = load_manifest(corpus)
    path = out / variant / "timing.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    timing = json.loads(path.read_text()) if path.exists() else {"variant": variant, "runs": []}
    timing["model_load_seconds"] = round(load_s, 1)
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
            result = {"id": entry["id"], "profile": prof, "pages": entry["pages"], "seconds": round(secs, 2), "device": device, "error": err}
            timing["runs"] = [r for r in timing["runs"] if (r["id"], r["profile"]) != (entry["id"], prof)] + [result]
            timing["peak_rss_mb"] = max(timing.get("peak_rss_mb") or 0, round(peak_rss_mb()))
            path.write_text(json.dumps(timing, indent=1))
            print(variant, prof, entry["id"], f"{secs:.1f}s", err or "", flush=True)


def cli():
    import argparse

    p = argparse.ArgumentParser()
    p.add_argument("--corpus", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--profiles", default="gut,mittel,fax,schlecht")
    p.add_argument("--docs", default="")
    p.add_argument("--variant", default="")
    a = p.parse_args()
    return a, [x for x in a.profiles.split(",") if x], {d for d in a.docs.split(",") if d}
