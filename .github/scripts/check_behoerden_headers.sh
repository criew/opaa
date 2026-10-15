#!/usr/bin/env bash
# Every Markdown file (*.md, *.markdown, any case) under docs/behoerden/ except
# docs/behoerden/README.md follows the conventions of that README, found by directory walk, so a
# new template is checked without any list:
# - a YAML front matter block with stand, gilt-fuer, erzeugung, rechtsstand, pruefvermerk and
#   naechste-durchsicht, each exactly once and well-formed; an overdue naechste-durchsicht is no
#   error, the review role reports it
# - the verbatim disclaimer as the first line after the front matter and an optional H1 title
# - fact sheet placeholders only in the form {{faktenblatt:<name>}} or {{faktenblatt:<name>#<anchor>}},
#   each naming a target of .github/fact-sheet-anchors.txt: the sheet names and anchors the
#   generator renders, checked in and kept current by the backend test FactSheetReferenceTest
# The name list in docs/behoerden/README.md ("`<name>` ist eines der Faktenblätter: ...") equals the
# sheet names of that file.
# Errors fail the run (exit 1). A configuration key (opaa.*) or an enum constant (UPPER_SNAKE_CASE)
# outside fenced code blocks is only a warning: it hints at a fact copied instead of referenced.
# Designations with a digit after an underscore (ISO_27001) are no enum constants and stay silent.
set -euo pipefail
# Under pipefail a reader that stops early (grep -q, awk exit) reads from a here-string, never
# from a pipe: the writer's SIGPIPE would otherwise fail the check at random.

cd "$(git rev-parse --show-toplevel)"

FOLDER=docs/behoerden
DISCLAIMER='> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.'
FIELDS=(stand gilt-fuer erzeugung rechtsstand pruefvermerk naechste-durchsicht)
ANCHORS=.github/fact-sheet-anchors.txt
DATE_RE='^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'

if [ ! -f "$FOLDER/README.md" ]; then
  echo "::error::$FOLDER/README.md not found - the guard would pass without checking anything. Verify this script."
  exit 1
fi

if [ ! -f "$ANCHORS" ]; then
  echo "::error::$ANCHORS not found - run ./gradlew complianceAnchors in backend/ and commit it."
  exit 1
fi
targets="$(tr -d '\r' < "$ANCHORS" | grep -vE '^(#|[[:space:]]*$)' || true)"
FACT_SHEETS="$(printf '%s\n' "$targets" | grep -v '#' | paste -sd '|' - || true)"

checked=0
rejected=0
warned=0

report() {
  # $1 file, $2 line (may be empty), $3 message
  rejected=$((rejected + 1))
  if [ -n "$2" ]; then
    echo "::error file=$1,line=$2::$3"
  else
    echo "::error file=$1::$3"
  fi
}

# The README names exactly the sheets of the target file.
readme_names="$(tr -d '\r' < "$FOLDER/README.md" | tr '\n' ' ' |
  sed -n 's/.*`<name>` ist eines der Faktenblätter:\([^.]*\)\..*/\1/p' |
  grep -oE '`[a-z0-9-]+`' | tr -d '`' | sort | paste -sd '|' - || true)"
expected_names="$(printf '%s\n' "$targets" | grep -v '#' | sort | paste -sd '|' - || true)"
if [ "$readme_names" != "$expected_names" ]; then
  report "$FOLDER/README.md" "" "the fact sheet name list (\"\`<name>\` ist eines der Faktenblätter: ...\") must name exactly ${expected_names//|/, } from $ANCHORS, found \"${readme_names//|/, }\""
fi
# The README's examples name real targets; its syntax descriptions (<name>, <anker>) are skipped.
while IFS=: read -r line match; do
  [ -n "$line" ] || continue
  target="${match#'{{faktenblatt:'}"
  target="${target%'}}'}"
  grep -qxF -- "$target" <<<"$targets" ||
    report "$FOLDER/README.md" "$line" "example \"$match\" names no target of $ANCHORS"
done < <(tr -d '\r' < "$FOLDER/README.md" | grep -noE '\{\{faktenblatt:[a-z0-9-]+(#[a-z0-9-]+)?\}\}' || true)

check_value() {
  # $1 file, $2 field, $3 value
  local file=$1 field=$2 value=$3
  case "$field" in
    stand | rechtsstand | naechste-durchsicht)
      { grep -Eq -- "$DATE_RE" <<<"$value" &&
        [ "$(date -u -d "$value" +%F 2>/dev/null || true)" = "$value" ]; } ||
        report "$file" "" "field \"$field\" must be a calendar date JJJJ-MM-TT, found \"$value\""
      ;;
    gilt-fuer)
      grep -Eq -- '^(main|[0-9]+\.[0-9]+\.[0-9]+)$' <<<"$value" ||
        report "$file" "" "field \"$field\" must be \"main\" or a version X.Y.Z, found \"$value\""
      ;;
    erzeugung)
      [ "$value" = "redaktionell" ] ||
        report "$file" "" "field \"$field\" must be \"redaktionell\" (generated documents are not checked in), found \"$value\""
      ;;
    pruefvermerk)
      [ "$value" = "nicht rechtlich geprüft" ] ||
        report "$file" "" "field \"$field\" must be \"nicht rechtlich geprüft\", found \"$value\""
      ;;
  esac
}

while IFS= read -r -d '' file; do
  checked=$((checked + 1))
  content="$(tr -d '\r' < "$file")"

  # (a) header block: "---" on line 1 up to the next "---"
  end="$(awk 'NR==1{if($0!="---")exit;next} $0=="---"{print NR;exit}' <<<"$content")"
  if [ -z "$end" ]; then
    report "$file" "1" "no header block: the file must start with a front matter block between \"---\" lines ($FOLDER/README.md)"
  else
    header="$(printf '%s\n' "$content" | sed -n "2,$((end - 1))p")"
    for field in "${FIELDS[@]}"; do
      count="$(printf '%s\n' "$header" | grep -c "^$field:" || true)"
      if [ "$count" -eq 0 ]; then
        report "$file" "" "missing field \"$field\" in the header block"
      elif [ "$count" -gt 1 ]; then
        report "$file" "" "field \"$field\" appears more than once in the header block"
      else
        value="$(printf '%s\n' "$header" | sed -n "s/^$field:[[:space:]]*//p" | sed -E 's/[[:space:]]+$//')"
        value="$(printf '%s' "$value" | sed -E "s/^\"(.*)\"$/\\1/; s/^'(.*)'$/\\1/")"
        check_value "$file" "$field" "$value"
      fi
    done

    # (b) disclaimer: first non-empty line after the header, an H1 title may precede it
    first="$(awk -v start="$end" '
      NR<=start || $0 ~ /^[[:space:]]*$/ {next}
      !title && /^# / {title=1; next}
      {sub(/[[:space:]]+$/, ""); print NR "\t" $0; exit}' <<<"$content")"
    if [ "${first#*$'\t'}" != "$DISCLAIMER" ]; then
      report "$file" "${first%%$'\t'*}" "disclaimer missing: the first line after the header (and an optional H1 title) must read \"$DISCLAIMER\""
    fi
  fi

  # (c) fact sheet placeholders
  while IFS=: read -r line match; do
    [ -n "$line" ] || continue
    if ! grep -Eq -- "^\{\{faktenblatt:($FACT_SHEETS)(#[a-z0-9-]+)?\}\}$" <<<"$match"; then
      report "$file" "$line" "malformed fact sheet placeholder \"$match\": use {{faktenblatt:<name>}} or {{faktenblatt:<name>#<anchor>}} with <name> one of ${FACT_SHEETS//|/, }"
    else
      target="${match#'{{faktenblatt:'}"
      target="${target%'}}'}"
      grep -qxF -- "$target" <<<"$targets" ||
        report "$file" "$line" "fact sheet placeholder \"$match\" names no anchor of the rendered sheet; the targets are listed in $ANCHORS"
    fi
  done < <(printf '%s\n' "$content" | grep -noiE '\{\{[[:space:]]*faktenblatt[^}]*(\}\})?' || true)

  # (d) copied facts: warning only, the heuristic cannot tell a fact from a legitimate mention
  while IFS=: read -r line match; do
    [ -n "$line" ] || continue
    [ -z "$end" ] || [ "$line" -gt "$end" ] || continue
    warned=$((warned + 1))
    echo "::warning file=$file,line=$line::\"$match\" looks like a value from the fact sheets: reference it via {{faktenblatt:<name>}} instead of copying it ($FOLDER/README.md)"
  done < <(printf '%s\n' "$content" |
    awk '/^[[:space:]]*(```|~~~)/{fenced=!fenced; print ""; next} {print fenced ? "" : $0}' |
    grep -noE '\bopaa\.[a-z][a-z0-9.-]*|\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b' | grep -vE '_[0-9]' || true)
done < <(find "$FOLDER" -type f \( -iname '*.md' -o -iname '*.markdown' \) ! -path "$FOLDER/README.md" -print0 | sort -z)

if [ "$rejected" -gt 0 ]; then
  echo "::error::$rejected finding(s) in $checked document(s) under $FOLDER ($FOLDER/README.md)."
  exit 1
fi

echo "All $checked document(s) under $FOLDER carry header and disclaimer ($warned warning(s))."
