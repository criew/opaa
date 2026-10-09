#!/usr/bin/env bash
# Compares two chart packages by content, for the chart job in publish-images.yml: succeeds when
# both unpack to identical files and fails with the differences otherwise. helm package takes the
# file timestamps of the checkout into the archive, so packages of the same commit from two
# checkouts differ in bytes; they match here, a chart from any other state does not.
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 <local package .tgz> <published package .tgz>" >&2
  exit 2
fi

for package in "$1" "$2"; do
  [[ -f "$package" ]] || { echo "$package is not a file" >&2; exit 1; }
done

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
mkdir "$work_dir/local" "$work_dir/published"
tar -xzf "$1" -C "$work_dir/local"
tar -xzf "$2" -C "$work_dir/published"

if ! diff -r "$work_dir/local" "$work_dir/published" >&2; then
  echo "Chart $2 differs in content from $1" >&2
  exit 1
fi
