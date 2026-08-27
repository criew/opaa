#!/usr/bin/env python3
"""Diffs every committed retrieval baseline under `eval/baseline/*.json` and renders a Markdown
section per file listing every metric that is lower on one side ("pr") than on the other ("main").

Used by .github/workflows/baseline-diff.yml (issue #228, ADR-0013 decision 6; generalized to all
baseline files in issue #941): the label-triggered run on a pull request compares the PR branch's
own committed baselines against the ones on `main`, so a PR that quietly lowers any domain's
baseline (making its own regression job pass against an already-weakened target) is visible in the
PR comment instead of hidden behind a green check mark. Issue #941: the script originally only ever
looked at `comic-characters.json`, so a PR that lowered `city-landmarks.json` (or any baseline added
later) got a silent, false "no lowering" verdict — it now iterates every `*.json` file under the
given directories, so a newly added domain's baseline is covered without a code change here.

This is purely informational and always exits 0 — it never fails the job by itself. The actual gate
against an unjustified baseline lowering is the review procedure in eval/baseline/README.md; this
script only makes a silent lowering visible to the reviewer, it does not replace their judgment.

Stdlib-only, no dependency on the Java harness or Gradle build — same rationale as the corpus and
golden-dataset generators under eval/generator/ (ADR-0011, decision 2).
"""

import argparse
import json
import sys
from pathlib import Path

METRICS = ("hitRateAt5", "mrr", "ndcgAt10", "recallAt10", "allExpectedDocumentsHitAt10")

# Issue #306: the two case counts BaselineComparator reads for the case-count check on
# group/metric pairs whose mean tolerance is tighter than one case's worth of shift. They live in
# the same "groups" object as METRICS above, so a PR that lowers one silently narrows a group's
# protection the same way a lowered mean would — worth surfacing here for the same reason, even
# though this script never gates anything (see module docstring).
CASE_COUNT_FIELDS = ("hitCountAt5", "hitCountAt10")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def find_lowered(main_baseline, pr_baseline):
    """Returns every (group, metric, main_value, pr_value) where pr_value < main_value, plus one
    entry per metric for every group present on `main` but absent on the PR branch (pr_value is
    `None` in that case).

    Dropping a compared group entirely is itself a silent loss of coverage — a PR that removes a
    group loses every check that group used to run, without any single metric ever showing up as
    "lower". The original version of this function only ever iterated `pr_groups`, so a removed
    group was invisible to it (issue #304 review finding: PR #673 removed the `language:de` group
    without the diff reporting anything, even though four checks disappeared)."""
    lowered = []
    main_groups = main_baseline.get("groups", {})
    pr_groups = pr_baseline.get("groups", {})
    all_fields = METRICS + CASE_COUNT_FIELDS
    for group, pr_metrics in pr_groups.items():
        main_metrics = main_groups.get(group)
        if main_metrics is None:
            # A group that only exists on the PR side (e.g. a golden-dataset extension) has
            # nothing on main to be lower *than* — not a lowering, nothing to flag.
            continue
        for metric in all_fields:
            main_value = main_metrics.get(metric)
            pr_value = pr_metrics.get(metric)
            if main_value is None or pr_value is None:
                continue
            if pr_value < main_value:
                lowered.append((group, metric, main_value, pr_value))

    removed_groups = set(main_groups) - set(pr_groups)
    for group in sorted(removed_groups):
        main_metrics = main_groups[group]
        for metric in all_fields:
            main_value = main_metrics.get(metric)
            if main_value is None:
                continue
            lowered.append((group, metric, main_value, None))

    return lowered


def render_table(lowered, main_ref, pr_ref):
    lines = [
        "**Achtung: {} Metrik(en) in diesem PR-Branch liegen unter dem auf `{}` committeten "
        "Wert.** Das ist keine automatische Blockade — aber jede Absenkung muss im PR begründet "
        "sein (siehe `eval/baseline/README.md`), sonst besteht der Regressionsjob künftiger PRs "
        "nur noch gegen einen bereits abgesenkten Maßstab.".format(len(lowered), main_ref),
        "",
        "| Gruppe | Metrik | {} | {} | Differenz |".format(main_ref, pr_ref),
        "|---|---|---|---|---|",
    ]
    for group, metric, main_value, pr_value in lowered:
        # Case counts (issue #306) are integers — render them without the fractional-metric
        # formatting so a genuine "3 -> 2" reads as a case count, not a fraction.
        fmt = "{}" if metric in CASE_COUNT_FIELDS else "{:.3f}"
        signed_fmt = "{:+d}" if metric in CASE_COUNT_FIELDS else "{:+.3f}"
        if pr_value is None:
            lines.append(
                ("| {{}} | {{}} | {} | entfernt | entfernt |".format(fmt)).format(
                    group, metric, main_value
                )
            )
        else:
            lines.append(
                ("| {{}} | {{}} | {} | {} | {} |".format(fmt, fmt, signed_fmt)).format(
                    group, metric, main_value, pr_value, pr_value - main_value
                )
            )
    lines.append("")
    return "\n".join(lines)


def render(results, main_ref, pr_ref):
    """`results` is a list of (filename, lowered) pairs, one per baseline file — the report names
    every file's verdict explicitly (issue #941), rather than a single verdict across all files
    that would hide which domain's baseline actually regressed."""
    lines = ["### Baseline-Vergleich gegenüber `{}`".format(main_ref), ""]
    for filename, lowered in results:
        lines.append("#### `{}`".format(filename))
        lines.append("")
        if not lowered:
            lines.append(
                "Keine Gruppe/Metrik in diesem PR-Branch (`{}`) liegt unter dem auf `{}` "
                "committeten Wert — keine stille Baseline-Absenkung erkennbar.".format(
                    pr_ref, main_ref
                )
            )
            lines.append("")
        else:
            lines.append(render_table(lowered, main_ref, pr_ref))
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--main-dir", required=True, help="Directory with main's baseline JSON files"
    )
    parser.add_argument(
        "--pr-dir", required=True, help="Directory with the PR branch's baseline JSON files"
    )
    parser.add_argument("--output", required=True, help="Markdown output file")
    parser.add_argument("--main-ref", default="main")
    parser.add_argument("--pr-ref", default="PR-Branch")
    args = parser.parse_args(argv)

    pr_dir = Path(args.pr_dir)
    main_dir = Path(args.main_dir)
    results = []
    for pr_path in sorted(pr_dir.glob("*.json")):
        pr_baseline = load(pr_path)
        main_path = main_dir / pr_path.name
        main_baseline = load(main_path) if main_path.exists() else {"groups": {}}
        lowered = find_lowered(main_baseline, pr_baseline)
        results.append((pr_path.name, lowered))

    markdown = render(results, args.main_ref, args.pr_ref)

    with open(args.output, "w", encoding="utf-8") as f:
        f.write(markdown)

    print(markdown)
    return 0


if __name__ == "__main__":
    sys.exit(main())
