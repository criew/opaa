#!/usr/bin/env bash
# Smoke test of the two OPAA images on the architecture of the Docker host: starts PostgreSQL with
# pgvector, the backend hardened as in production (read-only root, /tmp and /app/uploads as tmpfs,
# no capabilities, profile "oidc") and the frontend hardened the same way in front of it. Passes when
# the backend reports readiness UP, /api/health answers through the frontend's nginx and the
# frontend serves index.html. Model endpoints do not exist; readiness does not depend on them.
#
#   .github/scripts/image_smoke_test.sh <backend image> <frontend image>
#
#   EXPECT_ARCH       architecture both images must have, e.g. arm64 (default: the Docker host's)
#   POSTGRES_IMAGE    database image (default: pgvector/pgvector:pg18)
#   READY_TIMEOUT     seconds the backend may take to become ready (default: 300)
#   EXPECT_VERSION    version the backend must report in its start line and under /actuator/info,
#                     e.g. the OPAA_VERSION build argument of the image (default: not checked)
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 <backend image> <frontend image>" >&2
  exit 2
fi
backend_image="$1"
frontend_image="$2"
expect_arch="${EXPECT_ARCH:-$(docker version --format '{{.Server.Arch}}')}"
postgres_image="${POSTGRES_IMAGE:-pgvector/pgvector:pg18}"
ready_timeout="${READY_TIMEOUT:-300}"
expect_version="${EXPECT_VERSION:-}"

suffix="$$"
network="opaa-smoke-$suffix"
postgres="opaa-smoke-postgres-$suffix"
backend="opaa-smoke-backend-$suffix"
frontend="opaa-smoke-frontend-$suffix"

cleanup() {
  docker rm -f "$frontend" "$backend" "$postgres" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "FAIL: $1" >&2
  for container in "$backend" "$frontend"; do
    if docker container inspect "$container" >/dev/null 2>&1; then
      echo "--- log of $container (last 80 lines)" >&2
      docker logs --tail 80 "$container" >&2 2>&1 || true
    fi
  done
  exit 1
}

# A locally present image of the wrong architecture would run emulated and prove nothing.
for image in "$backend_image" "$frontend_image"; do
  arch="$(docker image inspect --format '{{.Architecture}}' "$image")"
  echo "$image: linux/$arch"
  [[ "$arch" == "$expect_arch" ]] || fail "$image is linux/$arch, expected linux/$expect_arch"
done

docker network create "$network" >/dev/null
docker run -d --name "$postgres" --network "$network" --network-alias postgres \
  -e POSTGRES_DB=opaa -e POSTGRES_USER=opaa -e POSTGRES_PASSWORD=smoke-only-password \
  "$postgres_image" >/dev/null

db_ready=false
# Over TCP: the image's init phase runs a temporary server on the Unix socket only and restarts it.
for _ in $(seq 1 60); do
  if docker exec "$postgres" pg_isready -h 127.0.0.1 -U opaa -d opaa >/dev/null 2>&1; then
    db_ready=true
    break
  fi
  sleep 1
done
[[ "$db_ready" == true ]] || fail "PostgreSQL did not become ready"

# Throwaway secrets, valid in form only: the jwt secret's minimum length and two Base64 AES-256 keys.
docker run -d --name "$backend" --network "$network" --network-alias backend \
  --read-only --tmpfs /tmp --tmpfs /app/uploads:uid=65532,gid=65532,mode=0700 \
  --cap-drop ALL --security-opt no-new-privileges \
  -e SPRING_PROFILES_ACTIVE=oidc \
  -e OPAA_SERVER_ADDRESS=0.0.0.0 \
  -e OPAA_PUBLIC_BASE_URL=https://opaa.example.org \
  -e OPAA_CORS_ALLOWED_ORIGINS=https://opaa.example.org \
  -e OPAA_DB_URL=jdbc:postgresql://postgres:5432/opaa \
  -e OPAA_DB_USERNAME=opaa \
  -e OPAA_DB_PASSWORD=smoke-only-password \
  -e OPAA_AUTH_JWT_SECRET=smoke-only-jwt-secret-0123456789abcdefghijklmnop \
  -e OPAA_CREDENTIALS_ENCRYPTION_KEY=bjzhjv2AKbnBjZCYAR2V/Glp7TYZzqABwkqXs3lEyCY= \
  -e OPAA_SETTINGS_ENCRYPTION_KEY=CH1X+hsIYeijer8S9wq5wKRnQa4DpUPePzw4KxGMTMw= \
  -e OPAA_INITIAL_ADMIN_EMAIL=it-postfach@example.org \
  -e OPAA_OPENAI_EMBEDDING_BASE_URL=http://models.invalid/v1 \
  -e OPAA_OPENAI_EMBEDDING_MODEL=nomic-embed-text \
  -e OPAA_PGVECTOR_DIMENSIONS=768 \
  -e OPAA_OPENAI_CHAT_BASE_URL=http://models.invalid/v1 \
  -e OPAA_OPENAI_CHAT_MODEL=qwen2.5:3b \
  -p 127.0.0.1::8080 "$backend_image" >/dev/null
backend_port="$(docker port "$backend" 8080/tcp | head -n 1 | sed 's/.*://')"

start="$(date +%s)"
readiness=""
while (($(date +%s) - start < ready_timeout)); do
  if [[ "$(docker container inspect --format '{{.State.Running}}' "$backend")" != true ]]; then
    fail "backend exited before becoming ready"
  fi
  readiness="$(curl -sS --max-time 5 "http://127.0.0.1:$backend_port/actuator/health/readiness" 2>/dev/null || true)"
  if [[ "$readiness" == *'"status":"UP"'* ]]; then
    break
  fi
  sleep 2
done
[[ "$readiness" == *'"status":"UP"'* ]] || fail "backend not ready within ${ready_timeout}s: ${readiness:-no answer}"
echo "backend ready after $(($(date +%s) - start))s: $readiness"

if [[ -n "$expect_version" ]]; then
  start_line="$(docker logs "$backend" 2>&1 | grep -m 1 'Starting OpaaApplication' || true)"
  [[ "$start_line" == *"Starting OpaaApplication v$expect_version using "* ]] ||
    fail "backend start line does not report version $expect_version: ${start_line:-none}"
  echo "start line reports version $expect_version"
  info="$(curl -sS --max-time 5 "http://127.0.0.1:$backend_port/actuator/info" || true)"
  [[ "$(jq -r '.build.version // empty' <<<"$info" 2>/dev/null)" == "$expect_version" ]] ||
    fail "/actuator/info does not report version $expect_version: ${info:-no answer}"
  echo "/actuator/info reports version $expect_version"
fi

docker run -d --name "$frontend" --network "$network" \
  --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
  -e OPAA_BACKEND_UPSTREAM=backend:8080 \
  -p 127.0.0.1::8080 "$frontend_image" >/dev/null
frontend_port="$(docker port "$frontend" 8080/tcp | head -n 1 | sed 's/.*://')"

index=""
for _ in $(seq 1 30); do
  index="$(curl -fsS --max-time 5 "http://127.0.0.1:$frontend_port/" 2>/dev/null || true)"
  [[ "$index" == *'<div id="root">'* ]] && break
  sleep 1
done
[[ "$index" == *'<div id="root">'* ]] || fail "frontend did not serve index.html"
echo "frontend serves index.html"

health_status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
  "http://127.0.0.1:$frontend_port/api/health" || true)"
[[ "$health_status" == 200 ]] || fail "/api/health through the frontend answered HTTP $health_status"
echo "/api/health through the frontend: HTTP 200"
echo "OK: backend and frontend run on linux/$expect_arch"
