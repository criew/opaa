#!/usr/bin/env bash
# Every workflow must reference every action by full commit SHA, followed by the exact release as
# a comment ("uses: owner/repo@<40 hex> # v1.2.3"). A tag can be moved to other code; a SHA cannot.
# Local actions (./...) and digest-pinned docker:// references are exempt. The renovate.json5
# rules that maintain the pins apply to all workflows as well; test_check_action_pins.py checks it.
set -euo pipefail

# A uses key in block style ("- uses:", "uses:"), quoted ("'uses':", "\"uses\":") or inside a
# flow mapping ("- { name: x, uses: ... }"). A value on its own continuation line is not
# recognised; the workflows here never write one.
USES_KEY="(^[[:space:]]*(-[[:space:]]+)?|[{,][[:space:]]*)['\"]?uses['\"]?[[:space:]]*:"

cd "$(git rev-parse --show-toplevel)"

shopt -s nullglob
WORKFLOWS=(.github/workflows/*.yml .github/workflows/*.yaml)

checked=0
rejected=0

# The ${..+..} form keeps an empty list from failing under "set -u" in bash before 4.4.
for workflow in ${WORKFLOWS[@]+"${WORKFLOWS[@]}"}; do
  # "|| true": a workflow without any uses: line is valid, not a reason to abort.
  while IFS=: read -r line content; do
    checked=$((checked + 1))
    ref="$(printf '%s' "$content" | sed -E "s/.*uses['\"]?[[:space:]]*:[[:space:]]*['\"]?([^'\" ,}]*).*/\1/")"
    comment="$(printf '%s' "$content" | sed -nE 's/.*[[:space:]]#[[:space:]]*(.*)$/\1/p')"

    case "$ref" in
    ./*)
      echo "ok      $workflow:$line: $ref (local)"
      continue
      ;;
    docker://*@sha256:*)
      if printf '%s' "$ref" | grep -Eq '@sha256:[0-9a-f]{64}$'; then
        echo "ok      $workflow:$line: $ref"
        continue
      fi
      ;;
    *)
      if printf '%s' "$ref" | grep -Eq '^[^@/]+/[^@]+@[0-9a-f]{40}$' &&
        printf '%s' "$comment" | grep -Eq '^v?[0-9]+\.[0-9]+\.[0-9]+'; then
        echo "ok      $workflow:$line: $ref # $comment"
        continue
      fi
      ;;
    esac

    rejected=$((rejected + 1))
    echo "::error file=$workflow,line=$line::\"$ref\" is not pinned to a full commit SHA with an exact version comment. Expected: uses: owner/repo@<40-character SHA> # vX.Y.Z (resolve the tag via 'gh api repos/<owner>/<repo>/git/ref/tags/<tag>', dereferencing annotated tags)."
  done < <(grep -nE "$USES_KEY" "$workflow" || true)
done

if [ "$checked" -eq 0 ] && [ "$rejected" -eq 0 ]; then
  echo "::error::No uses: line found in .github/workflows - the guard would pass without checking anything. Verify this script."
  exit 1
fi

if [ "$rejected" -gt 0 ]; then
  echo "::error::$rejected of $checked action references in ${#WORKFLOWS[@]} workflows are not pinned to a commit SHA."
  exit 1
fi

echo "All $checked action references in ${#WORKFLOWS[@]} workflows are pinned to a commit SHA."
