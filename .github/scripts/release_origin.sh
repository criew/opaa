#!/usr/bin/env bash
# Release guard of publish-images.yml (docs/releases.md, "Schutzregeln im Workflow"): admits a tag
# vX.Y.Z[-<pre>] only if <commit> lies on main or on release/X.Y, the branch of exactly the tag's
# line, and prints that branch (main first). On release/X.Y only a patch (Z >= 1) of a line whose
# vX.Y.0 the commit contains; never a commit containing a final tag at or above the version (above
# the release itself for a final tag), which would ship a newer line's functions. Reads the tags
# and remote-tracking refs of "origin". Exit 1 if refused, 2 if the tag is malformed or main unread.
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
if on_branch main; then
  echo main
  exit 0
fi
branch="release/$line"
if git rev-parse -q --verify "refs/remotes/origin/$branch" >/dev/null && on_branch "$branch"; then
  if [[ "$base" == *.0 ]]; then
    echo "::error::Tag $tag eröffnet eine Linie; das geschieht auf main, $branch trägt nur Patch-Releases (docs/releases.md)" >&2
    exit 1
  fi
  if ! git tag --merged "$commit" --list "v$line.0" | grep -qxF "v$line.0"; then
    echo "::error::Tag $tag liegt auf $branch, aber der Commit enthält v$line.0 nicht; der Branch entsteht vom Commit dieses Tags (docs/releases.md)" >&2
    exit 1
  fi
  echo "$branch"
  exit 0
fi
echo "::error::Tag $tag zeigt auf einen Commit, der weder auf main noch auf $branch liegt (docs/releases.md)" >&2
exit 1
