#!/usr/bin/env bash
# Checks the page rules of docs/handbuch/AGENTS.md on every Markdown file under docs/handbuch/
# except AGENTS.md and CLAUDE.md, found by directory walk, so a new page is checked without a list:
# - no reference out of the handbook to decision records: no "ADR-<digits>", no "docs/decisions",
#   no link into the repository on GitHub (blob/tree); old pages lose their issue links on the move
# - the page head under the title: a "Ziel" row and a "Wer" row, each a single line "**Ziel:** ..."
#   or "**Wer:** ..." followed by "{: .kopf }", within the first eight lines; every page under
#   administration/ and benutzer/ except the connector pages needs "Ziel", pages under
#   administration/ and the connector pages need "Wer", other pages under benutzer/ carry "Wer"
#   only where the task needs a role at the object
# - every page under benutzer/ except a folder's README.md and the connector pages has at most
#   80 lines
# Links inside the handbook are checked by the strict MkDocs build (mkdocs.yml).
# Errors fail the run (exit 1).
set -euo pipefail

root="${1:-docs/handbuch}"
max_lines=80
errors=0

while IFS= read -r -d '' file; do
  rel="${file#"$root"/}"
  case "$rel" in
    AGENTS.md | CLAUDE.md) continue ;;
  esac

  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    echo "::error file=$file,line=${hit%%:*}::Reference out of the handbook: \"${hit#*:}\". The handbook explains behaviour on its own and never points to decision records or repository files."
    errors=$((errors + 1))
  done <<<"$(grep -nE 'ADR-[0-9]{4}|docs/decisions|github\.com/[^/]+/[^/]+/(blob|tree)/' "$file" | cut -c1-120 || true)"

  case "$rel" in
    benutzer/README.md | benutzer/konnektoren/README.md | administration/README.md) continue ;;
    benutzer/* | administration/*) ;;
    *) continue ;;
  esac

  if grep -qE '^> \*\*Wer( darf das)?:\*\*' "$file"; then
    echo "::error file=$file,line=1::The \"Wer\" row is no quote any more: write \"**Wer:** ...\" followed by a line \"{: .kopf }\"."
    errors=$((errors + 1))
  fi
  head8=$(head -n 8 "$file")
  for label in Ziel Wer; do
    row=$(grep -nE "^\*\*${label}:\*\* " <<<"$head8" | head -n 1 | cut -d: -f1 || true)
    if [ -n "$row" ]; then
      if ! sed -n "$((row + 1))p" "$file" | grep -qxF '{: .kopf }'; then
        echo "::error file=$file,line=$row::The \"$label\" row is one line followed by \"{: .kopf }\"."
        errors=$((errors + 1))
      fi
      continue
    fi
    case "$label:$rel" in
      Ziel:benutzer/konnektoren/*) ;;
      Wer:administration/* | Wer:benutzer/konnektoren/* | Ziel:administration/* | Ziel:benutzer/*)
        echo "::error file=$file,line=1::Missing \"$label\" row: \"**$label:** ...\" with \"{: .kopf }\" below, under the title."
        errors=$((errors + 1))
        ;;
    esac
  done

  case "$rel" in
    benutzer/konnektoren/* | administration/*) continue ;;
  esac
  lines=$(wc -l <"$file")
  if [ "$lines" -gt "$max_lines" ]; then
    echo "::error file=$file,line=$((max_lines + 1))::Page has $lines lines, at most $max_lines allowed: one task per page, one screen."
    errors=$((errors + 1))
  fi
done < <(find "$root" -type f -iname '*.md' -print0 | sort -z)

if [ "$errors" -gt 0 ]; then
  echo "::error::$errors violation(s) of the handbook page rules (docs/handbuch/AGENTS.md)."
  exit 1
fi
echo "All handbook pages follow docs/handbuch/AGENTS.md."
