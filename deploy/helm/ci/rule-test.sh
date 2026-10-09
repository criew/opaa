#!/usr/bin/env bash
# Unit test of the chart's alerts, run by .github/workflows/helm-chart.yml and locally from the
# repository root. Renders the PrometheusRule with the full value set as release "r" in namespace
# "opaa" - the names the test cases expect -, takes its spec as a plain Prometheus rule file and runs
# the test cases in <chart>/ci/prometheusrule-test.yaml against it. Needs helm, yq and promtool.
set -euo pipefail

CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
TEST_FILE="$CHART_DIR/ci/prometheusrule-test.yaml"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

echo "$(helm version --short), $(promtool --version 2>&1 | head -n 1)"

helm template r "$CHART_DIR" --namespace opaa --values "$CHART_DIR/ci/full-values.yaml" \
  --show-only templates/prometheusrule.yaml >"$work_dir/prometheusrule.yaml"
# -e fails on a missing spec instead of handing promtool an empty rule file.
yq -e '.spec' "$work_dir/prometheusrule.yaml" >"$work_dir/opaa-rules.yaml"

# The test file names its rule file relative to itself, so both lie side by side.
cp "$TEST_FILE" "$work_dir/prometheusrule-test.yaml"
promtool test rules "$work_dir/prometheusrule-test.yaml"
