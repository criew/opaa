#!/usr/bin/env bash
# Starts the OPAA stack in operating mode "oidc" with local accounts (the Compose setup of the E2E
# target "local-auth", without Keycloak), runs the ZAP baseline scan anonymously against it through
# the frontend's nginx and always tears the stack down again.
#
# Usage: zap_baseline.sh <zap-image> <output-dir>
# The rules in .github/zap/rules.yaml are rendered into ZAP's rule file first. The output directory
# receives zap-report.{json,html,md}, zap.log and docker-compose.log. Exit status: that of
# zap-baseline.py (0 nothing reported, 1 FAIL, 2 WARN, 3 error), or 1 if the stack does not start.
# ZAP_SKIP_BUILD=true starts prebuilt images opaa-zap-backend:latest and opaa-zap-frontend:latest.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <zap-image> <output-dir>" >&2
  exit 64
fi
ZAP_IMAGE="$1"
OUT_DIR="$(mkdir -p "$2" && cd "$2" && pwd)"

cd "$(git rev-parse --show-toplevel)"

export COMPOSE_PROJECT_NAME=opaa-zap
export OPAA_FRONTEND_PORT=13010
export OPAA_BACKEND_PORT=18092
export OPAA_DB_PORT=15440
export OPAA_MAILPIT_SMTP_PORT=11030
export OPAA_MAILPIT_WEB_PORT=18030
export OPAA_INSTALLATION_DIR_HOST=./e2e/installation
# e2e/local-auth.env trusts X-Forwarded-For only from this address (ADR-0033, Entscheidung 9).
export OPAA_COMPOSE_SUBNET=172.31.0.0/16
export OPAA_COMPOSE_FRONTEND_ADDRESS=172.31.0.10

TARGET="http://localhost:${OPAA_FRONTEND_PORT}"
COMPOSE=(docker compose --profile mail -f docker-compose.yml -f e2e/docker-compose.local-auth.yml)
SERVICES=(ai-stub mailpit postgres backend frontend)

# The empty key and secret lines of the env file get values generated for this run, as
# e2e/scripts/run-env-file.mjs does for the E2E targets: the "oidc" profile refuses public ones.
work="$(mktemp -d)"
export OPAA_ENV_FILE="$work/local-auth.env"
cp e2e/local-auth.env "$OPAA_ENV_FILE"
for entry in OPAA_CREDENTIALS_ENCRYPTION_KEY:32 OPAA_SETTINGS_ENCRYPTION_KEY:32 OPAA_AUTH_JWT_SECRET:48; do
  name="${entry%%:*}"
  value="$(openssl rand -base64 "${entry##*:}")"
  sed -i "s|^${name}=\$|${name}=${value}|" "$OPAA_ENV_FILE"
done

teardown() {
  "${COMPOSE[@]}" logs --no-color --timestamps >"$OUT_DIR/docker-compose.log" 2>&1 || true
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap teardown EXIT

python3 .github/scripts/zap_rules.py render --rules .github/zap/rules.yaml --out "$work/rules.conf"

mkdir -p documents uploads
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
up=(up -d)
if [ "${ZAP_SKIP_BUILD:-false}" != "true" ]; then
  up+=(--build)
fi
if ! "${COMPOSE[@]}" "${up[@]}" "${SERVICES[@]}"; then
  echo "::error::docker compose up failed, see docker-compose.log"
  exit 1
fi

ready=false
for _ in $(seq 1 90); do
  if curl -fsS -o /dev/null "$TARGET/api/v1/auth/config"; then
    ready=true
    break
  fi
  sleep 2
done
if [ "$ready" != true ]; then
  echo "::error::$TARGET/api/v1/auth/config did not answer within 180 s"
  exit 1
fi

# ZAP runs as uid 1000 and writes its reports into /zap/wrk.
chmod 777 "$work"
set +e
docker run --rm --network host \
  -v "$work:/zap/wrk:rw" \
  -v "$PWD/.github/zap:/zap/opaa:ro" \
  "$ZAP_IMAGE" zap-baseline.py \
  -t "$TARGET/" \
  -c rules.conf \
  -J zap-report.json -r zap-report.html -w zap-report.md \
  --hook=/zap/opaa/hooks.py \
  2>&1 | tee "$OUT_DIR/zap.log"
status="${PIPESTATUS[0]}"
set -e

for report in zap-report.json zap-report.html zap-report.md; do
  if [ -f "$work/$report" ]; then
    cp "$work/$report" "$OUT_DIR/"
  fi
done
exit "$status"
