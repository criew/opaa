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
# Every relative Markdown link in these files and the README names an existing file and, with an
# anchor, a heading (GitHub slug) or <a id> of the target, so a renamed heading fails here.
# Under 03-vergabe/ every document has a section "## Zum Nachlesen" whose table has a
# column "Fundstelle" with a link or "zu prüfen" in each row; every citation "§"/"§§"/"Art." in
# the text is recognised and also appears in a row of that table, and rechtsstand is not after
# stand. The requirement catalog and the award criteria never name OPAA (any case, also in links),
# and catalog and compliance matrix list the same requirement numbers, each once.
# Needs GNU sed (\L), the C.UTF-8 locale and GNU realpath (-m, --relative-to), as on CI runners.
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
LEGAL_DIRS=("$FOLDER/03-vergabe")
SOURCES_HEADING='## Zum Nachlesen'
NEUTRAL_FILES=("$FOLDER/03-vergabe/anforderungskatalog.md" "$FOLDER/03-vergabe/zuschlagskriterien.md")
CATALOG="$FOLDER/03-vergabe/anforderungskatalog.md"
MATRIX="$FOLDER/03-vergabe/erfuellungsmatrix.md"
REQUIREMENT_RE='^\| [A-Z]-[0-9]{2} \|'

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

# Stores in ANCHOR_CACHE[file] the anchors GitHub renders for a Markdown file, one per line:
# - a slug per ATX heading (up to three spaces indent) and setext heading outside fenced code and
#   outside the front matter: emphasis and link targets dropped, lowercase, only letters, digits,
#   space, "_" and "-" kept, spaces to "-"; a repeated slug gets "-1", "-2", skipping any suffix
#   already taken, as github-slugger does
# - every <a id="..."> or <a name="...">
# Runs in the current shell.
declare -A ANCHOR_CACHE=()
load_anchors() {
  if [ -z "${ANCHOR_CACHE[$1]+set}" ]; then
    ANCHOR_CACHE[$1]="$(
      tr -d '\r' < "$1" |
        awk '
          NR == 1 && $0 == "---" { fm = 1; next }
          fm { if ($0 == "---") fm = 0; next }
          /^[[:space:]]*(```|~~~)/ { f = !f; prev = ""; next }
          f { next }
          /^ ? ? ?#+([ \t]|$)/ && !/^ ? ? ?#######/ {
            h = $0; sub(/^ *#+[ \t]*/, "", h); sub(/[ \t]+#+[ \t]*$/, "", h); sub(/[ \t]+$/, "", h)
            print h; prev = ""; next
          }
          /^ ? ? ?(=+|-+)[ \t]*$/ && prev != "" { print prev; prev = ""; next }
          {
            prev = $0
            if (prev ~ /^[ \t]*$/ || prev ~ /^ ? ? ?([-*+][ \t]|[0-9]+[.)][ \t]|>|\|)/ || prev ~ /^(    |\t)/) prev = ""
            sub(/^[ \t]+/, "", prev); sub(/[ \t]+$/, "", prev)
          }' |
        LC_ALL=C.UTF-8 sed -E 's/!?\[([^]]*)\]\([^)]*\)/\1/g; s/<[^>]*>//g;
          s/(^|[^[:alnum:]_])_+([^_]+)_+([^[:alnum:]_]|$)/\1\2\3/g;
          s/.*/\L&/; s/[^[:alnum:] _-]//g; s/ /-/g' |
        awk '{ r = $0; o = r; while (r in occ) { occ[o]++; r = o "-" occ[o] } occ[r] = 0; print r }'
      tr -d '\r' < "$1" | grep -oE '<a (id|name)="[^"]+"' | sed -E 's/.*="([^"]+)"/\1/' || true
    )"
  fi
}

check_links() {
  # $1 file: relative links outside fenced and inline code resolve to a file and an anchor
  local file=$1 line target path anchor resolved
  while IFS=: read -r line target; do
    [ -n "$line" ] || continue
    target="${target#']('}"
    case "$target" in http://* | https://* | mailto:*) continue ;; esac
    path="${target%%#*}"
    anchor=""
    [ "$path" = "$target" ] || anchor="${target#*#}"
    # GitHub matches a percent-encoded anchor (%C3%BC) against the decoded heading (ü)
    anchor="$(printf '%b' "${anchor//%/\\x}")"
    if [ -z "$path" ]; then
      resolved="$file"
    else
      resolved="$(realpath -m --relative-to=. "$(dirname "$file")/$path")"
    fi
    if [ ! -e "$resolved" ]; then
      report "$file" "$line" "link \"$target\" points to a missing file ($resolved)"
      continue
    fi
    [ -n "$anchor" ] && [[ "$resolved" == *.md ]] || continue
    load_anchors "$resolved"
    if ! grep -qxF -- "$anchor" <<<"${ANCHOR_CACHE[$resolved]}"; then
      report "$file" "$line" "link \"$target\" names no heading or anchor in $resolved"
    fi
  done < <(tr -d '\r' < "$file" |
    awk '/^[[:space:]]*(```|~~~)/{f=!f; print ""; next} {print f ? "" : $0}' |
    sed -E 's/`[^`]*`//g' | grep -noE '\]\([^) ]+' || true)
}

# Reads a template without its header (lines after start) and prints "<line>\t<message>" for each
# finding: a sources table without column "Fundstelle", a row without link or "zu prüfen" there,
# a "§", "§§" or "Art." the citation grammar does not recognise, and a citation of the text that
# no table row of the sources section names. Citations are compared as "§ <n> <LAW>" or
# "Art. <n> <LAW>": Abs./Absatz, Satz/S., Nr., UAbs., lit. and ff./f. are dropped. Text is read as
# one token stream, so a citation may span lines; fenced code is skipped.
LEGAL_AWK="$(
  cat <<'AWK'
function trim(x) { sub(/^[ \t]+/, "", x); sub(/[ \t]+$/, "", x); return x }
function err(l, m) { printf "%d\t%s\n", l, m }
function tokens(x, l, src,   a, n, i) {
  gsub(/\]\([^)]*\)/, " ", x)
  gsub(/§/, " § ", x)
  gsub(/„|“|”|"|\[|\]|\(|\)|\|/, " ", x)
  n = split(x, a, /[ \t]+/)
  for (i = 1; i <= n; i++) if (a[i] != "") { t++; tok[t] = a[i]; tl[t] = l; ts[t] = src }
}
NR <= start { next }
/^[[:space:]]*(```|~~~)/ { fence = !fence; next }
fence { next }
$0 == heading { sec = 1; next }
sec && /^## / { sec = 0 }
sec && /^\|/ {
  rows++
  if (rows == 1) {
    nc = split($0, c, "|")
    for (i = 1; i <= nc; i++) if (trim(c[i]) == "Fundstelle") col = i
    if (!col) err(NR, "the table under \"" heading "\" needs a column \"Fundstelle\"")
    next
  }
  if ($0 ~ /^\|[-:| \t]+$/) next
  split($0, c, "|")
  if (col && c[col] !~ /\]\(|zu prüfen/) err(NR, "source row without link and without \"zu prüfen\" in column \"Fundstelle\"")
  tokens($0, NR, 1)
  next
}
{ tokens($0, NR, 0) }
END {
  for (i = 1; i <= t; i++) {
    if (tok[i] == "§") kind = "§"; else if (tok[i] == "Art.") kind = "Art."; else continue
    j = i + 1
    if (kind == "§" && tok[j] == "§") j++
    num = tok[j]; sub(/[,;:]$/, "", num)
    if (num !~ /^[0-9]+[a-z]?$/) { err(tl[i], "citation \"" kind " " tok[j] "\" not recognised: write " kind " <n> [Abs. <n>] <LAW>"); continue }
    j++
    while (1) {
      q = tok[j]; v = tok[j + 1]; sub(/[,;]$/, "", v)
      if (q ~ /^(Abs\.|Absatz|Satz|S\.|Nr\.|UAbs\.|lit\.)$/ && v ~ /^([0-9]+[a-z]?|[a-z])$/) { j += 2; continue }
      if (q ~ /^ff?\.$/) { j++; continue }
      break
    }
    law = tok[j]; sub(/[.,;:!?]+$/, "", law)
    if (law !~ /^[A-Z][A-Za-z]*[A-Z][A-Za-z]*$/) { err(tl[i], "citation \"" kind " " num "\" not recognised: no law abbreviation after it (" tok[j] ")"); continue }
    key = kind " " num " " law
    if (ts[i]) listed[key] = 1
    else if (!(key in first)) first[key] = tl[i]
    i = j
  }
  for (k in first) if (!(k in listed)) err(first[k], "citation \"" k "\" is missing from the table under \"" heading "\"")
}
AWK
)"

check_legal() {
  # $1 file, $2 content, $3 line of the header end: sources and rechtsstand of legal statements
  local file=$1 content=$2 end=$3 line message stand rechtsstand
  if ! grep -qxF -- "$SOURCES_HEADING" <<<"$content"; then
    report "$file" "" "missing section \"$SOURCES_HEADING\": every legal statement needs its source and the rechtsstand ($FOLDER/README.md)"
    return
  fi
  while IFS=$'\t' read -r line message; do
    [ -n "$line" ] || continue
    report "$file" "$line" "$message"
  done < <(awk -v start="$end" -v heading="$SOURCES_HEADING" "$LEGAL_AWK" <<<"$content" | sort -n)
  stand="$(sed -n "2,$((end - 1))p" <<<"$content" | sed -n 's/^stand:[[:space:]]*//p' | tr -d "\"' ")"
  rechtsstand="$(sed -n "2,$((end - 1))p" <<<"$content" | sed -n 's/^rechtsstand:[[:space:]]*//p' | tr -d "\"' ")"
  if [[ "$rechtsstand" > "$stand" ]]; then
    report "$file" "" "rechtsstand $rechtsstand lies after stand $stand"
  fi
}

# Requirement numbers of the first table column, one per line and occurrence, unsorted.
requirement_ids() {
  [ -f "$1" ] || return 0
  tr -d '\r' < "$1" | { grep -oE "$REQUIREMENT_RE" || true; } | grep -oE '[A-Z]-[0-9]{2}'
}

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

  # (e) links; for legal templates sources and rechtsstand; vendor-neutral templates
  check_links "$file"
  for dir in "${LEGAL_DIRS[@]}"; do
    if [[ "$file" == "$dir"/* ]] && [ -n "$end" ]; then
      check_legal "$file" "$content" "$end"
    fi
  done
  for neutral in "${NEUTRAL_FILES[@]}"; do
    [ "$file" = "$neutral" ] || continue
    while IFS=: read -r line _; do
      [ -n "$line" ] || continue
      report "$file" "$line" "a vendor-neutral template must not name OPAA, not even in a link; product evidence belongs in $MATRIX"
    done < <(grep -niF opaa <<<"$content" || true)
  done
done < <(find "$FOLDER" -type f \( -iname '*.md' -o -iname '*.markdown' \) ! -path "$FOLDER/README.md" -print0 | sort -z)

check_links "$FOLDER/README.md"

# (f) the compliance matrix answers exactly the requirements of the catalog
if [ -f "$CATALOG" ] || [ -f "$MATRIX" ]; then
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    report "$MATRIX" "" "requirement $id of $CATALOG has no row in the compliance matrix"
  done < <(comm -23 <(requirement_ids "$CATALOG" | sort -u) <(requirement_ids "$MATRIX" | sort -u))
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    report "$MATRIX" "" "requirement $id is not in $CATALOG"
  done < <(comm -13 <(requirement_ids "$CATALOG" | sort -u) <(requirement_ids "$MATRIX" | sort -u))
  for listing in "$CATALOG" "$MATRIX"; do
    while IFS= read -r id; do
      [ -n "$id" ] || continue
      report "$listing" "" "requirement $id has more than one row"
    done < <(requirement_ids "$listing" | sort | uniq -d)
  done
fi

if [ "$rejected" -gt 0 ]; then
  echo "::error::$rejected finding(s) in $checked document(s) under $FOLDER ($FOLDER/README.md)."
  exit 1
fi

echo "All $checked document(s) under $FOLDER carry header and disclaimer ($warned warning(s))."
