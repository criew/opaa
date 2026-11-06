#!/bin/sh
# Start of opaa-ocr: refuses to run without an API key, since docling-serve itself answers every
# request without one when DOCLING_SERVE_API_KEY is empty. The key has no default and must have at
# least 32 characters; every request then has to send it in the header X-Api-Key. A key from a
# configuration file (DOCLING_SERVE_CONFIG_FILE) does not count, only the environment variable.
set -eu

min_length=32
key="${DOCLING_SERVE_API_KEY:-}"
trimmed="$(printf '%s' "$key" | tr -d '[:space:]')"

if [ -z "$trimmed" ]; then
  echo "opaa-ocr: DOCLING_SERVE_API_KEY is not set; refusing to start without an API key" >&2
  exit 64
fi
if [ "${#trimmed}" -ne "${#key}" ]; then
  echo "opaa-ocr: DOCLING_SERVE_API_KEY contains whitespace; refusing to start" >&2
  exit 64
fi
if [ "${#key}" -lt "$min_length" ]; then
  echo "opaa-ocr: DOCLING_SERVE_API_KEY has ${#key} characters, at least $min_length are required" >&2
  exit 64
fi

exec "$@"
