#!/usr/bin/env bash
# Installation test of the OPAA chart, run by .github/workflows/helm-chart.yml and locally from the
# repository root. Expects the current kubectl context to point at a kind cluster that already holds
# the images named in install-values.yaml. Starts the helper database, then lets chart-testing
# install the chart into a namespace enforcing the Pod Security Standard "restricted", wait until
# backend and frontend are ready, run helm test and uninstall again. Needs kubectl, helm and ct.
set -euo pipefail

CI_DIR="deploy/helm/ci"
CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
NAMESPACE="${INSTALL_NAMESPACE:-opaa-ct}"
# The first start migrates the schema; the chart's startup probe allows up to ten minutes for it.
INSTALL_TIMEOUT="${INSTALL_TIMEOUT:-900s}"

kubectl apply -f "$CI_DIR/postgres.yaml"
kubectl --namespace opaa-ci-db rollout status deployment/postgres --timeout=300s

kubectl create namespace "$NAMESPACE" --dry-run=client --output yaml | kubectl apply -f -
kubectl label --overwrite namespace "$NAMESPACE" \
  pod-security.kubernetes.io/enforce=restricted pod-security.kubernetes.io/enforce-version=latest

# ct install runs once per ci/*-values.yaml. The chart's own value sets point at hosts that do not
# exist, so the test installs a copy of the chart whose ci/ holds only this cluster's value set.
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
cp -R "$CHART_DIR" "$work_dir/opaa"
rm -rf "$work_dir/opaa/ci"
mkdir "$work_dir/opaa/ci"
cp "$CI_DIR/install-values.yaml" "$work_dir/opaa/ci/kind-values.yaml"

ct install --config "$CI_DIR/ct.yaml" --charts "$work_dir/opaa" \
  --namespace "$NAMESPACE" --release-label app.kubernetes.io/instance \
  --helm-extra-args "--timeout $INSTALL_TIMEOUT"
