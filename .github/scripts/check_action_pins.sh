#!/usr/bin/env bash
# Workflows that can write to published artifacts or to the repository must reference every
# action by full commit SHA, followed by the exact release as a comment
# ("uses: owner/repo@<40 hex> # v1.2.3"). A tag can be moved to other code; a SHA cannot.
# Local actions (./...) and digest-pinned docker:// references are exempt.
# PINNED_WORKFLOWS must list the same files as the pinDigests rule in renovate.json5 -
# test_check_action_pins.py enforces that.
set -euo pipefail

PINNED_WORKFLOWS=(
  .github/workflows/cla.yml
  .github/workflows/cve-scan.yml
  .github/workflows/daily-report.yml
  .github/workflows/dependency-graph.yml
  .github/workflows/landing-page.yml
  .github/workflows/publish-images.yml
)

cd "$(git rev-parse --show-toplevel)"

checked=0
rejected=0

for workflow in "${PINNED_WORKFLOWS[@]}"; do
  if [ ! -f "$workflow" ]; then
    rejected=$((rejected + 1))
    echo "::error file=$workflow::Listed workflow does not exist - update PINNED_WORKFLOWS here and the pinDigests rule in renovate.json5."
    continue
  fi

  # "|| true": a workflow without any uses: line is valid, not a reason to abort.
  while IFS=: read -r line content; do
    checked=$((checked + 1))
    ref="$(printf '%s' "$content" | sed -E "s/^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*['\"]?([^'\" ]*)['\"]?.*/\2/")"
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
  done < <(grep -nE '^[[:space:]]*(-[[:space:]]+)?uses:' "$workflow" || true)
done

if [ "$checked" -eq 0 ] && [ "$rejected" -eq 0 ]; then
  echo "::error::No uses: line found in any listed workflow - the guard would pass without checking anything. Verify PINNED_WORKFLOWS and this script."
  exit 1
fi

if [ "$rejected" -gt 0 ]; then
  echo "::error::$rejected of $checked action references in write-privileged workflows are not pinned to a commit SHA."
  exit 1
fi

echo "All $checked action references in ${#PINNED_WORKFLOWS[@]} write-privileged workflows are pinned to a commit SHA."
