#!/usr/bin/env bash
# Three rules for every file under .github/workflows/, a new workflow is covered without a list:
# 1. No run: or script: value interpolates github.head_ref, github.ref_name, github.ref,
#    github.workflow_ref, github.event in any form (github.event.*, github.event['...'],
#    toJSON(github.event)), the github context as a whole or by index (toJSON(github),
#    github['...']), or the inputs, secrets, vars and env contexts in any form (inputs.x,
#    secrets['X'], toJSON(secrets), env.X).
#    Actions expands ${{ ... }} into the script text before the shell or JavaScript runs it, so a
#    branch name, PR title or dispatch input with shell syntax would execute. Such values go into
#    an env: entry and are read as "$NAME" (process.env.NAME in actions/github-script).
#    The match is textual: a string literal inside an expression ('github', 'env') is rejected
#    as well; such an expression moves into env: like any other.
# 2. The workflow sets permissions: at top level, so no job falls back to the repository default.
# 3. Every actions/checkout step states persist-credentials: false, or true where the job pushes
#    from that checkout afterwards. Otherwise the token stays in .git/config for every later step.
# The scan is line based (plain POSIX awk, runners ship mawk): block and inline scalars, steps in
# block style. A key written in flow style ({ run: ... }) is not recognised.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

shopt -s nullglob
WORKFLOWS=(.github/workflows/*.yml .github/workflows/*.yaml)

# Prints "script <file>:<line>" per script key and "<rule> <file>:<line>" per violation.
scan() {
  awk '
    function indent_of(s) { match(s, /^[ ]*/); return RLENGTH }
    function check(text) {
      lowered = tolower(text)
      # Anywhere inside the expression up to its closing braces; a single brace (a format() pattern)
      # does not end it.
      if (lowered ~ /\$\{\{(([^}]|\}[^}])*[^a-z0-9_.])?(github\.head_ref|github\.ref_name|github\.(ref|workflow_ref)[^a-z0-9_]|github\.event[^a-z0-9_]|github[^a-z0-9_.]|(inputs|secrets|vars|env)[^a-z0-9_])/) {
        printf "context %s:%d\n", FILENAME, FNR
      }
    }
    function close_step() {
      if (step_col >= 0 && checkout_line > 0 && !has_persist) {
        printf "credentials %s:%d\n", FILENAME, checkout_line
      }
      step_col = -1
    }
    function close_file() {
      close_step()
      if (file != "" && !has_permissions) { printf "permissions %s:1\n", file }
    }
    FNR == 1 {
      close_file()
      file = FILENAME; has_permissions = 0; in_script = 0; step_col = -1
    }
    {
      sub(/\r$/, "")
      if (in_script) {
        if ($0 ~ /^[ ]*$/ || indent_of($0) > script_col) { check($0); next }
        in_script = 0
      }
      if ($0 ~ /^[ ]*(#.*)?$/) { next }
      ind = indent_of($0)
      if (step_col >= 0 && ind <= step_col) { close_step() }
      if (step_col < 0 && $0 ~ /^[ ]*-[ ]/) {
        step_col = ind; checkout_line = 0; has_persist = 0
      }
      if ($0 ~ /^permissions[ ]*:/) { has_permissions = 1 }
      if ($0 ~ /^[ ]*(-[ ]+)?uses[ ]*:[ ]*["\047]?actions\/checkout@/) { checkout_line = FNR }
      if ($0 ~ /^[ ]*persist-credentials[ ]*:[ ]*["\047]?(true|false)/) { has_persist = 1 }
      if (match($0, /^[ ]*(-[ ]+)?(run|script)[ ]*:/)) {
        value = substr($0, RSTART + RLENGTH)
        # An empty value is a mapping (defaults: run: working-directory), not a script.
        if (value ~ /^[ ]*(#.*)?$/) { next }
        key = substr($0, 1, RSTART + RLENGTH - 1)
        match(key, /^[ ]*(-[ ]+)?/)
        script_col = RLENGTH
        in_script = 1
        printf "script %s:%d\n", FILENAME, FNR
        check(value)
      }
    }
    END { close_file() }
  ' "$@"
}

if [ "${#WORKFLOWS[@]}" -eq 0 ]; then
  echo "::error::No workflow found in .github/workflows - the guard would pass without checking anything. Verify this script."
  exit 1
fi

scripts=0
rejected=0
while IFS= read -r entry; do
  rule="${entry%% *}"
  location="${entry#* }"
  file="${location%:*}"
  line="${location##*:}"
  case "$rule" in
  script)
    scripts=$((scripts + 1))
    continue
    ;;
  context)
    message="A run:/script: value interpolates github.head_ref, github.ref_name, github.ref, github.workflow_ref, github.event, the github context as a whole, inputs, secrets, vars or env directly. Pass it through env: and read it as \"\$NAME\" (process.env.NAME in github-script)."
    ;;
  permissions)
    message="The workflow sets no top-level permissions: block. Grant the minimum (usually contents: read, or {}) and name anything more in the job that needs it."
    ;;
  credentials)
    message="actions/checkout without persist-credentials. Set persist-credentials: false, or true if the job pushes from this checkout afterwards."
    ;;
  esac
  rejected=$((rejected + 1))
  echo "::error file=$file,line=$line::$message"
done < <(scan "${WORKFLOWS[@]}")

if [ "$scripts" -eq 0 ]; then
  echo "::error::No run: or script: key found in .github/workflows - the guard would pass without checking anything. Verify this script."
  exit 1
fi

if [ "$rejected" -gt 0 ]; then
  echo "::error::$rejected violations in ${#WORKFLOWS[@]} workflows ($scripts scripts checked)."
  exit 1
fi

echo "All ${#WORKFLOWS[@]} workflows pass ($scripts scripts read untrusted context through env:, permissions and checkout credentials set)."
