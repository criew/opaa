#!/usr/bin/env bash
# Unit test of the chart's alerts, run by .github/workflows/helm-chart.yml and locally from the
# repository root. Renders the PrometheusRule with the full value set as release "r" in namespace
# "opaa" - the names the test cases expect -, takes its spec as a plain Prometheus rule file and runs
# the test cases in <chart>/ci/prometheusrule-test.yaml against it. Before that it checks that every
# alert carries severity (critical, warning or info), component and a runbook_url pointing at the
# explicit anchor of its name on the runbook page of the handbook, that a test case names it and that
# the test file is what gen_rule_tests.py generates; --write regenerates it first. Needs helm, yq,
# python3 and promtool.
set -euo pipefail

CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
write=""
[ "${1:-}" = "--write" ] && write=1
TEST_FILE="$CHART_DIR/ci/prometheusrule-test.yaml"
RUNBOOK_PAGE="${RUNBOOK_PAGE:-docs/handbuch/betrieb/alarme.md}"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

echo "$(helm version --short), $(promtool --version 2>&1 | head -n 1)"

helm template r "$CHART_DIR" --namespace opaa --values "$CHART_DIR/ci/full-values.yaml" \
  --show-only templates/prometheusrule.yaml >"$work_dir/prometheusrule.yaml"
# -e fails on a missing spec instead of handing promtool an empty rule file.
yq -e '.spec' "$work_dir/prometheusrule.yaml" >"$work_dir/opaa-rules.yaml"

runbook_base="$(yq -e '.metrics.runbookBaseUrl' "$CHART_DIR/values.yaml")"
failed=0
while IFS=$'\t' read -r alert severity component runbook; do
  case "$severity" in
    critical | warning | info) ;;
    *) echo "::error::$alert: severity '$severity' is none of critical, warning, info"; failed=1 ;;
  esac
  if [ -z "$component" ] || [ "$component" = "null" ]; then
    echo "::error::$alert: no component label"
    failed=1
  fi
  if [ "$runbook" != "$runbook_base#$alert" ]; then
    echo "::error::$alert: runbook_url '$runbook' is not $runbook_base#$alert"
    failed=1
  fi
  # The explicit id keeps the anchor's case on the website and in the update package alike.
  if ! grep -qx "<a id=\"$alert\"></a>" "$RUNBOOK_PAGE" || ! grep -qx "### $alert" "$RUNBOOK_PAGE"; then
    echo "::error::$alert: $RUNBOOK_PAGE has no section '### $alert' with the anchor <a id=\"$alert\">"
    failed=1
  fi
  if ! grep -qE "^ *alertname: $alert\$" "$TEST_FILE"; then
    echo "::error::$alert: no test case in $TEST_FILE"
    failed=1
  fi
done < <(yq -r '.groups[].rules[] | [.alert, .labels.severity, .labels.component, .annotations.runbook_url] | @tsv' \
  "$work_dir/opaa-rules.yaml")

# The test file is generated from the cases in gen_rule_tests.py; a hand edit or a stale file fails.
yq -o json '.' "$work_dir/opaa-rules.yaml" >"$work_dir/opaa-rules.json"
python3 "$(dirname "$0")/gen_rule_tests.py" "$work_dir/opaa-rules.json" "$work_dir/generated-test.yaml" >/dev/null
[ -n "$write" ] && cp "$work_dir/generated-test.yaml" "$TEST_FILE"
if ! diff -u "$TEST_FILE" "$work_dir/generated-test.yaml"; then
  echo "::error::$TEST_FILE differs from deploy/helm/ci/gen_rule_tests.py; run" \
    "deploy/helm/ci/rule-test.sh --write" >&2
  failed=1
fi
[ "$failed" -eq 0 ] || exit 1

# The test file names its rule file relative to itself, so both lie side by side.
cp "$TEST_FILE" "$work_dir/prometheusrule-test.yaml"
promtool test rules "$work_dir/prometheusrule-test.yaml"
