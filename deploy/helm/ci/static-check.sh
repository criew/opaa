#!/usr/bin/env bash
# Static check of the OPAA chart, run by .github/workflows/helm-chart.yml and locally from the
# repository root. Lints and renders the chart with every value set in <chart>/ci/, validates the
# rendered manifests against each supported Kubernetes version and asserts that the chart's guards
# still refuse the misconfigurations they exist for. Needs helm and kubeconform on the PATH.
set -euo pipefail

CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
# The three youngest Kubernetes minor versions maintained upstream (ADR-0042, Entscheidung 8);
# moved up by hand with each new Kubernetes minor release.
KUBERNETES_VERSIONS="${KUBERNETES_VERSIONS:-1.35.0 1.36.0 1.37.0}"
# Schemas of custom resources such as the Gateway API HTTPRoute, pinned to one catalog commit so a
# catalog change cannot turn the check red without a change in this repository.
CRD_SCHEMA_LOCATION='https://raw.githubusercontent.com/datreeio/CRDs-catalog/63669a570e231d4f1f8396d229a1de512bcf0a34/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
mkdir -p "$work_dir/schemas"

shopt -s nullglob
value_sets=("$CHART_DIR"/ci/*-values.yaml)
if ((${#value_sets[@]} == 0)); then
  echo "No value sets found in $CHART_DIR/ci/" >&2
  exit 1
fi

echo "$(helm version --short), kubeconform $(kubeconform -v)"

for values in "${value_sets[@]}"; do
  name="$(basename "$values" -values.yaml)"
  echo "--- value set $name"
  helm lint --strict "$CHART_DIR" --values "$values"
  helm template opaa "$CHART_DIR" --namespace opaa --values "$values" >"$work_dir/$name.yaml"
  for version in $KUBERNETES_VERSIONS; do
    kubeconform -strict -summary -kubernetes-version "$version" -cache "$work_dir/schemas" \
      -schema-location default -schema-location "$CRD_SCHEMA_LOCATION" "$work_dir/$name.yaml"
  done
done

# Renders the minimal value set with extra arguments and fails unless Helm refuses it with a message
# containing the expected text.
expect_refusal() {
  local expected="$1"
  shift
  local output
  if output="$(helm template opaa "$CHART_DIR" --values "$CHART_DIR/ci/minimal-values.yaml" "$@" 2>&1)"; then
    echo "Expected the chart to refuse '$*', but it rendered." >&2
    return 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "The chart refused '$*', but without naming '$expected':" >&2
    echo "$output" >&2
    return 1
  fi
  echo "refused as expected: $*"
}

echo "--- guards"
expect_refusal "ADR-0021" --set backend.replicas=2
expect_refusal "ADR-0005" --set 'backend.extraEnv[0].name=SPRING_PROFILES_ACTIVE' --set 'backend.extraEnv[0].value=dev'
expect_refusal "jwtSecret" --set secrets.jwtSecret=changeme-0123456789abcdefghijklmnopqrstuv
expect_refusal "backendReadTimeout" --set frontend.backendReadTimeout=600

# The release packaging of publish-images.yml without the push: a release and a pre-release tag are
# packaged, malformed tags and versions Helm does not accept as SemVer are refused. The tag format
# itself comes from release-version.sh, which the image release guard calls as well.
echo "--- release packaging"
for tag in v1.2.3 v1.2.3-rc.1; do
  version="$(deploy/helm/ci/release-version.sh "$tag")"
  if [[ "$version" != "${tag#v}" ]]; then
    echo "release-version.sh turned $tag into '$version' instead of '${tag#v}'" >&2
    exit 1
  fi
done
for tag in v1.2.3 v1.2.3-rc.1; do
  CHART_DIR="$CHART_DIR" deploy/helm/ci/package-chart.sh "$tag" "$work_dir/packages" >/dev/null
  echo "packaged as expected: $tag"
done
expect_tag_refusal() {
  local tag="$1" expected="$2" output
  if output="$(CHART_DIR="$CHART_DIR" deploy/helm/ci/package-chart.sh "$tag" "$work_dir/packages" 2>&1)"; then
    echo "Expected release packaging to refuse tag $tag, but it packaged." >&2
    return 1
  fi
  if ! grep -qi -- "$expected" <<<"$output"; then
    echo "Release packaging refused tag $tag, but without naming '$expected':" >&2
    echo "$output" >&2
    return 1
  fi
  echo "refused as expected: $tag"
}
for tag in 1.2.3 v1.2 v1.2.3+build.1 v01.2.3; do
  expect_tag_refusal "$tag" "is not a release version"
done
expect_tag_refusal v1.0.0-rc.01 "segment starts with 0"

echo "Static check passed: ${#value_sets[@]} value sets, Kubernetes $KUBERNETES_VERSIONS, release packaging"
