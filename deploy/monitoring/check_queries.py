#!/usr/bin/env python3
"""Checks the queries of the dashboards and alerts against the metrics OPAA has.

Every metric a dashboard or alert names must appear in the metric table of the handbook
(docs/handbuch/betrieb/ueberwachung-und-fehlerbehebung.md) or come from outside OPAA
(kube-state-metrics, Prometheus itself). With --probe the names are checked against a real scrape
of /actuator/prometheus instead. With --promtool every dashboard query is parsed by
`promtool check rules`, after the Grafana variables are replaced by plain values.

Usage: check_queries.py --dashboards DIR --rules RULES_JSON [--rules ...] [--probe FILE] [--promtool]
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

HANDBOOK = "docs/handbuch/betrieb/ueberwachung-und-fehlerbehebung.md"

# Series that come from outside the backend: kube-state-metrics, the scrape and the alerts.
EXTERNAL = ("kube_deployment_", "up", "ALERTS")

# Metrics of an open pull request that the alerts and dashboards already use while their rows are
# still missing in the handbook table. A name here that the table lists fails the check, so the
# list empties itself.
PENDING: set[str] = set()

VARIABLES = (("${__url_time_range}", ""), ("$__range_s", "86400"), ("$__range", "1d"), ("$__interval", "1h"),
             ("$namespace", ".*"), ("$host", ".*"), ("$connector", ".*"), ("$model", ".*"),
             ("$price_input", "1"), ("$price_output", "1"))

SELECTOR = re.compile(r"(?<![\w:\"])([a-zA-Z_:][a-zA-Z0-9_:]*)\{")
LABEL_VALUES = re.compile(r"label_values\(([a-zA-Z_:][a-zA-Z0-9_:]*)")


def dashboard_queries(directory):
    def walk(panels):
        for p in panels:
            yield p
            yield from walk(p.get("panels", []))

    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        d = json.load(open(path))
        name = os.path.basename(path)
        for p in walk(d.get("panels", [])):
            for t in p.get("targets", []):
                yield name, p.get("title") or "", t["expr"]
        for a in d.get("annotations", {}).get("list", []):
            if a.get("expr"):
                yield name, "annotation " + a["name"], a["expr"]
        for v in d.get("templating", {}).get("list", []):
            q = v.get("query")
            if v.get("type") == "query":
                yield name, "variable " + v["name"], q["query"] if isinstance(q, dict) else q


def rule_queries(path):
    for g in json.load(open(path))["groups"]:
        for r in g["rules"]:
            yield os.path.basename(path), r.get("alert") or r.get("record"), r["expr"]


def names(expr):
    found = set(SELECTOR.findall(expr)) | set(LABEL_VALUES.findall(expr))
    return {n for n in found if n not in ("by", "on", "without", "ignoring", "group_left", "group_right")}


def catalog_from_handbook(path):
    exact, prefixes = set(), []
    for line in open(path, encoding="utf-8"):
        if not line.startswith("| `"):
            continue
        first = line.split("|")[1]
        for token in re.findall(r"`([a-z][a-z0-9_]*\*?)`", first):
            if token.endswith("*"):
                prefixes.append(token[:-1])
            else:
                exact.add(token)
    return exact, prefixes


def catalog_from_probe(path):
    families = set()
    for line in open(path, encoding="utf-8"):
        if line.startswith("#"):
            continue
        m = re.match(r"([a-zA-Z_:][a-zA-Z0-9_:]*)", line)
        if m:
            families.add(m.group(1))
    return families, []


APPLICATION_YML = "backend/src/main/resources/application.yml"


def histogram_timers(path):
    """Prometheus names of the timers with buckets: the keys under management.metrics.distribution
    slo or percentiles-histogram of application.yml, e.g. opaa.query.duration ->
    opaa_query_duration_seconds. A timer without either publishes no _bucket series."""
    timers, section = set(), None
    for line in open(path, encoding="utf-8"):
        stripped = line.strip()
        if re.match(r"(slo|percentiles-histogram):$", stripped):
            section, indent = stripped, len(line) - len(line.lstrip())
            continue
        if section:
            m = re.match(r'"\[([a-z0-9_.]+)\]":', stripped)
            if m and len(line) - len(line.lstrip()) > indent:
                timers.add(m.group(1).replace(".", "_") + "_seconds")
            elif stripped and len(line) - len(line.lstrip()) <= indent:
                section = None
    return timers


def known(name, exact, prefixes):
    return name in exact or any(name.startswith(p) for p in prefixes) \
        or name == "up" or any(name.startswith(e) for e in EXTERNAL if e != "up")


def promtool_check(queries):
    rules = {"groups": [{"name": "dashboards", "rules": [
        {"record": f"dashboard:check:{i}", "expr": expr} for i, (_, _, expr) in enumerate(queries)]}]}
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(rules, f)
    try:
        res = subprocess.run(["promtool", "check", "rules", "--lint=none", f.name], capture_output=True, text=True)
    finally:
        os.unlink(f.name)
    if res.returncode != 0:
        print(res.stdout + res.stderr)
        for m in re.finditer(r"dashboard:check:(\d+)", res.stdout + res.stderr):
            src, title, expr = queries[int(m.group(1))]
            print(f"::error::{src} / {title}: {expr}")
        return False
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dashboards", required=True)
    ap.add_argument("--rules", action="append", default=[], help="rule file as JSON (yq -o json '.spec')")
    ap.add_argument("--handbook", default=HANDBOOK)
    ap.add_argument("--probe", help="text of /actuator/prometheus; replaces the handbook as catalogue")
    ap.add_argument("--application", default=APPLICATION_YML,
                    help="application.yml whose distribution settings say which timers have buckets")
    ap.add_argument("--promtool", action="store_true", help="parse every dashboard query with promtool")
    args = ap.parse_args()

    dashboards = []
    for src, title, expr in dashboard_queries(args.dashboards):
        for a, b in VARIABLES:
            expr = expr.replace(a, b)
        dashboards.append((src, title, expr))
    queries = dashboards + [q for path in args.rules for q in rule_queries(path)]

    ok = True
    if args.probe:
        exact, prefixes = catalog_from_probe(args.probe)
    else:
        exact, prefixes = catalog_from_handbook(args.handbook)
        for name in sorted(PENDING):
            if known(name, exact, prefixes):
                print(f"::error::{name} is in the handbook table now; remove it from PENDING in {__file__}.")
                ok = False
        exact |= PENDING

    missing = {}
    for src, title, expr in queries:
        for name in names(expr):
            if not known(name, exact, prefixes):
                missing.setdefault(name, []).append(f"{src} / {title}")
    for name, where in sorted(missing.items()):
        print(f"::error::Unknown metric {name}, used in {where[0]}" + (f" and {len(where) - 1} more" if len(where) > 1 else ""))
        ok = False

    histograms = histogram_timers(args.application)
    unbucketed = {}
    for src, title, expr in queries:
        for name in names(expr):
            if name.endswith("_bucket") and name not in PENDING and name[:-len("_bucket")] not in histograms:
                unbucketed.setdefault(name, f"{src} / {title}")
    for name, where in sorted(unbucketed.items()):
        print(f"::error::{name}, used in {where}, has no buckets: its timer has no slo or "
              f"percentiles-histogram entry in {args.application}")
        ok = False

    parsed = [q for q in dashboards if not q[1].startswith("variable ")]
    if args.promtool and not promtool_check(parsed):
        ok = False
    print(f"{len(queries)} queries checked, {len({n for _, _, e in queries for n in names(e)})} metric names")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
