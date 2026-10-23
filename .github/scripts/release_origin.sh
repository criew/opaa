#!/usr/bin/env bash
# Release guard of publish-images.yml (docs/releases.md, "Schutzregeln im Workflow"): a line opens
# on main (vX.Y.0 and its pre-releases), every patch vX.Y.Z with Z >= 1 lies on release/X.Y and its
# commit contains vX.Y.0; no commit may contain a final tag at or above the version (above the
# release itself for a final tag). Main moves on to the next line, so a patch from there would ship
# its functions. Prints the branch; reads the tags and remote-tracking refs of "origin".
# Exit 1 if refused, 2 if the tag is malformed or main cannot be read.
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
line="$(cut -d. -f1,2 <<<"$version")"
base="${version%%-*}"
final_tag='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

# A final tag the commit contains that the version must not lie below: any higher one, and for a
# pre-release also its own final version.
while IFS= read -r other; do
  v="${other#v}"
  if [[ "$v" == "$base" && "$version" == "$base" ]]; then
    continue
  fi
  if [[ "$(printf '%s\n%s\n' "$v" "$base" | sort -V | tail -n 1)" == "$v" ]]; then
    echo "::error::Tag $tag zeigt auf einen Commit, der schon $other enthält; ein älteres oder gleiches Release brächte dessen Stand (docs/releases.md)" >&2
    exit 1
  fi
done < <(git tag --merged "$commit" --list 'v*' | grep -E "$final_tag" || true)

# 0: ancestor, 1: not an ancestor, anything else (unknown ref or commit) fails the guard.
on_branch() {
  local rc=0
  git merge-base --is-ancestor "$commit" "refs/remotes/origin/$1" || rc=$?
  if ((rc > 1)); then
    echo "::error::Lage von $commit gegenüber origin/$1 nicht bestimmbar" >&2
    exit 2
  fi
  return "$rc"
}

if ! git rev-parse -q --verify "refs/remotes/origin/main" >/dev/null; then
  echo "::error::origin/main fehlt im Checkout; die Herkunft von $tag ist nicht prüfbar" >&2
  exit 2
fi
if [[ "$base" == *.0 ]]; then
  if on_branch main; then
    echo main
    exit 0
  fi
  echo "::error::Tag $tag eröffnet eine Linie; das geschieht nur auf main, der Commit liegt nicht darauf (docs/releases.md)" >&2
  exit 1
fi
branch="release/$line"
if ! git rev-parse -q --verify "refs/remotes/origin/$branch" >/dev/null || ! on_branch "$branch"; then
  echo "::error::Tag $tag ist ein Patch-Release und muss auf $branch liegen, auch für die jüngste Linie; main trägt schon die nächste Linie (docs/releases.md)" >&2
  exit 1
fi
if ! git tag --merged "$commit" --list "v$line.0" | grep -qxF "v$line.0"; then
  echo "::error::Tag $tag liegt auf $branch, aber der Commit enthält v$line.0 nicht; der Branch entsteht vom Commit dieses Tags (docs/releases.md)" >&2
  exit 1
fi
echo "$branch"
