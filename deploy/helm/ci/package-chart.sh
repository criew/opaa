#!/usr/bin/env bash
# Packages the OPAA chart for a release tag, run by .github/workflows/publish-images.yml and, as a
# dry run, by static-check.sh. Tag vX.Y.Z[-<pre>] becomes chart version and appVersion X.Y.Z[-<pre>]
# (ADR-0042, Entscheidung 7). Given the index digests of the three images, the packaged values set
# backend.image.digest, frontend.image.digest and ocr.service.image.digest and must render exactly
# IMAGE_PREFIX-backend@<digest>, -frontend@<digest> and, with the text recognition service switched
# on, -ocr@<digest>; without digests (dry run) the images tagged with the version. Refuses the
# package otherwise, or unless chart version and appVersion carry the version and the annotation
# opaa/image-prefix is IMAGE_PREFIX. Prints its path.
set -euo pipefail

CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/criew/opaa}"

if (($# != 2 && $# != 5)); then
  echo "Usage: $0 <release tag vX.Y.Z[-<pre>]> <output directory> [<backend digest> <frontend digest> <ocr digest>]" >&2
  exit 2
fi
tag="$1"
out_dir="$2"
backend_digest="${3:-}"
frontend_digest="${4:-}"
ocr_digest="${5:-}"

version="$("$(dirname "$0")/release-version.sh" "$tag")"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

source_dir="$CHART_DIR"
if (($# == 5)); then
  for digest in "$backend_digest" "$frontend_digest" "$ocr_digest"; do
    if ! [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
      echo "'$digest' is not an image digest sha256:<64 hex digits>" >&2
      exit 1
    fi
  done
  # Fills the empty digest under backend.image, frontend.image and ocr.service.image, exactly one
  # each, found by the path of keys above it; every other image of the chart keeps its value.
  source_dir="$work_dir/opaa"
  cp -R "$CHART_DIR" "$source_dir"
  if ! awk -v backend="$backend_digest" -v frontend="$frontend_digest" -v ocr="$ocr_digest" '
    BEGIN {
      digest["backend.image"] = backend
      digest["frontend.image"] = frontend
      digest["ocr.service.image"] = ocr
    }
    /^ *[A-Za-z][A-Za-z0-9]*:/ {
      match($0, /^ */)
      indent = RLENGTH
      level = indent / 2
      key = substr($0, indent + 1)
      sub(/:.*/, "", key)
      path[level] = key
      parent = path[0]
      for (i = 1; i < level; i++) parent = parent "." path[i]
      if (level > 0 && $0 ~ /^ *digest: ""[ ]*$/ && (parent in digest)) {
        print substr($0, 1, indent) "digest: \"" digest[parent] "\""
        count[parent]++
        next
      }
    }
    { print }
    END {
      exit !(count["backend.image"] == 1 && count["frontend.image"] == 1 && count["ocr.service.image"] == 1)
    }
  ' "$CHART_DIR/values.yaml" >"$source_dir/values.yaml"; then
    echo "$CHART_DIR/values.yaml has no single empty digest under backend.image, frontend.image and ocr.service.image" >&2
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

# Renders the minimal value set plus the given arguments and prints the images it names.
rendered_images() {
  helm template opaa "$package" --values "$CHART_DIR/ci/minimal-values.yaml" "$@" |
    sed -n 's/^ *image: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' | sort -u
}
# Images of a release are referenced by digest, of a dry run by the version.
reference() {
  if [[ -n "$2" ]]; then printf '%s@%s\n' "$IMAGE_PREFIX-$1" "$2"; else printf '%s:%s\n' "$IMAGE_PREFIX-$1" "$version"; fi
}
expect_images() {
  local images="$1" expected="$2" case="$3"
  if [[ "$images" != "$expected" ]]; then
    echo "Packaged chart renders $case the images" >&2
    echo "$images" >&2
    echo "instead of" >&2
    echo "$expected" >&2
    exit 1
  fi
}
expect_images "$(rendered_images)" \
  "$(reference backend "$backend_digest"; reference frontend "$frontend_digest")" "by default"
# The key is a throwaway value for this render only; nothing is installed.
expect_images "$(rendered_images --set ocr.service.enabled=true \
  --set-string secrets.ocrApiKey=package-check-only-0123456789abcdef)" \
  "$(reference backend "$backend_digest"; reference frontend "$frontend_digest"; reference ocr "$ocr_digest")" \
  "with the text recognition service"

echo "$package"
