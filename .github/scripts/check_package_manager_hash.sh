#!/usr/bin/env bash
# Every tracked package.json that pins "packageManager" must carry the Corepack
# integrity hash ("+sha512."): pnpm/action-setup reads that field, and without the
# hash the pnpm binary is downloaded unverified. Finding no manifest at all is a
# failure too, so a changed path convention cannot make the guard pass vacuously.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

checked=0
missing=0

while IFS= read -r manifest; do
  value="$(sed -n 's/.*"packageManager"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$manifest" | head -n 1)"
  [ -n "$value" ] || continue
  checked=$((checked + 1))
  case "$value" in
  *+sha512.*)
    echo "ok      $manifest: $value"
    ;;
  *)
    missing=$((missing + 1))
    echo "::error file=$manifest::packageManager \"$value\" has no Corepack integrity hash. Run 'corepack use ${value%%+*}' in $(dirname "$manifest"), then commit package.json (and the lockfile if it changed)."
    ;;
  esac
done < <(git ls-files '*package.json' ':!:**/node_modules/**')

if [ "$checked" -eq 0 ]; then
  echo "::error::No tracked package.json with a \"packageManager\" field found - the guard would pass without checking anything. Verify the path convention and this script."
  exit 1
fi

if [ "$missing" -gt 0 ]; then
  echo "::error::$missing of $checked packageManager entries lack the Corepack integrity hash."
  exit 1
fi

echo "All $checked packageManager entries carry a Corepack integrity hash."
