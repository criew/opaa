#!/usr/bin/env bash
# Every ADR under docs/decisions/ follows docs/decisions/README.md: the status line (first non-empty
# line under "## Status") starts with "Akzeptiert" or "Ersetzt durch" and names
# "zuletzt geändert JJJJ-MM-TT"; no appended "Nachtrag"/"Nachträge"/"Überholt" blocks (headings or bold
# lead-ins, also inside indented block quotes); no sub-issue cut
# ("Zuschnitt der ... Sub-Issues"); no "seit #NNNN" markers and no "## Änderungsverlauf" section
# (git log is the history).
# Any finding fails the run (exit 1).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

shopt -s nullglob
ADRS=(docs/decisions/0*.md)

checked=0
rejected=0

report() {
  # $1 file, $2 line (may be empty), $3 message
  rejected=$((rejected + 1))
  if [ -n "$2" ]; then
    echo "::error file=$1,line=$2::$3"
  else
    echo "::error file=$1::$3"
  fi
}

for adr in ${ADRS[@]+"${ADRS[@]}"}; do
  checked=$((checked + 1))

  # (a) status line
  status_line_no="$(awk '/^## Status[[:space:]]*$/{f=1;next} f&&NF{print NR;exit}' "$adr" || true)"
  if [ -z "$status_line_no" ]; then
    report "$adr" "" "no status line under \"## Status\""
  else
    status="$(sed -n "${status_line_no}p" "$adr")"
    if ! printf '%s' "$status" | grep -Eq '^(Akzeptiert|Ersetzt durch)'; then
      report "$adr" "$status_line_no" "status line must start with \"Akzeptiert\" or \"Ersetzt durch ADR-NNNN\""
    fi
    if ! printf '%s' "$status" | grep -Eq 'zuletzt geändert [0-9]{4}-[0-9]{2}-[0-9]{2}'; then
      report "$adr" "$status_line_no" "status line must contain \"zuletzt geändert JJJJ-MM-TT\""
    fi
  fi

  # (b) appended blocks instead of an updated core text
  while IFS=: read -r line content; do
    [ -n "$line" ] || continue
    report "$adr" "$line" "appended block \"${content:0:40}\": work the change into the decision text"
  done < <(grep -nE '^[[:space:]]*(>[[:space:]]*)*(#+[[:space:]]+|\*\*)(Nachtr(ag|äge)|Überholt)' "$adr" || true)

  # (c) sub-issue cut
  while IFS=: read -r line content; do
    [ -n "$line" ] || continue
    report "$adr" "$line" "sub-issue cut belongs into the epic, not into the ADR"
  done < <(grep -nE '^#+ .*Zuschnitt der' "$adr" || true)

  # (d) no "seit #NNNN" markers: git log is the history
  while IFS=: read -r line content; do
    [ -n "$line" ] || continue
    report "$adr" "$line" "\"seit #NNNN\": describe the current state only, the history is git log"
  done < <(grep -nE 'seit #[0-9]+' "$adr" || true)

  # (e) no change log section: git log is the history
  while IFS=: read -r line content; do
    [ -n "$line" ] || continue
    report "$adr" "$line" "section \"## Änderungsverlauf\": the history is git log, drop the section"
  done < <(grep -nE '^#+ +Änderungsverlauf' "$adr" || true)
done

if [ "$checked" -eq 0 ]; then
  echo "::error::No ADR found under docs/decisions - the guard would pass without checking anything. Verify this script."
  exit 1
fi

if [ "$rejected" -gt 0 ]; then
  echo "::error::$rejected style finding(s) in $checked ADRs (docs/decisions/README.md)."
  exit 1
fi

echo "All $checked ADRs follow docs/decisions/README.md."
