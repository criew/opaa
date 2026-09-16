#!/usr/bin/env bash
# Every tracked package.json that pins "packageManager" must carry a well-formed
# Corepack integrity hash. Corepack verifies the downloaded package manager binary
# against it - in frontend/Dockerfile ("corepack enable" before the production image
# build) and in every local checkout. A truncated or missing hash disables that check.
# Finding no manifest at all, or a field whose value cannot be read, is a failure too,
# so neither a changed path convention nor an unexpected layout can make the guard
# pass without having checked anything.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

checked=0
rejected=0

while IFS= read -r manifest; do
  grep -q '"packageManager"' "$manifest" || continue
  checked=$((checked + 1))

  # grep -o returns matches left to right, so head -n 1 is the outermost/first
  # occurrence - a nested one must never shadow the manifest's own field.
  # "|| true": no match is a reportable finding below, not a reason to abort under
  # "set -e -o pipefail".
  value="$(grep -o '"packageManager"[[:space:]]*:[[:space:]]*"[^"]*"' "$manifest" |
    head -n 1 |
    sed 's/.*:[[:space:]]*"\(.*\)"$/\1/' || true)"

  if [ -z "$value" ]; then
    rejected=$((rejected + 1))
    echo "::error file=$manifest::Found a \"packageManager\" field but could not read its value. Key and value must sit on one line as \"packageManager\": \"pnpm@<version>+sha512.<hash>\"."
    continue
  fi

  if printf '%s' "$value" | grep -Eq '\+(sha512\.[0-9a-f]{128}|sha256\.[0-9a-f]{64})$'; then
    echo "ok      $manifest: $value"
    continue
  fi

  rejected=$((rejected + 1))
  case "$value" in
  *+*)
    echo "::error file=$manifest::packageManager \"$value\" has a malformed Corepack integrity hash (expected +sha512. followed by 128 hex characters, or +sha256. followed by 64). Run 'corepack use ${value%%+*}' in $(dirname "$manifest"), then commit package.json (and the lockfile if it changed)."
    ;;
  *)
    echo "::error file=$manifest::packageManager \"$value\" has no Corepack integrity hash. Run 'corepack use $value' in $(dirname "$manifest"), then commit package.json (and the lockfile if it changed)."
    ;;
  esac
done < <(git ls-files '*package.json' ':!:**/node_modules/**')

if [ "$checked" -eq 0 ]; then
  echo "::error::No tracked package.json with a \"packageManager\" field found - the guard would pass without checking anything. Verify the path convention and this script."
  exit 1
fi

if [ "$rejected" -gt 0 ]; then
  echo "::error::$rejected of $checked packageManager entries lack a well-formed Corepack integrity hash."
  exit 1
fi

echo "All $checked packageManager entries carry a Corepack integrity hash."
