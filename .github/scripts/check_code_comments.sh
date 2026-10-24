#!/usr/bin/env bash
# Comments state the contract, not how it came about (AGENTS.md, "Code-Kommentare").
#
# Checked trees (TREES): '//' and '/* */' comment lines of the Java sources under backend/src/main,
# opaa-api/src/main, backend/src/configMetadata, backend/src/test and backend/src/evalTest, of the
# e2e/ scripts (*.ts, *.mjs, *.js), of the Gradle build scripts (*.gradle.kts in backend/ and
# opaa-api/) and of
# renovate.json5; '#' lines of application*.yml, of e2e/*.yml|yaml|env, every shell script (*.sh),
# docker-compose*.yml, .env*.example, the Dockerfiles, backend/gradle/*.toml, frontend/nginx*.conf
# and demo/*.conf; '#' lines and docstrings of the Python scripts under eval/, demo/ and
# backend/src/test/resources/; '#' lines and '//' lines of inline scripts in the workflows
# (.github/workflows/*.yml); every line of the OpenAPI fragments, whose summaries and descriptions
# are the API documentation; '--' lines of the Flyway migrations (*.sql); '//' and '/* */'
# comments of the TypeScript sources under frontend/src, of frontend/vite.config.ts and of the ESLint configuration
# (frontend/eslint.config.*, frontend/eslint/), each from the comment start on.
#
# Apart from the frontend sources, only a line that starts with its comment mark is read: a comment
# behind code stays unchecked, as telling it from a '#' or '//' inside a string or URL needs a
# parser per language. A '#!' line is no comment. This script is exempt itself: its header lists the rejected forms as examples.
#
# Rejected:
# - review protocol: "#393 code review, finding 1", "#N second review", "#N (review)",
#   "review of PR #N", "Review zu #N", "re-review", "review round", "review finding", "finding 2",
#   "nit 4", "nit-4", "Befund 6", "Wichtig 3", "Review-Runde 1", "HIGH 2"/"MEDIUM 5"/"LOW 7"
# - history anchored at an issue: "since #1931", "seit #1040", "before #N", "pre-#888",
#   "formerly"
# - origin markers: "(#1073)", "(PR #537)", "(#2163, ADR-0041)", a leading "#1073:",
#   "Issue #1070:" or "#583/#1910:"
#
# Any other issue reference is only counted, per tree: a permitted one (workaround until an upstream
# fix, deprecation, spike) and one still woven into a sentence look alike to a pattern;
# "until #N"/"after #N" stay allowed. A test's "regression guard for #N: <invariant>" is counted
# apart as the permitted form; e2e/ and the frontend tests count as test trees. The spike package
# and a line naming "Spike #N" are exempt.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# label=path prefix; a line counts for the first entry whose prefix it starts with.
TREES=(
  migration=backend/src/main/resources/db/migration/
  main=backend/src/main/
  main=opaa-api/src/main/
  main=backend/src/configMetadata/
  test=backend/src/test/
  evalTest=backend/src/evalTest/
  workflows=.github/workflows/
  e2e=e2e/
  eval=eval/
  frontend=frontend/src/
  frontend=frontend/vite.config.ts
  frontend=frontend/eslint
  build=backend/
  build=opaa-api/
  build=frontend/
  build=docker-compose
  build=.env
  build=renovate.json5
  build=deploy/
  build=examples/
  demo=demo/
  scripts=.github/scripts/
)
# An issue reference; "owner/repo#123", "&#123;" and "page.md#123" are none.
REF='(?<![\w.&#/-])#[0-9]{2,4}\b'
# file:line: in front of every checked line
AT='^[^:]+:[0-9]+:'
REVIEW="(?i)${REF}\S*\s+((second|third|follow-up)\s+)?(code\s+|re-)?review\b|${REF}\s*\(review\)|\breview\s+(of|zu)\s+(PR\s+)?#[0-9]|\bre-review\b|\breview\s+(round|findings?)\b|\b(should-|optional\s+)?finding\s+[0-9]|\bnit[\s-]+[0-9]|\bReview-Runde\s+[0-9]|(?-i:\b(HIGH|MEDIUM|LOW)\s+[0-9]+\b)|\b(Befund|Wichtig)\s+([0-9]|\")"
HISTORY="(?i)\bpre-#[0-9]|\bformerly\b|\b(since|seit|before)\s+(epic\s+|PR\s+|issue\s+)?#[0-9]"
MARKER="\(\s*((PR|issue|see|Epic)\s+)?${REF}(\s*[,/]\s*((PR|issue)\s+)?${REF})*\s*([);]|,\s*\S)|${AT}\s*(//|/?\*|#|--)?\s*(<p>|<b>)?\s*(([Ii]ssues?|PR)\s+)?${REF}(\s*[,/]\s*#[0-9]{2,4})*\s*:"
EXEMPT="^backend/src/main/java/io/opaa/query/spike/|^\.github/scripts/check_code_comments\.sh:|(?i)\bspike\s+\(?#[0-9]"
# The form a test may keep: it names the regression and, after the colon, the invariant it guards.
TESTS='((backend/src/(test|evalTest)|e2e|frontend/src/test)/[^:]+|frontend/src/[^:]+\.test\.tsx?)'
GUARD="^${TESTS}:[0-9]+:(?i).*\bregression\s+guard\s+for\s+#[0-9]+((\s*,\s*|\s+and\s+|/)#[0-9]+)*:\s*\S"

# '//' and '/* */' comment lines of the files matching the pathspecs.
slash_comments() {
  git ls-files -z -- "$@" | xargs -0 -r grep -nHP '^\s*(//|/?\*)' || true
}

# '#' comment lines of the files matching the pathspecs.
hash_comments() {
  git ls-files -z -- "$@" | xargs -0 -r grep -nHP '^\s*#(?!!)' || true
}

# '#' lines and docstrings (a triple-quoted string opening a statement) of the Python files; any
# other triple-quoted string (an odd count of one quote kind on a line) is skipped to its end.
python_comments() {
  git ls-files -z -- "$@" | xargs -0 -r awk '
    function count(s, t) { return gsub(t, "", s) }
    FNR == 1 { doc = 0; data = 0 }
    doc { print FILENAME ":" FNR ":" $0; if (index($0, q)) doc = 0; next }
    data { if (count($0, q) % 2) data = 0; next }
    /^[ \t]*#/ { print FILENAME ":" FNR ":" $0; next }
    /^[ \t]*[rRbBuU]?("""|\047\047\047)/ {
      print FILENAME ":" FNR ":" $0
      q = index($0, "\"\"\"") ? "\"\"\"" : "\047\047\047"
      if (!index(substr($0, index($0, q) + 3), q)) doc = 1
      next
    }
    count($0, "\"\"\"") % 2 { q = "\"\"\""; data = 1; next }
    count($0, "\047\047\047") % 2 { q = "\047\047\047"; data = 1 }'
}

# '--' lines of the SQL migrations.
migration_comments() {
  git ls-files -z -- 'backend/src/main/resources/db/migration/*.sql' | xargs -0 -r grep -nH '^[[:space:]]*--' || true
}

# TypeScript and JavaScript comments: a line inside a block comment whole, otherwise from the first
# '/*' or '//' outside a one-line string literal, plus a '//' after a block comment closed on the
# same line; a '//' right after ':' is a URL.
ts_comments() {
  git ls-files -z -- 'frontend/src/*.ts' 'frontend/src/*.tsx' frontend/vite.config.ts \
    'frontend/eslint.config.*' 'frontend/eslint/*.js' 'frontend/eslint/*.mjs' | xargs -0 -r awk '
    FNR == 1 { block = 0 }
    block { print FILENAME ":" FNR ":" $0; if (index($0, "*/")) block = 0; next }
    {
      code = $0
      while (match(code, /"([^"\\]|\\.)*"|\047([^\047\\]|\\.)*\047|`([^`\\]|\\.)*`/)) {
        code = substr(code, 1, RSTART - 1) sprintf("%" RLENGTH "s", "") substr(code, RSTART + RLENGTH)
      }
      b = match(code, /\/\*/) ? RSTART : 0
      l = match(code, /(^|[^:])\/\//) ? RSTART + (substr(code, RSTART, 2) == "//" ? 0 : 1) : 0
      if (l && (!b || l < b)) { print FILENAME ":" FNR ":" substr($0, l); next }
      if (b) {
        print FILENAME ":" FNR ":" substr($0, b)
        e = index(substr(code, b + 2), "*/")
        if (!e) block = 1
        else if (match(substr(code, b + e + 3), /\/\//)) print FILENAME ":" FNR ":" substr($0, b + e + 2 + RSTART)
      }
    }'
}

lines() {
  slash_comments 'backend/src/main/*.java' 'opaa-api/src/main/*.java' 'backend/src/configMetadata/*.java' \
    'backend/src/test/*.java' \
    'backend/src/evalTest/*.java' 'e2e/*.ts' 'e2e/*.mjs' 'e2e/*.js' 'backend/*.gradle.kts' \
    'opaa-api/*.gradle.kts' 'renovate.json5'
  hash_comments 'backend/src/main/resources/application*.yml' 'e2e/*.yml' 'e2e/*.yaml' 'e2e/*.env' \
    '*.sh' 'docker-compose*.yml' '.env*.example' '*Dockerfile' 'backend/gradle/*.toml' \
    'frontend/nginx*.conf' 'demo/*.conf'
  python_comments 'eval/*.py' 'demo/*.py' 'backend/src/test/resources/*.py'
  git ls-files -z -- '.github/workflows/*.yml' | xargs -0 -r grep -nHP '^\s*(#|//)' || true
  git ls-files -z -- 'opaa-api/src/main/openapi/*.yaml' | xargs -0 -r grep -nH '' || true
  migration_comments
  ts_comments
}

# The tree label of every line read, in order.
labels() {
  awk -v trees="${TREES[*]}" '
    BEGIN { n = split(trees, t, " ") }
    NF { for (i = 1; i <= n; i++) { split(t[i], kv, "="); if (index($0, kv[2]) == 1) { print kv[1]; next } } }'
}

all="$(lines)"
if [ -z "$all" ]; then
  echo "::error::No comment line found in any checked tree - the guard would pass without checking anything. Verify this script."
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

# Issue references left, per tree; a test's regression guard is counted apart.
references="$(grep -P "${AT}.*${REF}" <<<"$candidates" || true)"
others="$(grep -vP "$GUARD" <<<"$references" | labels || true)"
guards="$(grep -P "$GUARD" <<<"$references" | labels || true)"
for label in $(printf '%s\n' "${TREES[@]%%=*}" | awk '!seen[$0]++'); do
  echo "::notice::$label: $(grep -cx "$label" <<<"$others" || true) checked lines still name an issue, $(grep -cx "$label" <<<"$guards" || true) more as a regression guard."
done
echo "Issue references are allowed only for a workaround until an upstream fix, a deprecation, a spike or a test's regression guard."
echo "All $checked checked lines follow the comment rule."
