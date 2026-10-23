#!/usr/bin/env bash
# Release guard of publish-images.yml (docs/releases.md, "Schutzregeln im Workflow"): admits a tag
# vX.Y.Z[-<pre>] only if <commit> lies on main or on release/X.Y, the maintenance branch of exactly
# the tag's line, and prints that branch (main first). Reads the remote-tracking refs of "origin",
# so the checkout must have fetched every branch. Exit 1 if the commit lies on neither, exit 2 if
# the tag is malformed or main cannot be read.
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
  echo "$branch"
  exit 0
fi
echo "::error::Tag $tag zeigt auf einen Commit, der weder auf main noch auf $branch liegt (docs/releases.md)" >&2
exit 1
