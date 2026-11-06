#!/usr/bin/env bash
# Converts satzung-scan.pdf with a real opaa-ocr image and writes the DoclingDocument of the answer
# to the given file (default satzung-scan.docling.json next to this script) - the answer the
# ocr-stub of the E2E stack replays. Sends exactly the options of TextRecognitionClient.
# Usage: capture_docling.sh <opaa-ocr image> [output file]
set -euo pipefail

image="$1"
here="$(cd "$(dirname "$0")" && pwd)"
out="${2:-$here/satzung-scan.docling.json}"
port="${OCR_CAPTURE_PORT:-15001}"
key="$(openssl rand -hex 32)"
name="opaa-ocr-capture-$$"

docker run -d --rm --name "$name" -p "127.0.0.1:$port:5001" \
  -e DOCLING_SERVE_API_KEY="$key" -e DOCLING_SERVE_MAX_SYNC_WAIT=1800 "$image" >/dev/null
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT

for _ in $(seq 1 120); do
  if curl -fsS "http://127.0.0.1:$port/ready" >/dev/null 2>&1; then break; fi
  sleep 5
done
curl -fsS "http://127.0.0.1:$port/ready" >/dev/null

answer="$(mktemp)"
curl -fsS --max-time 1800 -H "X-Api-Key: $key" -H 'Accept: application/json' \
  -F from_formats=pdf -F to_formats=json -F do_ocr=true -F force_ocr=true \
  -F ocr_engine=rapidocr -F ocr_lang=de -F do_table_structure=true -F table_mode=accurate \
  -F image_export_mode=placeholder -F abort_on_error=false \
  -F "files=@$here/satzung-scan.pdf;type=application/pdf;filename=document.pdf" \
  "http://127.0.0.1:$port/v1/convert/file" -o "$answer"

status="$(jq -r .status "$answer")"
if [[ "$status" != success ]]; then
  echo "opaa-ocr answered with status $status" >&2
  jq '.errors' "$answer" >&2
  exit 1
fi
jq '.document.json_content' "$answer" >"$out"
echo "wrote $out ($(jq '.texts | length' "$out") texts, $(jq '.tables | length' "$out") tables)"
