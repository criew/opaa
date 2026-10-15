#!/usr/bin/env bash
# Data impact guard for pull requests (docs/releases.md, "Datenwirkung kennzeichnen").
# Usage: check_data_impact.sh <base> <head>; env HAS_LABEL=true|false (label data-impact) and
# PR_BODY (pull request description). Fails if the description ticks a data impact without the
# label, or if a changed file matches .github/data-impact-paths.txt while the pull request has
# neither the label nor a ticked "Keine Wirkung". With the label or "Keine Wirkung" the matched
# files are listed as a notice or warning for the review.
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 <base> <head>" >&2
  exit 2
fi
base="$1"
head="$2"
has_label="${HAS_LABEL:-false}"
body="${PR_BODY:-}"

cd "$(git rev-parse --show-toplevel)"
patterns_file=".github/data-impact-paths.txt"

# Ticked boxes of the section "Wirkung auf Daten und Behördenunterlagen" in the description.
ticked="$(tr -d '\r' <<<"$body" | awk '
  /^##[[:space:]]/ { in_section = ($0 ~ /^##[[:space:]]+Wirkung auf Daten/); next }
  in_section && /^[[:space:]]*[-*][[:space:]]+\[[xX]\]/ {
    sub(/^[[:space:]]*[-*][[:space:]]+\[[xX]\][[:space:]]*/, ""); print
  }')"
no_impact=false
declared_impact=false
while IFS= read -r line; do
  [ -n "$line" ] || continue
  if [[ "$line" == "Keine Wirkung"* ]]; then
    no_impact=true
  else
    declared_impact=true
  fi
done <<<"$ticked"

hits=""
while IFS= read -r pattern; do
  [[ -z "${pattern// /}" || "$pattern" == \#* ]] && continue
  while IFS= read -r file; do
    [ -n "$file" ] && hits+="$file ($pattern)"$'\n'
  done < <(git diff --name-only "$base" "$head" -- ":(glob)$pattern")
done <"$patterns_file"
hits="$(sed '/^$/d' <<<"$hits" | sort -u)"

summary() {
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  {
    echo "### Datenwirkung"
    echo
    echo "$1"
    if [ -n "$hits" ]; then
      echo
      echo "Geänderte Dateien aus \`$patterns_file\`:"
      echo
      sed 's/^/- /' <<<"$hits"
    fi
  } >>"$GITHUB_STEP_SUMMARY"
}

if [ "$has_label" = "true" ]; then
  echo "::notice::Label data-impact is set; the pull request appears under \"Datenrelevante Änderungen\"."
  [ -z "$hits" ] || printf '%s\n' "$hits"
  summary "Label \`data-impact\` gesetzt."
  exit 0
fi

if [ "$declared_impact" = "true" ]; then
  echo "::error::The description ticks a data impact, but the label data-impact is missing. Set the label."
  summary "Datenwirkung angekreuzt, Label \`data-impact\` fehlt."
  exit 1
fi

if [ -z "$hits" ]; then
  echo "No changed file matches $patterns_file."
  summary "Keine Datei aus \`$patterns_file\` geändert."
  exit 0
fi

if [ "$no_impact" = "true" ]; then
  echo "::warning::Files with possible data impact changed; the description ticks \"Keine Wirkung\". Check in the review."
  printf '%s\n' "$hits"
  summary "\"Keine Wirkung\" angekreuzt, obwohl Dateien mit möglicher Datenwirkung geändert sind. Im Review prüfen."
  exit 0
fi

echo "::error::Files with possible data impact changed. Set the label data-impact or tick \"Keine Wirkung\" in the section \"Wirkung auf Daten und Behördenunterlagen\" of the description."
printf '%s\n' "$hits"
summary "Dateien mit möglicher Datenwirkung geändert, weder Label \`data-impact\` noch \"Keine Wirkung\" angekreuzt."
exit 1
