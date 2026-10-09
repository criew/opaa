#!/usr/bin/env bash
# Starts the frontend image hardened as in production (read-only root, /tmp as tmpfs, no
# capabilities) in front of a stand-in backend that answers every /api/ and /mcp request only after
# DELAY seconds, then asserts the status code nginx returns. Needs Docker and curl on the host.
# With EXPECT_READ_TIMEOUT it also asserts that the rendered configuration sets exactly that
# proxy_read_timeout for /api/ and /mcp - the way to check the image default without waiting for it.
#
#   frontend/scripts/check-backend-read-timeout.sh [image]
#
#   IMAGE / $1       frontend image to test (default: opaa-frontend:local)
#   DELAY            seconds the stand-in backend waits before answering (default: 75)
#   EXPECT           expected HTTP status (default: 200)
#   REQUEST_PATH     path requested through the frontend (default: /api/v1/query)
#   OPAA_BACKEND_READ_TIMEOUT  passed to the frontend container when set
#   EXPECT_READ_TIMEOUT        expected proxy_read_timeout of /api/ and /mcp, e.g. 600s (optional)
#   BACKEND_IMAGE    image of the stand-in backend (default: python:3.13-alpine)
set -euo pipefail

IMAGE="${1:-${IMAGE:-opaa-frontend:local}}"
DELAY="${DELAY:-75}"
EXPECT="${EXPECT:-200}"
REQUEST_PATH="${REQUEST_PATH:-/api/v1/query}"
BACKEND_IMAGE="${BACKEND_IMAGE:-python:3.13-alpine}"

suffix="$$"
network="opaa-read-timeout-$suffix"
backend="opaa-read-timeout-backend-$suffix"
frontend="opaa-read-timeout-frontend-$suffix"

cleanup() {
  docker rm -f "$frontend" "$backend" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Answers GET and POST with 200 after DELAY seconds; logs nothing per request.
backend_script='
import http.server, os, time
delay = int(os.environ["DELAY"])
class Handler(http.server.BaseHTTPRequestHandler):
    def answer(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        time.sleep(delay)
        body = b"{\"answer\":\"ok\"}"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    do_GET = answer
    do_POST = answer
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
'

docker network create "$network" >/dev/null
docker run -d --name "$backend" --network "$network" --network-alias backend-standin \
  -e DELAY="$DELAY" "$BACKEND_IMAGE" python -c "$backend_script" >/dev/null

# Waits for the listening socket only: an HTTP probe would itself take DELAY seconds.
backend_ready=false
for _ in $(seq 1 30); do
  if docker exec "$backend" python -c \
    'import socket; socket.create_connection(("127.0.0.1", 8080), 1).close()' >/dev/null 2>&1; then
    backend_ready=true
    break
  fi
  sleep 1
done
if [ "$backend_ready" != true ]; then
  echo "FAIL: stand-in backend did not start listening" >&2
  docker logs "$backend" >&2
  exit 1
fi

frontend_env=(-e OPAA_BACKEND_UPSTREAM=backend-standin:8080)
if [ -n "${OPAA_BACKEND_READ_TIMEOUT+x}" ]; then
  frontend_env+=(-e "OPAA_BACKEND_READ_TIMEOUT=$OPAA_BACKEND_READ_TIMEOUT")
fi
docker run -d --name "$frontend" --network "$network" \
  --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
  "${frontend_env[@]}" -p 127.0.0.1::8080 "$IMAGE" >/dev/null

port="$(docker port "$frontend" 8080/tcp | head -n 1 | sed 's/.*://')"
ready=false
for _ in $(seq 1 30); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$port/index.html" 2>/dev/null; then
    ready=true
    break
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  echo "FAIL: frontend did not become ready" >&2
  docker logs "$frontend" >&2
  exit 1
fi

if [ -n "${EXPECT_READ_TIMEOUT:-}" ]; then
  rendered="$(docker exec "$frontend" nginx -T 2>/dev/null | grep -E '^[[:space:]]*proxy_read_timeout' || true)"
  expected_count="$(printf '%s\n' "$rendered" | grep -cxE "[[:space:]]*proxy_read_timeout $EXPECT_READ_TIMEOUT;" || true)"
  total_count="$(printf '%s\n' "$rendered" | grep -c 'proxy_read_timeout' || true)"
  echo "rendered proxy_read_timeout lines:"
  printf '%s\n' "$rendered"
  if [ "$expected_count" != 2 ] || [ "$total_count" != 2 ]; then
    echo "FAIL: expected proxy_read_timeout $EXPECT_READ_TIMEOUT for /api/ and /mcp" >&2
    exit 1
  fi
fi

echo "frontend $IMAGE on port $port, backend delay ${DELAY}s, POST $REQUEST_PATH"
start="$(date +%s)"
status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  --data '{"question":"x"}' --max-time $((DELAY + 120)) "http://127.0.0.1:$port$REQUEST_PATH" || true)"
elapsed=$(($(date +%s) - start))
echo "HTTP $status after ${elapsed}s"
echo "--- frontend log (last 5 lines)"
docker logs --tail 5 "$frontend" 2>&1

if [ "$status" != "$EXPECT" ]; then
  echo "FAIL: expected HTTP $EXPECT, got $status" >&2
  exit 1
fi
echo "OK: HTTP $EXPECT as expected"
