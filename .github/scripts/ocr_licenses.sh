#!/usr/bin/env bash
# License check of the OCR image opaa-ocr (docs/sbom.md, "Lizenzprüfung"): the Python packages of
# the image's CycloneDX SBOM (components with a pkg:pypi purl, as Trivy reads them from the package
# metadata) and the baked-in models listed in ocr/models.cdx.json, checked by check_licenses.py
# against .github/licenses/. The operating system packages of the base image are out of scope, like
# the OS of the backend and frontend images. Exit code as check_licenses.py.
#
#   .github/scripts/ocr_licenses.sh <image SBOM, CycloneDX JSON> <report.md>
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 <sbom.cdx.json> <report.md>" >&2
  exit 2
fi
sbom="$1"
report="$2"
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

jq '{bomFormat, specVersion, components: [.components[]? | select((.purl // "") | startswith("pkg:pypi/"))]}' \
  "$sbom" >"$work/python.cdx.json"
count="$(jq '.components | length' "$work/python.cdx.json")"
# An SBOM without Python packages means the scan missed the environment, not a clean result.
if ((count == 0)); then
  echo "no pkg:pypi component in $sbom; the SBOM does not cover the Python environment" >&2
  exit 2
fi
echo "$count Python packages, $(jq '.components | length' "$root/ocr/models.cdx.json") models"

python3 "$root/.github/scripts/check_licenses.py" \
  --allowed "$root/.github/licenses/allowed.txt" \
  --exceptions "$root/.github/licenses/exceptions.txt" \
  --cyclonedx "$work/python.cdx.json" \
  --cyclonedx "$root/ocr/models.cdx.json" \
  --title OCR \
  --report "$report"
