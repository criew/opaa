#!/usr/bin/env bash
# Prints two lines for the GitHub release of tag vX.Y.Z[-<pre>] on <commit> (docs/releases.md):
#   latest=true|false   true only for a final release no other final tag v* exceeds, so a patch
#                       release of an older line never takes "Latest" from the newest line;
#   previous=vA.B.C     the highest final tag below the own version that <commit> contains, empty
#                       if none; within a line that is its previous release, as for the update package.
# Pre-release tags never count. Reads the tags of the current repository. Exit 2 on a malformed tag.
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 <release tag vX.Y.Z[-<pre>]> <commit>" >&2
  exit 2
fi
tag="$1"
commit="$2"

if ! version="$("$(dirname "$0")/../../deploy/helm/ci/release-version.sh" "$tag")"; then
  exit 2
fi
if ! git rev-parse -q --verify "$commit^{commit}" >/dev/null; then
  echo "::error::Commit $commit fehlt im Checkout" >&2
  exit 2
fi
base="${version%%-*}"
final_tag='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

# True if version $1 is lower than version $2; both X.Y.Z.
lower() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" == "$1" ]]
}

latest=false
if [[ "$version" == "$base" ]]; then
  latest=true
  while IFS= read -r other; do
    if lower "$base" "${other#v}"; then
      latest=false
      break
    fi
  done < <(git tag --list 'v*' | grep -E "$final_tag" || true)
fi

previous=""
while IFS= read -r candidate; do
  if lower "${candidate#v}" "$base" && { [[ -z "$previous" ]] || lower "${previous#v}" "${candidate#v}"; }; then
    previous="$candidate"
  fi
done < <(git tag --merged "$commit" --list 'v*' | grep -E "$final_tag" || true)

echo "latest=$latest"
echo "previous=$previous"
