#!/usr/bin/env bash
# Packages the OPAA chart for a release tag, run by .github/workflows/publish-images.yml and, as a
# dry run, by static-check.sh. Tag vX.Y.Z[-<pre>] becomes chart version and appVersion X.Y.Z[-<pre>]
# (ADR-0042, Entscheidung 7). Given the index digests of both images, the packaged values set
# backend.image.digest and frontend.image.digest and must render exactly
# IMAGE_PREFIX-backend@<digest> and -frontend@<digest>; without digests (dry run) the images tagged
# with the version. Refuses the package otherwise, or unless chart version and appVersion carry the
# version and the annotation opaa/image-prefix is IMAGE_PREFIX. Prints its path.
set -euo pipefail

CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/criew/opaa}"

if (($# != 2 && $# != 4)); then
  echo "Usage: $0 <release tag vX.Y.Z[-<pre>]> <output directory> [<backend digest> <frontend digest>]" >&2
  exit 2
fi
tag="$1"
out_dir="$2"
backend_digest="${3:-}"
frontend_digest="${4:-}"

version="$("$(dirname "$0")/release-version.sh" "$tag")"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

source_dir="$CHART_DIR"
if (($# == 4)); then
  for digest in "$backend_digest" "$frontend_digest"; do
    if ! [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
      echo "'$digest' is not an image digest sha256:<64 hex digits>" >&2
      exit 1
    fi
  done
  # Fills the empty digest under backend.image and frontend.image, exactly one each; every other
  # image of the chart keeps its value.
  source_dir="$work_dir/opaa"
  cp -R "$CHART_DIR" "$source_dir"
  if ! awk -v backend="$backend_digest" -v frontend="$frontend_digest" '
    /^[^ #]/ { top = $0; sub(/:.*/, "", top); image = 0 }
    /^  [^ #]/ { image = ($0 ~ /^  image:[ ]*$/) }
    image && (top == "backend" || top == "frontend") && /^    digest: ""[ ]*$/ {
      print "    digest: \"" (top == "backend" ? backend : frontend) "\""
      count[top]++
      next
    }
    { print }
    END { exit !(count["backend"] == 1 && count["frontend"] == 1) }
  ' "$CHART_DIR/values.yaml" >"$source_dir/values.yaml"; then
    echo "$CHART_DIR/values.yaml has no single empty digest under backend.image and frontend.image" >&2
    exit 1
  fi
fi

mkdir -p "$out_dir"
helm package "$source_dir" --version "$version" --app-version "$version" --destination "$out_dir" >&2
package="$out_dir/opaa-$version.tgz"

chart="$(helm show chart "$package")"
for field in version appVersion; do
  if ! grep -qxF -e "$field: $version" -e "$field: \"$version\"" <<<"$chart"; then
    echo "Packaged chart does not carry $field $version:" >&2
    echo "$chart" >&2
    exit 1
  fi
done
# NOTES.txt tells a mirror from the default images by this annotation.
if ! grep -qxF -e "  opaa/image-prefix: $IMAGE_PREFIX" -e "  opaa/image-prefix: \"$IMAGE_PREFIX\"" <<<"$chart"; then
  echo "Packaged chart does not carry the annotation opaa/image-prefix: $IMAGE_PREFIX:" >&2
  echo "$chart" >&2
  exit 1
fi

images="$(helm template opaa "$package" --values "$CHART_DIR/ci/minimal-values.yaml" |
  sed -n 's/^ *image: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' | sort -u)"
if [[ -n "$backend_digest" ]]; then
  expected="$(printf '%s\n' "$IMAGE_PREFIX-backend@$backend_digest" "$IMAGE_PREFIX-frontend@$frontend_digest")"
else
  expected="$(printf '%s\n' "$IMAGE_PREFIX-backend:$version" "$IMAGE_PREFIX-frontend:$version")"
fi
if [[ "$images" != "$expected" ]]; then
  echo "Packaged chart renders the images" >&2
  echo "$images" >&2
  echo "instead of" >&2
  echo "$expected" >&2
  exit 1
fi

echo "$package"
