#!/usr/bin/env bash
# Tells whether a chart version already exists in an OCI repository, for the release guards in
# publish-images.yml: prints "true" or "false". Only an unambiguous "not found" counts as free; any
# other answer, such as 401 or 403 from the registry, fails with the registry's message, so an
# unreadable repository never passes as an unused version. Needs helm, logged in to the registry.
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 <oci://registry/repository/chart> <version>" >&2
  exit 2
fi

command -v helm >/dev/null || { echo "helm is not installed" >&2; exit 1; }

if out="$(helm show chart "$1" --version "$2" 2>&1)"; then
  echo true
elif grep -qE ': not found$' <<<"$out"; then
  echo false
else
  echo "Lookup of $1 version $2 failed: $out" >&2
  exit 1
fi
