#!/usr/bin/env bash
# Smoke test of the OCR image opaa-ocr on the architecture of the Docker host. Every container runs
# without network (--network none, only loopback), hardened as in production: read-only root, /tmp
# as the only writable path, no capabilities, no new privileges. Passes when
#   - the container refuses to start without DOCLING_SERVE_API_KEY and with a key that is too short,
#   - with a key it becomes ready offline, so it fetches nothing, and /health answers,
#   - a conversion without the key, with a wrong key or with an engine the image does not carry is
#     refused,
#   - the test page ocr/test/scan-bescheid.pdf comes back from /v1/convert/source as a
#     DoclingDocument JSON with the expected text including "§", the table and a frame per element
#     (.github/scripts/ocr_smoke_check.py).
# Prints image size and cold start time. Needs jq, openssl and python3 on the host; the HTTP calls
# run inside the container (.github/scripts/ocr_smoke_client.py), the only way in without network.
#
#   .github/scripts/ocr_smoke_test.sh <image>
#
#   EXPECT_ARCH     architecture the image must have, e.g. arm64 (default: the Docker host's)
#   READY_TIMEOUT   seconds the service may take to become ready (default: 600)
#   RESULT_DIR      directory that keeps the conversion answer (default: a temporary directory)
set -euo pipefail

if (($# != 1)); then
  echo "Usage: $0 <image>" >&2
  exit 2
fi
image="$1"
expect_arch="${EXPECT_ARCH:-$(docker version --format '{{.Server.Arch}}')}"
ready_timeout="${READY_TIMEOUT:-600}"
here="$(cd "$(dirname "$0")" && pwd)"
fixture="$(cd "$here/../../ocr/test" && pwd)/scan-bescheid.pdf"
result_dir="${RESULT_DIR:-$(mktemp -d)}"
mkdir -p "$result_dir"

suffix="$$"
service="opaa-ocr-smoke-$suffix"
refused="opaa-ocr-smoke-refused-$suffix"

cleanup() {
  docker rm -f "$service" "$refused" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "FAIL: $1" >&2
  if docker container inspect "$service" >/dev/null 2>&1; then
    echo "--- log of $service (last 120 lines)" >&2
    docker logs --tail 120 "$service" >&2 2>&1 || true
  fi
  exit 1
}

for tool in jq openssl python3; do
  command -v "$tool" >/dev/null || fail "the smoke test needs $tool"
done
[[ -f "$fixture" ]] || fail "test page $fixture is missing"

# A locally present image of the wrong architecture would run emulated and prove nothing.
arch="$(docker image inspect --format '{{.Architecture}}' "$image")"
echo "$image: linux/$arch"
[[ "$arch" == "$expect_arch" ]] || fail "$image is linux/$arch, expected linux/$expect_arch"
size="$(docker image inspect --format '{{.Size}}' "$image")"
echo "image size: $size bytes ($((size / 1024 / 1024)) MiB, uncompressed)"

hardening=(--network none --read-only --tmpfs "/tmp:rw,exec,size=4g"
  --cap-drop ALL --security-opt no-new-privileges)

# Without a key, and with a key below the minimum length, the entrypoint ends at once with exit
# code 64 before docling-serve starts.
for key in "" "too-short-key"; do
  docker run --name "$refused" "${hardening[@]}" -e "DOCLING_SERVE_API_KEY=$key" "$image" \
    >/dev/null 2>&1 && exit_code=0 || exit_code=$?
  log="$(docker logs "$refused" 2>&1 || true)"
  docker rm -f "$refused" >/dev/null 2>&1 || true
  [[ "$exit_code" == 64 && "$log" == *DOCLING_SERVE_API_KEY* ]] ||
    fail "start with key '${key:-<empty>}' ended with exit code $exit_code instead of 64: $log"
  echo "start with key '${key:-<empty>}' refused: ${log%%$'\n'*}"
done

# A throwaway key for this run; a value written into this file would be public.
key="$(openssl rand -hex 32)"
start="$(date +%s)"
docker run -d --name "$service" "${hardening[@]}" -e "DOCLING_SERVE_API_KEY=$key" \
  -v "$here/ocr_smoke_client.py:/smoke/client.py:ro" -v "$fixture:/smoke/scan-bescheid.pdf:ro" \
  "$image" >/dev/null

client() {
  docker exec "$service" /opt/app-root/bin/python /smoke/client.py "$@"
}

# HTTP status of a conversion of the test page with the given key and preset.
convert_status() {
  client convert /smoke/scan-bescheid.pdf "$@" >"$result_dir/refused.txt" || true
  head -n 1 "$result_dir/refused.txt"
}

ready=""
while (($(date +%s) - start < ready_timeout)); do
  if [[ "$(docker container inspect --format '{{.State.Running}}' "$service")" != true ]]; then
    fail "the service exited before becoming ready"
  fi
  ready="$(client status /ready 2>/dev/null || true)"
  [[ "$ready" == 200 ]] && break
  sleep 2
done
[[ "$ready" == 200 ]] || fail "the service was not ready within ${ready_timeout}s (last /ready: ${ready:-no answer})"
cold_start=$(($(date +%s) - start))
echo "cold start: ready after ${cold_start}s without network"
[[ "$(client status /health)" == 200 ]] || fail "/health does not answer 200"
echo "/health: HTTP 200"

for attempt in "-" "wrong-key-$(openssl rand -hex 16)"; do
  status="$(convert_status "$attempt")"
  [[ "$status" == 401 ]] ||
    fail "a conversion with key '${attempt/#-/<none>}' answered HTTP $status instead of 401"
  echo "conversion with key '${attempt/#-/<none>}': HTTP 401"
done

# Only the presets "auto" and "rapidocr" exist, both RapidOCR with the baked-in models.
status="$(convert_status "$key" easyocr)"
[[ "$status" == 422 ]] || fail "a conversion with the preset easyocr answered HTTP $status instead of 422"
echo "conversion with preset easyocr: HTTP 422"

convert_start="$(date +%s)"
client convert /smoke/scan-bescheid.pdf "$key" >"$result_dir/answer.txt" ||
  fail "the conversion call failed"
status="$(head -n 1 "$result_dir/answer.txt")"
tail -n +2 "$result_dir/answer.txt" >"$result_dir/scan-bescheid.json"
[[ "$status" == 200 ]] ||
  fail "the conversion answered HTTP $status: $(head -c 2000 "$result_dir/scan-bescheid.json")"
echo "conversion: HTTP 200 after $(($(date +%s) - convert_start))s," \
  "processing time $(jq -r '.processing_time' "$result_dir/scan-bescheid.json")s"
python3 "$here/ocr_smoke_check.py" "$result_dir/scan-bescheid.json" ||
  fail "the DoclingDocument of the test page lacks expected content"
# Peak memory of the container (cgroup v2) up to here, the basis of the sizing in the handbook.
peak="$(docker exec "$service" cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0)"
echo "memory peak after one page: $((peak / 1024 / 1024)) MiB"

# Offline proof beyond the successful start: nothing in the log may report a download attempt.
if docker logs "$service" 2>&1 | grep -Ei 'downloading|huggingface\.co|modelscope' >&2; then
  fail "the service log reports a download attempt"
fi
echo "OK: opaa-ocr runs offline on linux/$expect_arch; size $((size / 1024 / 1024)) MiB, cold start ${cold_start}s"
