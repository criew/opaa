"""Scores the real-scan runs (work/out-real/<variant>/real/<id>.md) against the hand-read check
points in real/truth/<id>.json and prints a Markdown table per variant.

Measures:
- checks: share of check points found verbatim; both sides are normalised the same way (quotes,
  dashes, Markdown markup, line-break hyphenation) and compared without whitespace, so spacing
  differences such as "Str.108" vs "Str. 108" do not count, but every character does
- §: the same, restricted to check points containing a paragraph sign
- table rows: a truth row counts when one Markdown table row of the output holds all its cells in
  the same column order (a cell may hold more text, e.g. a second line)
- CER of the short truth paragraphs against the best-matching span of the output
- seconds per page and peak memory from timing.json

Usage: python evaluate_real.py [--out work/out-real] [--json work/results-real.json]
"""

import argparse
import json
import re
import statistics
import unicodedata
from pathlib import Path

import jiwer
from rapidfuzz import fuzz

HERE = Path(__file__).resolve().parent


def norm(text):
    text = unicodedata.normalize("NFKC", text)
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"[„“”«»″]", '"', text)
    text = re.sub(r"[‚‘’`´]", "'", text)
    text = re.sub(r"[‐‑‒–—―−]", "-", text)
    text = re.sub(r"[#*_]+", " ", text)
    text = re.sub(r"\.{4,}", " ", text)  # dot leaders in tables of contents
    text = re.sub(r"([a-zäöüß])- +([a-zäöüß])", r"\1\2", text)  # hyphenation at line breaks
    return re.sub(r"\s+", " ", text).strip()


def tight(text):
    return re.sub(r"\s+", "", norm(text))


def table_rows(markdown):
    rows = []
    for line in markdown.splitlines():
        if line.count("|") >= 2 and not re.fullmatch(r"[\s|:\-]+", line):
            rows.append([tight(c) for c in line.strip().strip("|").split("|")])
    return rows


def row_found(truth_row, rows):
    want = [tight(c) for c in truth_row]
    for cells in rows:
        pos = 0
        for w in want:
            while pos < len(cells) and w not in cells[pos]:
                pos += 1
            if pos == len(cells):
                break
            pos += 1
        else:
            return True
    return False


def paragraph_cer(ref, hyp):
    ref, hyp = norm(ref), norm(hyp)
    if not hyp:
        return 1.0
    al = fuzz.partial_ratio_alignment(ref, hyp)
    if al is None or al.score < 50:
        return 1.0
    return min(1.0, jiwer.cer(ref, hyp[al.dest_start:al.dest_end]))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, default=Path("work/out-real"))
    p.add_argument("--json", type=Path, default=Path("work/results-real.json"))
    args = p.parse_args()
    truths = {json.loads(f.read_text(encoding="utf-8"))["id"]: json.loads(f.read_text(encoding="utf-8")) for f in sorted((HERE / "real" / "truth").glob("*.json"))}
    results, details = {}, {}
    for vdir in sorted(d for d in args.out.iterdir() if d.is_dir()):
        timing = json.loads((vdir / "timing.json").read_text()) if (vdir / "timing.json").exists() else {}
        found = total = para_found = para_total = rows_ok = rows_total = 0
        cers, per_doc = [], {}
        for doc_id, t in truths.items():
            f = vdir / "real" / f"{doc_id}.md"
            out = f.read_text(encoding="utf-8") if f.exists() else ""
            hay = tight(out)
            missed = [c for c in t["checks"] if tight(c) not in hay]
            found += len(t["checks"]) - len(missed)
            total += len(t["checks"])
            paras = [c for c in t["checks"] if "§" in c]
            para_total += len(paras)
            para_found += sum(1 for c in paras if c not in missed)
            rows = table_rows(out)
            ok = [row_found(r, rows) for r in t["table_rows"]]
            rows_ok += sum(ok)
            rows_total += len(ok)
            doc_cers = [paragraph_cer(r, out) for r in t["paragraphs"]]
            cers += doc_cers
            per_doc[doc_id] = {"missed": missed, "table_rows_ok": f"{sum(ok)}/{len(ok)}", "cer": [round(c, 3) for c in doc_cers]}
        runs = [r for r in timing.get("runs", []) if r["profile"] == "real"]
        pages = sum(r["pages"] for r in runs) or 1
        results[vdir.name] = {
            "checks": f"{found}/{total}",
            "checks_share": round(found / total, 3),
            "paragraph_checks": f"{para_found}/{para_total}",
            "table_rows": f"{rows_ok}/{rows_total}",
            "cer_mean": round(statistics.mean(cers), 4),
            "cer_median": round(statistics.median(cers), 4),
            "sec_per_page": round(sum(r["seconds"] for r in runs) / pages, 1),
            "errors": sum(1 for r in runs if r.get("error")),
            "peak_rss_mb": timing.get("peak_rss_mb"),
        }
        details[vdir.name] = per_doc
    args.json.write_text(json.dumps({"summary": results, "details": details}, ensure_ascii=False, indent=1), encoding="utf-8")
    cols = ["checks", "checks_share", "paragraph_checks", "table_rows", "cer_mean", "cer_median", "sec_per_page", "errors", "peak_rss_mb"]
    print("| Variante | " + " | ".join(cols) + " |")
    print("|---" * (len(cols) + 1) + "|")
    for k, v in sorted(results.items(), key=lambda kv: -kv[1]["checks_share"]):
        print(f"| {k} | " + " | ".join(str(v[c]) for c in cols) + " |")


if __name__ == "__main__":
    main()
