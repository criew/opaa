#!/usr/bin/env bash
# Checks the page rules of docs/handbuch/AGENTS.md on every Markdown file under docs/handbuch/
# except AGENTS.md and CLAUDE.md, found by directory walk, so a new page is checked without a list:
# - no reference out of the handbook to decision records: no "ADR-<digits>", no "docs/decisions",
#   no link into the repository on GitHub (blob/tree); old pages lose their issue links on the move
# - a "Wer" box is a single line "> **Wer:** ..." within the first five lines; pages under
#   administration/ and connector pages under benutzer/konnektoren/ need one, other pages under
#   benutzer/ carry one only where the task needs a role at the object
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

  box=$(head -n 5 "$file" | grep -nE '^> \*\*Wer( darf das)?:\*\*' | head -n 1 | cut -d: -f1 || true)
  if [ -n "$box" ]; then
    if ! sed -n "${box}p" "$file" | grep -qE '^> \*\*Wer:\*\*'; then
      echo "::error file=$file,line=$box::The box is written \"> **Wer:** ...\"."
      errors=$((errors + 1))
    fi
    if sed -n "$((box + 1))p" "$file" | grep -q '^>'; then
      echo "::error file=$file,line=$((box + 1))::The \"Wer\" box is one line; details belong in the Rechte-Matrix."
      errors=$((errors + 1))
    fi
  else
    case "$rel" in
      administration/* | benutzer/konnektoren/*)
        echo "::error file=$file,line=1::Missing \"Wer\" line: a single line \"> **Wer:** ...\" within the first five lines names the role or capability."
        errors=$((errors + 1))
        ;;
    esac
  fi

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
