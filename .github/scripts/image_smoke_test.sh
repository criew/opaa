#!/usr/bin/env bash
# Smoke test of the two OPAA images on the architecture of the Docker host: starts PostgreSQL with
# pgvector, migrates it with the backend image in its migration mode (OPAA_MODE=migrate, which must
# end with exit code 0, as must the wait for it), then the backend without migration, hardened as
# in production (read-only root, /tmp and /app/uploads as tmpfs, no capabilities, profile "oidc"),
# and the frontend hardened the same way in front of it. Passes when the backend reports readiness
# UP, its health probe agrees, /api/health answers through the frontend's nginx, the frontend
# serves index.html and the auth configuration reports the pending installation. Model endpoints
# do not exist; readiness does not depend on them. Needs curl and jq.
#
#   .github/scripts/image_smoke_test.sh <backend image> <frontend image>
#
#   EXPECT_ARCH       architecture both images must have, e.g. arm64 (default: the Docker host's)
#   POSTGRES_IMAGE    database image (default: pgvector/pgvector:pg18)
#   READY_TIMEOUT     seconds the backend may take to become ready (default: 300)
#   EXPECT_VERSION    version the backend must report in its start line and under /actuator/info
#                     and the frontend's entry bundle must carry for "Info zu OPAA", e.g. the
#                     OPAA_VERSION build argument of both images (default: not checked)
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
migration="opaa-smoke-migration-$suffix"
frontend="opaa-smoke-frontend-$suffix"

cleanup() {
  docker rm -f "$frontend" "$backend" "$migration" "$postgres" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "FAIL: $1" >&2
  for container in "$migration" "$backend" "$frontend"; do
    if docker container inspect "$container" >/dev/null 2>&1; then
      echo "--- log of $container (last 80 lines)" >&2
      docker logs --tail 80 "$container" >&2 2>&1 || true
    fi
  done
  exit 1
}

command -v jq >/dev/null || fail "the smoke test needs jq"

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

# Throwaway secrets, valid in form only: the jwt secret's minimum length and two Base64 AES-256 keys
# that are not among the published development and CI keys the "oidc" profile refuses.
# No answer file: the backend starts in installation mode (ADR-0060), whose readiness is UP.
backend_environment=(
  -e SPRING_PROFILES_ACTIVE=oidc
  -e OPAA_SERVER_ADDRESS=0.0.0.0
  -e OPAA_PUBLIC_BASE_URL=https://opaa.example.org
  -e OPAA_CORS_ALLOWED_ORIGINS=https://opaa.example.org
  -e OPAA_DB_URL=jdbc:postgresql://postgres:5432/opaa
  -e OPAA_DB_USERNAME=opaa
  -e OPAA_DB_PASSWORD=smoke-only-password
  -e OPAA_AUTH_JWT_SECRET=smoke-only-jwt-secret-0123456789abcdefghijklmnop
  -e OPAA_CREDENTIALS_ENCRYPTION_KEY=bgkeQqaMpiL1xFl4ymM9MVJ+jYlt+GCb4w9J8o5btxI=
  -e OPAA_SETTINGS_ENCRYPTION_KEY=NNEl4fE9dCl6LycesKNIxBXCY+hDKDDWV8D7hPi5e0A=
)
hardening=(--read-only --tmpfs /tmp --tmpfs /app/uploads:uid=65532,gid=65532,mode=0700
  --cap-drop ALL --security-opt no-new-privileges)

# The migration step: only Flyway, then exit 0 - also with SPRING_FLYWAY_ENABLED=false, which
# it shares with the instances.
docker run --name "$migration" --network "$network" "${hardening[@]}" "${backend_environment[@]}" \
  -e OPAA_MODE=migrate -e SPRING_FLYWAY_ENABLED=false "$backend_image" >/dev/null 2>&1 &&
  migration_exit=0 || migration_exit=$?
[[ "$migration_exit" == 0 ]] || fail "the migration mode ended with exit code $migration_exit"
migration_log="$(docker logs "$migration" 2>&1)"
[[ "$migration_log" == *'Started DatabaseMigrationMode'* && "$migration_log" != *'Started OpaaApplication'* ]] ||
  fail "the migration mode did not end as the migration alone"
echo "migration mode: exit code 0"

# The wait for the migration step, which an instance's init container runs: on the migrated
# database it reads the migrations out of the jar and ends at once with exit code 0.
docker run --rm --network "$network" "${hardening[@]}" "${backend_environment[@]}" \
  -e OPAA_MODE=await-migration "$backend_image" >/dev/null 2>&1 &&
  await_exit=0 || await_exit=$?
[[ "$await_exit" == 0 ]] || fail "the await-migration mode ended with exit code $await_exit on the migrated database"
echo "await-migration mode: exit code 0"

docker run -d --name "$backend" --network "$network" --network-alias backend \
  "${hardening[@]}" "${backend_environment[@]}" -e SPRING_FLYWAY_ENABLED=false \
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

# The Compose health check: a class beside the jar, run without a shell inside the container.
docker exec "$backend" /opt/java/openjdk/bin/java -cp /app/probe HealthProbe ||
  fail "the health probe of the image does not report the ready backend as healthy"
if docker exec "$backend" /opt/java/openjdk/bin/java -cp /app/probe HealthProbe \
  http://127.0.0.1:1/actuator/health/readiness 2>/dev/null; then
  fail "the health probe of the image reports an address nobody listens on as healthy"
fi
echo "health probe: healthy, and unhealthy without an answer"

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

if [[ -n "$expect_version" ]]; then
  entry="$(grep -o 'src="/assets/[^"]*\.js"' <<<"$index" | head -n 1 | sed 's/^src="//; s/"$//' || true)"
  [[ -n "$entry" ]] || fail "index.html references no entry bundle under /assets/"
  bundle="$(curl -fsS --max-time 10 "http://127.0.0.1:$frontend_port$entry" || true)"
  [[ "$bundle" == *"v$expect_version"* ]] ||
    fail "frontend bundle $entry does not carry version v$expect_version"
  echo "frontend bundle $entry carries version v$expect_version"
fi

health_status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
  "http://127.0.0.1:$frontend_port/api/health" || true)"
[[ "$health_status" == 200 ]] || fail "/api/health through the frontend answered HTTP $health_status"
echo "/api/health through the frontend: HTTP 200"

auth_config="$(curl -fsS --max-time 10 "http://127.0.0.1:$frontend_port/api/v1/auth/config" || true)"
[[ "$(jq -r '.installationPending' <<<"$auth_config" 2>/dev/null)" == true ]] ||
  fail "/api/v1/auth/config does not report the pending installation: ${auth_config:-no answer}"
echo "/api/v1/auth/config through the frontend: installation pending"
echo "OK: backend and frontend run on linux/$expect_arch"
