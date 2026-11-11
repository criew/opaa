#!/usr/bin/env bash
# Every step of the set-up assistant has an E2E scenario: each constant of
# backend/src/main/java/io/opaa/installation/InstallationStep.java needs its label in STEP_INFO of
# frontend/src/features/installation/utils/steps.ts (a string, or a key of SETUP_NAMES in
# frontend/src/shared/ui/setup/setupNames.ts), and that label must be waited for as a step
# heading (stepHeading(page, '<label>')) in e2e/tests/installation-assistant.spec.ts. A new step
# without a scenario fails here instead of turning the nightly assistant run red.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

ENUM=backend/src/main/java/io/opaa/installation/InstallationStep.java
STEPS=frontend/src/features/installation/utils/steps.ts
NAMES=frontend/src/shared/ui/setup/setupNames.ts
SPEC=e2e/tests/installation-assistant.spec.ts

# The constants of the outer enum: upper-case names at the start of a line, followed by "(", ","
# or ";", up to the first one that ends the list with ";" (nested enums follow later).
mapfile -t constants < <(
  awk '/^public enum InstallationStep/ { on = 1 } on { print } on && /^[[:space:]]*[A-Z][A-Z0-9_]*[[:space:]]*(\(.*\))?;/ { exit }' "$ENUM" |
    grep -oP '^\s*\K[A-Z][A-Z0-9_]*(?=\s*(\(|,|;))'
)
if [ "${#constants[@]}" -eq 0 ]; then
  echo "::error file=$ENUM::No constant of InstallationStep found; has the file moved?"
  exit 1
fi

failed=0
for constant in "${constants[@]}"; do
  label="$(grep -oP "^\s*${constant}:\s*\{\s*label:\s*'\K[^']+" "$STEPS" || true)"
  if [ -z "$label" ]; then
    key="$(grep -oP "^\s*${constant}:\s*\{\s*label:\s*SETUP_NAMES\.\K\w+" "$STEPS" || true)"
    if [ -n "$key" ] && [ -f "$NAMES" ]; then
      label="$(grep -oP "^\s*${key}:\s*'\K[^']+" "$NAMES" || true)"
    fi
  fi
  if [ -z "$label" ]; then
    echo "::error file=$STEPS::InstallationStep.$constant has no label in STEP_INFO."
    failed=1
    continue
  fi
  if ! grep -qF "stepHeading(page, '$label')" "$SPEC"; then
    echo "::error file=$SPEC::Step \"$label\" (InstallationStep.$constant) is never reached in the E2E scenario of the set-up assistant: add it to the scenarios."
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  exit 1
fi
echo "All ${#constants[@]} steps of the set-up assistant are covered by $SPEC."
