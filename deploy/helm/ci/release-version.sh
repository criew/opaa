#!/usr/bin/env bash
# Single source of the release tag format (docs/releases.md): prints X.Y.Z[-<pre>] for a tag
# vX.Y.Z[-<pre>] and fails for anything else. Used by the release guard in publish-images.yml and by
# package-chart.sh. SemVer details such as leading zeros in the pre-release part are left to the
# consumers (metadata-action, helm package), which refuse them.
set -euo pipefail

if (($# != 1)); then
  echo "Usage: $0 <release tag vX.Y.Z[-<pre>]>" >&2
  exit 2
fi
tag="$1"

if ! [[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "Tag $tag is not a release version vX.Y.Z or vX.Y.Z-<pre> (docs/releases.md)" >&2
  exit 1
fi
echo "${tag#v}"
