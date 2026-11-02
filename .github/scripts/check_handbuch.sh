#!/usr/bin/env bash
# Checks the page rules of docs/handbuch/AGENTS.md on every Markdown file under docs/handbuch/
# except AGENTS.md and CLAUDE.md, found by directory walk, so a new page is checked without a list:
# - no reference out of the handbook to decision records: no "ADR-<digits>", no "docs/decisions",
#   no link into the repository on GitHub (blob/tree); old pages lose their issue links on the move
# - every page under arbeiten/ and pflegen/ except a folder's README.md carries the "Wer darf das"
#   box (a blockquote line starting "> **Wer darf das:**") within its first ten lines and has at
#   most 80 lines; connector pages under pflegen/konnektoren/ and pages under verwalten/ need the
#   box but have no length limit
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
    arbeiten/README.md | pflegen/README.md | pflegen/konnektoren/README.md | verwalten/README.md) continue ;;
    arbeiten/* | pflegen/* | verwalten/*) ;;
    *) continue ;;
  esac

  if ! head -n 10 "$file" | grep -qE '^> \*\*Wer darf das:\*\*'; then
    echo "::error file=$file,line=1::Missing \"Wer darf das\" box: a line \"> **Wer darf das:** ...\" within the first ten lines names the role or capability and what ships by default."
    errors=$((errors + 1))
  fi
  lines=$(wc -l <"$file")
  case "$rel" in
    pflegen/konnektoren/* | verwalten/*) lines=0 ;;
  esac
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
