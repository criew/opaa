#!/usr/bin/env bash
# Comments under backend/src/main and opaa-api/src/main state the contract, not how it came about
# (AGENTS.md, "Code-Kommentare"). Rejected: review protocol ("#393 code review, finding 1",
# "nit 4", "Befund 6"), history anchored at an issue ("since #1931", "pre-#888", "formerly") and
# bare origin markers ("(#1073)", "(PR #537)", a leading "#1073:"). Any other issue reference is
# only counted: a permitted one (workaround until an upstream fix, deprecation, spike) and one still
# woven into a sentence look alike to a pattern. The spike package is exempt.
# Checked: Java comment lines, '#' comment lines of application*.yml and every line of the OpenAPI
# fragments, whose summaries and descriptions are the API documentation.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# An issue reference; "owner/repo#123", "&#123;" and "page.md#123" are none.
REF='(?<![\w.&#/-])#[0-9]{2,4}\b'
# file:line: in front of every checked line
AT='^[^:]+:[0-9]+:'
REVIEW="(?i)${REF}\S*\s+(code\s+|re-)?review\b|\breview\s+of\s+(PR\s+)?#[0-9]|\bre-review\b|\b(should-|optional\s+)?finding\s+[0-9]|\bnit\s+[0-9]|\bBefund\s+([0-9]|\")"
HISTORY="(?i)\bpre-#[0-9]|\bformerly\b|\b(since|until|before|after)\s+(epic\s+|PR\s+|issue\s+)?#[0-9]"
MARKER="\(\s*((PR|issue|see|Epic)\s+)?${REF}(\s*[,/]\s*((PR|issue)\s+)?${REF})*\s*\)|${AT}\s*(//|/?\*|#)\s*(<p>|<b>)?\s*${REF}(\s*[,/]\s*${REF})*\s*:"
EXEMPT="^backend/src/main/java/io/opaa/query/spike/|(?i)\bspike\b"

lines() {
  git ls-files -z -- 'backend/src/main/*.java' 'opaa-api/src/main/*.java' |
    xargs -0 -r grep -nHP '^\s*(//|/?\*)' || true
  git ls-files -z -- 'backend/src/main/resources/application*.yml' |
    xargs -0 -r grep -nHP '^\s*#' || true
  git ls-files -z -- 'opaa-api/src/main/openapi/*.yaml' | xargs -0 -r grep -nH '' || true
}

all="$(lines)"
if [ -z "$all" ]; then
  echo "::error::No comment line found under backend/src/main or opaa-api/src/main - the guard would pass without checking anything. Verify this script."
  exit 1
fi
checked="$(grep -c '' <<<"$all")"
candidates="$(grep -vP "$EXEMPT" <<<"$all" || true)"

rejected=0
reject() {
  # $1 pattern, $2 kind, $3 hint
  local file line content
  while IFS=: read -r file line content; do
    [ -n "$file" ] || continue
    rejected=$((rejected + 1))
    content="$(sed -E 's/^[[:space:]]+//' <<<"$content" | cut -c1-90)"
    echo "::error file=$file,line=$line::$2 in a comment: \"$content\". $3"
  done < <(grep -P "$1" <<<"$candidates" || true)
}

reject "$REVIEW" "Review protocol" "Drop it; the reasoning belongs in the commit message or the PR."
reject "$HISTORY" "History" "State what holds now; the history is in git log."
reject "$MARKER" "Origin marker" "Drop the issue number; the sentence stands without it."

if [ "$rejected" -gt 0 ]; then
  echo "::error::$rejected of $checked checked lines break the comment rule (AGENTS.md, \"Code-Kommentare\")."
  exit 1
fi

others="$(sed -E 's/^[^:]+:[0-9]+://' <<<"$candidates" | grep -cP "$REF" || true)"
echo "::notice::$others checked lines still name an issue; that is allowed only for a workaround until an upstream fix, a deprecation or a spike."
echo "All $checked checked lines under backend/src/main and opaa-api/src/main follow the comment rule."
