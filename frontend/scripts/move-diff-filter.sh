#!/usr/bin/env bash
# Diff filter of a pure move PR (frontend/AGENTS.md): prints every changed line under frontend/
# that is not a module path - import/export lines, vi.mock()/vi.importActual() calls, a path on
# its own line, a key of eslint-suppressions.json. Empty output means the PR only moved files.
#
#   scripts/move-diff-filter.sh [<base-ref>]   (default: origin/main)
set -euo pipefail

base="${1:-origin/main}"
cd "$(dirname "$0")/.."

git diff --find-renames=30% --unified=0 "$base"...HEAD -- . \
  | grep -E '^[+-]' \
  | grep -vE '^(\+\+\+|---) ' \
  | grep -vE "^[+-][[:space:]]*(import[[:space:](]|export[[:space:]].*[[:space:]]from[[:space:]]|\}[[:space:]]*from[[:space:]]|vi\.(mock|doMock)\(|.*vi\.importActual)" \
  | grep -vE "^[+-][[:space:]]*['\"][^'\"]*['\"],?[[:space:]]*$" \
  | grep -vE "^[+-][[:space:]]*\"src/[^\"]+\": \{$" \
  || true
