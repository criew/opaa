#!/usr/bin/env bash
# Every ADR under docs/decisions/ follows docs/decisions/README.md: the status line (first non-empty
# line under "## Status") starts with "Akzeptiert", "Vorgeschlagen" or "Ersetzt durch" and names
# "zuletzt geändert JJJJ-MM-TT"; no appended "Nachtrag"/"Nachträge"/"Überholt" blocks (headings or bold
# lead-ins, also inside indented block quotes); no sub-issue cut
# ("Zuschnitt der ... Sub-Issues"); a "## Änderungsverlauf" section exists.
#
# Until the rewrites of the existing ADRs are merged (#2697-#2702), findings are reported as
# warnings and the script exits 0. "--strict" or ADR_STYLE_STRICT=1 turns them into errors (exit 1).
set -euo pipefail

strict="${ADR_STYLE_STRICT:-0}"
for arg in "$@"; do
  case "$arg" in
  --strict) strict=1 ;;
  *)
    echo "usage: $0 [--strict]" >&2
    exit 2
    ;;
  esac
done

cd "$(git rev-parse --show-toplevel)"

shopt -s nullglob
ADRS=(docs/decisions/0*.md)

if [ "$strict" = 1 ]; then level=error; else level=warning; fi

checked=0
rejected=0

report() {
  # $1 file, $2 line (may be empty), $3 message
  rejected=$((rejected + 1))
  if [ -n "$2" ]; then
    echo "::$level file=$1,line=$2::$3"
  else
    echo "::$level file=$1::$3"
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
    if ! printf '%s' "$status" | grep -Eq '^(Akzeptiert|Vorgeschlagen|Ersetzt durch)'; then
      report "$adr" "$status_line_no" "status line must start with \"Akzeptiert\", \"Vorgeschlagen\" or \"Ersetzt durch ADR-NNNN\""
    fi
    if ! printf '%s' "$status" | grep -Eq 'zuletzt geändert [0-9]{4}-[0-9]{2}-[0-9]{2}'; then
      report "$adr" "$status_line_no" "status line must contain \"zuletzt geändert JJJJ-MM-TT\""
    fi
  fi

  # (b) appended blocks instead of an updated core text
  while IFS=: read -r line content; do
    [ -n "$line" ] || continue
    report "$adr" "$line" "appended block \"${content:0:40}\": work the change into the decision text and add a row to \"## Änderungsverlauf\""
  done < <(grep -nE '^[[:space:]]*(>[[:space:]]*)*(#+[[:space:]]+|\*\*)(Nachtr(ag|äge)|Überholt)' "$adr" || true)

  # (c) sub-issue cut
  while IFS=: read -r line content; do
    [ -n "$line" ] || continue
    report "$adr" "$line" "sub-issue cut belongs into the epic, not into the ADR"
  done < <(grep -nE '^#+ .*Zuschnitt der' "$adr" || true)

  # (d) change log section
  if ! grep -Eq '^## Änderungsverlauf[[:space:]]*$' "$adr"; then
    report "$adr" "" "section \"## Änderungsverlauf\" is missing"
  fi
done

if [ "$checked" -eq 0 ]; then
  echo "::error::No ADR found under docs/decisions - the guard would pass without checking anything. Verify this script."
  exit 1
fi

if [ "$rejected" -gt 0 ]; then
  if [ "$strict" = 1 ]; then
    echo "::error::$rejected style finding(s) in $checked ADRs (docs/decisions/README.md)."
    exit 1
  fi
  echo "::warning::$rejected style finding(s) in $checked ADRs (docs/decisions/README.md); not enforced until the ADR rewrites (#2697-#2702) are merged."
  exit 0
fi

echo "All $checked ADRs follow docs/decisions/README.md."
