#!/usr/bin/env bash
# Checks the page rules of docs/handbuch/AGENTS.md on every Markdown file under docs/handbuch/
# except AGENTS.md and CLAUDE.md, found by directory walk, so a new page is checked without a list:
# - no reference out of the handbook to decision records: no "ADR-<digits>", no "docs/decisions",
#   no link into the repository on GitHub (blob/tree); old pages lose their issue links on the move
# - every page under pflegen/ and verwalten/ except a folder's README.md carries the one-line
#   "Wer" box (a line starting "> **Wer:**") within its first five lines, followed by no further
#   blockquote line; pages under arbeiten/ carry none (they apply to every account)
# - every page under arbeiten/ and pflegen/ except a folder's README.md has at most 80 lines;
#   connector pages under pflegen/konnektoren/ and pages under verwalten/ have no length limit
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

  case "$rel" in
    arbeiten/*)
      if grep -qE '^> \*\*Wer( darf das)?:\*\*' "$file"; then
        echo "::error file=$file,line=1::Pages under arbeiten/ carry no \"Wer\" box: they apply to every account; state a real precondition as a sentence."
        errors=$((errors + 1))
      fi
      ;;
    *)
      box=$(head -n 5 "$file" | grep -nE '^> \*\*Wer:\*\*' | head -n 1 | cut -d: -f1 || true)
      if [ -z "$box" ]; then
        echo "::error file=$file,line=1::Missing \"Wer\" line: a single line \"> **Wer:** ...\" within the first five lines names the role or capability."
        errors=$((errors + 1))
      elif sed -n "$((box + 1))p" "$file" | grep -q '^>'; then
        echo "::error file=$file,line=$((box + 1))::The \"Wer\" box is one line; details belong in the Rechte-Matrix."
        errors=$((errors + 1))
      fi
      ;;
  esac
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
