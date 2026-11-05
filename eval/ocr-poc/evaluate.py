"""Scores every variant output under work/out against the corpus ground truth and writes
work/results.json (--results) plus a Markdown summary table (stdout).

Measures per document and scan profile: character and word error rate, headings recovered as a
line and marked as a Markdown heading (fuzzy), §-headings marked with their exact "§ N" prefix, fee-table cells recovered inside a Markdown table row,
golden answer passages still findable, and BM25 recall@5 of the golden queries (only where the
whole corpus ran). Timing comes from each variant's timing.json.
"""

import argparse
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import jiwer
from rank_bm25 import BM25Okapi
from rapidfuzz import fuzz

REPO = Path(__file__).resolve().parents[2]
GOLDEN = REPO / "eval" / "golden" / "verwaltung.json"
# "§ 12" at the start of a heading, matched exactly: a "$ 12" or "8 12" from the OCR does not count
PARAGRAPH_PREFIX = re.compile(r"§ \d+\b")


def norm(text):
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"^\s*\|?\s*:?-{3,}.*$", " ", text, flags=re.M)
    text = re.sub(r"[#*|`_]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def tokens(text):
    return re.findall(r"\w+", text.lower())


def heading_scores(headings, output):
    lines = [l.strip() for l in output.splitlines() if l.strip()]
    marked = [re.sub(r"^#+\s*", "", l) for l in lines if l.startswith("#")]
    plain = [re.sub(r"^[#*\s]+|[*\s]+$", "", l) for l in lines]
    found = sum(1 for h in headings if any(fuzz.ratio(h, l) >= 90 for l in plain))
    marked_n = sum(1 for h in headings if any(fuzz.ratio(h, l) >= 90 for l in marked))
    return found, marked_n


def table_score(rows, output):
    """Cells of the truth table found in one Markdown table row of the output, per row."""
    out_rows = [[c.strip() for c in l.strip().strip("|").split("|")] for l in output.splitlines() if l.count("|") >= 2]
    ok = 0
    for row in rows:
        best = 0
        for cand in out_rows:
            hit = sum(1 for cell in row if any(fuzz.ratio(cell, c) >= 90 for c in cand))
            best = max(best, hit)
        ok += best
    return ok, sum(len(r) for r in rows)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--corpus", type=Path, default=Path("work/corpus"))
    p.add_argument("--out", type=Path, default=Path("work/out"))
    p.add_argument("--results", type=Path, default=Path("work/results.json"))
    args = p.parse_args()
    golden = json.loads(GOLDEN.read_text(encoding="utf-8"))
    truth = {}
    for d in sorted(x for x in args.corpus.iterdir() if (x / "truth.json").exists()):
        meta = json.loads((d / "truth.json").read_text(encoding="utf-8"))
        truth[d.name] = {**meta, "text": (d / "truth.txt").read_text(encoding="utf-8")}
    file_to_id = {v["file"]: k for k, v in truth.items()}

    results = {}
    for vdir in sorted(x for x in args.out.iterdir() if x.is_dir()):
        timing = json.loads((vdir / "timing.json").read_text()) if (vdir / "timing.json").exists() else {}
        for pdir in sorted(x for x in vdir.iterdir() if x.is_dir()):
            docs = {f.stem: f.read_text(encoding="utf-8") for f in pdir.glob("*.md")}
            agg = defaultdict(list)
            sums = defaultdict(int)
            for doc_id, out in docs.items():
                t = truth[doc_id]
                ref, hyp = norm(t["text"]), norm(out)
                agg["cer"].append(jiwer.cer(ref, hyp) if hyp else 1.0)
                agg["wer"].append(jiwer.wer(ref, hyp) if hyp else 1.0)
                f, m = heading_scores(t["headings"], out)
                prefixes = [hit.group(0) for h in t["headings"] if (hit := PARAGRAPH_PREFIX.match(h))]
                marked = [re.sub(r"^#+\s*", "", l).strip() for l in out.splitlines() if l.startswith("#")]
                sums["para_headings"] += len(prefixes)
                sums["para_headings_marked"] += sum(1 for pre in prefixes if any(PARAGRAPH_PREFIX.match(o) and PARAGRAPH_PREFIX.match(o).group(0) == pre for o in marked))
                sums["headings"] += len(t["headings"])
                sums["headings_found"] += f
                sums["headings_marked"] += m
                sums["para_truth"] += t["text"].count("§")
                sums["para_out"] += min(out.count("§"), t["text"].count("§"))
                if t["table"]:
                    ok, total = table_score(t["table"], out)
                    sums["cells_ok"] += ok
                    sums["cells"] += total
            spans = [q for q in golden for f in q["expected_documents"] if file_to_id.get(f) in docs and q.get("answer_span")]
            found = sum(1 for q in spans for f in q["expected_documents"] if file_to_id.get(f) in docs and fuzz.partial_ratio(norm(q["answer_span"]), norm(docs[file_to_id[f]])) >= 95)
            recall = None
            if len(docs) == len(truth):
                ids = sorted(docs)
                bm = BM25Okapi([tokens(docs[i]) for i in ids])
                hits = total = 0
                for q in golden:
                    exp = {file_to_id.get(f) for f in q["expected_documents"]} - {None}
                    if not exp:
                        continue
                    scores = bm.get_scores(tokens(q["query"]))
                    top = {ids[i] for i in sorted(range(len(ids)), key=lambda i: -scores[i])[:5]}
                    hits += bool(exp & top)
                    total += 1
                recall = round(hits / total, 3)
            runs = [r for r in timing.get("runs", []) if r["profile"] == pdir.name and r["id"] in docs]
            if len(runs) != len(docs):
                print(f"warning: {vdir.name}/{pdir.name}: times for {len(runs)} of {len(docs)} documents", file=sys.stderr)
            pages = sum(r["pages"] for r in runs) or 1
            results[f"{vdir.name}/{pdir.name}"] = {
                "docs": len(docs),
                "cer": round(statistics.mean(agg["cer"]), 4),
                "wer": round(statistics.mean(agg["wer"]), 4),
                "headings_found": round(sums["headings_found"] / max(sums["headings"], 1), 3),
                "headings_marked": round(sums["headings_marked"] / max(sums["headings"], 1), 3),
                "paragraph_headings_marked": round(sums["para_headings_marked"] / sums["para_headings"], 3) if sums["para_headings"] else None,
                "paragraph_signs": round(sums["para_out"] / max(sums["para_truth"], 1), 3),
                "table_cells": round(sums["cells_ok"] / sums["cells"], 3) if sums["cells"] else None,
                "answer_spans": f"{found}/{len(spans)}",
                "bm25_recall5": recall,
                "sec_per_page": round(sum(r["seconds"] for r in runs) / pages, 2),
                "timed_docs": len(runs),
                "errors": sum(1 for r in runs if r["error"]),
                "peak_rss_mb": timing.get("peak_rss_mb"),
                "model_load_s": timing.get("model_load_seconds"),
            }
    # the ground truth itself as the upper bound for retrieval
    ids = sorted(truth)
    bm = BM25Okapi([tokens(truth[i]["text"]) for i in ids])
    hits = total = 0
    for q in golden:
        exp = {file_to_id.get(f) for f in q["expected_documents"]} - {None}
        if exp:
            scores = bm.get_scores(tokens(q["query"]))
            top = {ids[i] for i in sorted(range(len(ids)), key=lambda i: -scores[i])[:5]}
            hits += bool(exp & top)
            total += 1
    results["truth"] = {"bm25_recall5": round(hits / total, 3)}
    args.results.write_text(json.dumps(results, indent=1, ensure_ascii=False), encoding="utf-8")
    cols = ["docs", "cer", "wer", "headings_found", "headings_marked", "paragraph_headings_marked", "paragraph_signs", "table_cells", "answer_spans", "bm25_recall5", "sec_per_page", "timed_docs", "errors", "peak_rss_mb"]
    print("| Variante/Profil | " + " | ".join(cols) + " |")
    print("|---" * (len(cols) + 1) + "|")
    for k, v in results.items():
        print(f"| {k} | " + " | ".join(str(v.get(c, "")) for c in cols) + " |")


if __name__ == "__main__":
    main()
