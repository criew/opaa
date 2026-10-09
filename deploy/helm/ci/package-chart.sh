#!/usr/bin/env bash
# Packages the OPAA chart for a release tag, run by .github/workflows/publish-images.yml and, as a
# dry run, by static-check.sh. Tag vX.Y.Z[-<pre>] becomes chart version and appVersion X.Y.Z[-<pre>]
# (ADR-0042, Entscheidung 7). Refuses the package unless both carry that version and the default
# values render exactly the images IMAGE_PREFIX-backend and -frontend of it. Prints the package path.
set -euo pipefail

CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/criew/opaa}"

if (($# != 2)); then
  echo "Usage: $0 <release tag vX.Y.Z[-<pre>]> <output directory>" >&2
  exit 2
fi
tag="$1"
out_dir="$2"

version="$("$(dirname "$0")/release-version.sh" "$tag")"

mkdir -p "$out_dir"
helm package "$CHART_DIR" --version "$version" --app-version "$version" --destination "$out_dir" >&2
package="$out_dir/opaa-$version.tgz"

chart="$(helm show chart "$package")"
for field in version appVersion; do
  if ! grep -qxF -e "$field: $version" -e "$field: \"$version\"" <<<"$chart"; then
    echo "Packaged chart does not carry $field $version:" >&2
    echo "$chart" >&2
    exit 1
  fi
done

images="$(helm template opaa "$package" --values "$CHART_DIR/ci/minimal-values.yaml" |
  sed -n 's/^ *image: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' | sort -u)"
expected="$(printf '%s\n' "$IMAGE_PREFIX-backend:$version" "$IMAGE_PREFIX-frontend:$version")"
if [[ "$images" != "$expected" ]]; then
  echo "Packaged chart renders the images" >&2
  echo "$images" >&2
  echo "instead of" >&2
  echo "$expected" >&2
  exit 1
fi

echo "$package"
