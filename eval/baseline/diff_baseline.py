#!/usr/bin/env python3
"""Diffs two committed retrieval baselines (eval/baseline/comic-characters.json) and renders a
Markdown table of every metric that is lower on one side ("pr") than on the other ("main").

Used by .github/workflows/retrieval-regression.yml (issue #228, ADR-0013 decision 6): the
label-triggered run on a pull request compares the PR branch's own committed baseline against the
one on `main`, so a PR that quietly lowers the baseline (making its own regression job pass
against an already-weakened target) is visible in the PR comment instead of hidden behind a green
check mark.

This is purely informational and always exits 0 — it never fails the job by itself. The actual gate
against an unjustified baseline lowering is the review procedure in eval/baseline/README.md; this
script only makes a silent lowering visible to the reviewer, it does not replace their judgment.

Stdlib-only, no dependency on the Java harness or Gradle build — same rationale as the corpus and
golden-dataset generators under eval/generator/ (ADR-0011, decision 2).
"""

import argparse
import json
import sys

METRICS = ("hitRateAt5", "mrr", "ndcgAt10", "recallAt10")


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
    for group, pr_metrics in pr_groups.items():
        main_metrics = main_groups.get(group)
        if main_metrics is None:
            # A group that only exists on the PR side (e.g. a golden-dataset extension) has
            # nothing on main to be lower *than* — not a lowering, nothing to flag.
            continue
        for metric in METRICS:
            main_value = main_metrics.get(metric)
            pr_value = pr_metrics.get(metric)
            if main_value is None or pr_value is None:
                continue
            if pr_value < main_value:
                lowered.append((group, metric, main_value, pr_value))

    removed_groups = set(main_groups) - set(pr_groups)
    for group in sorted(removed_groups):
        main_metrics = main_groups[group]
        for metric in METRICS:
            main_value = main_metrics.get(metric)
            if main_value is None:
                continue
            lowered.append((group, metric, main_value, None))

    return lowered


def render(lowered, main_ref, pr_ref):
    if not lowered:
        return (
            "### Baseline-Vergleich gegenüber `{main_ref}`\n\n"
            "Keine Gruppe/Metrik in diesem PR-Branch (`{pr_ref}`) liegt unter dem auf "
            "`{main_ref}` committeten Wert — keine stille Baseline-Absenkung erkennbar.\n"
        ).format(main_ref=main_ref, pr_ref=pr_ref)

    lines = [
        "### Baseline-Vergleich gegenüber `{}`".format(main_ref),
        "",
        "**Achtung: {} Metrik(en) in diesem PR-Branch liegen unter dem auf `{}` committeten "
        "Wert.** Das ist keine automatische Blockade — aber jede Absenkung muss im PR begründet "
        "sein (siehe `eval/baseline/README.md`), sonst besteht der Regressionsjob künftiger PRs "
        "nur noch gegen einen bereits abgesenkten Maßstab.".format(len(lowered), main_ref),
        "",
        "| Gruppe | Metrik | {} | {} | Differenz |".format(main_ref, pr_ref),
        "|---|---|---|---|---|",
    ]
    for group, metric, main_value, pr_value in lowered:
        if pr_value is None:
            lines.append(
                "| {} | {} | {:.3f} | entfernt | entfernt |".format(group, metric, main_value)
            )
        else:
            lines.append(
                "| {} | {} | {:.3f} | {:.3f} | {:+.3f} |".format(
                    group, metric, main_value, pr_value, pr_value - main_value
                )
            )
    lines.append("")
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--main", required=True, help="Path to main's baseline JSON")
    parser.add_argument("--pr", required=True, help="Path to the PR branch's baseline JSON")
    parser.add_argument("--output", required=True, help="Markdown output file")
    parser.add_argument("--main-ref", default="main")
    parser.add_argument("--pr-ref", default="PR-Branch")
    args = parser.parse_args(argv)

    main_baseline = load(args.main)
    pr_baseline = load(args.pr)
    lowered = find_lowered(main_baseline, pr_baseline)
    markdown = render(lowered, args.main_ref, args.pr_ref)

    with open(args.output, "w", encoding="utf-8") as f:
        f.write(markdown)

    print(markdown)
    return 0


if __name__ == "__main__":
    sys.exit(main())
