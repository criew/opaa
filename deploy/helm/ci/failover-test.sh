#!/usr/bin/env bash
# Failover test of the chart (docs/entwicklung/build-und-ci.md, "Umschaltung der Datenbank und
# Räumen eines Knotens"; ADR-0053, ADR-0076): a kind cluster of four nodes, a CloudNativePG cluster
# of two instances with pgvector, the object store as a container outside the cluster, and two
# backend pods under load while the primary of the database is switched over, fails, or the node
# of one backend pod and one database instance is drained. Not part of the CI; run from the
# repository root on a host with Docker, kind, kubectl, helm, node, pnpm and openssl.
#
#   deploy/helm/ci/failover-test.sh [all|up|run|down]
#
# up    builds nothing: expects the images BACKEND_IMAGE (default opaa-failover/backend:ci) and
#       FRONTEND_IMAGE (opaa-failover/frontend:ci) in the local Docker, creates the cluster
#       (reuses it if it exists), the database, the object store, the stand-ins and the release.
# run   runs e2e/failover/ once per disturbance in DISTURBANCES (default "switchover failover
#       drain") and repetition up to RUNS (default 2); DB_URL=hosts first switches the backend to a
#       connection URL with one host per instance (targetServerType=primary) instead of the
#       operator's read-write service (DB_URL=service, the default); HOSTS_PARAMS appends driver
#       parameters to that URL (e.g. "&hostRecheckSeconds=2"), RUN_LABEL (default DB_URL) prefixes
#       the result files. Results: e2e/failover-results/.
# down  deletes the cluster, the object store container and the Docker network, and nothing else.
# all   up, run, down; KEEP_CLUSTER=1 skips down.
#
# Uses its own kubeconfig (FAILOVER_KUBECONFIG, default ${TMPDIR:-/tmp}/opaa-failover.kubeconfig)
# and never touches another context. Memory: about 5 GB while the cluster runs.
set -euo pipefail

CLUSTER=opaa-failover
NETWORK=opaa-failover
OBJECT_STORE=opaa-failover-s3
NAMESPACE=opaa-failover
DB_NAMESPACE=failover-db
TEST_NAMESPACE=failover-test
RELEASE=opaa
CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
DIR=deploy/helm/ci/failover
BACKEND_IMAGE="${BACKEND_IMAGE:-opaa-failover/backend:ci}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:-opaa-failover/frontend:ci}"
DISTURBANCES="${DISTURBANCES:-switchover failover drain}"
RUNS="${RUNS:-2}"
DB_URL="${DB_URL:-service}"
HOSTS_URL="jdbc:postgresql://opaa-db-1.failover-db.svc.cluster.local:5432,opaa-db-2.failover-db.svc.cluster.local:5432/opaa?targetServerType=primary&connectTimeout=2&prepareThreshold=0${HOSTS_PARAMS:-}"
RUN_LABEL="${RUN_LABEL:-$DB_URL}"
CORPUS_SIZE="${CORPUS_SIZE:-40}"
# Newest supported Kubernetes version, as in multi-instance.yml.
KIND_NODE_IMAGE="${KIND_NODE_IMAGE:-kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5}"
# Moved up by hand together with the checksum of its release manifest.
CNPG_VERSION=1.30.2
CNPG_MANIFEST_SHA256=ea4cc355b394e10661d30c6b409cefb19029dd973bccb76b1ed8c789985bc68c
OBJECT_STORE_IMAGE=rustfs/rustfs:1.0.3
AWS_CLI_IMAGE=amazon/aws-cli:2.38.1
export KUBECONFIG="${FAILOVER_KUBECONFIG:-${TMPDIR:-/tmp}/opaa-failover.kubeconfig}"
secrets_file="${KUBECONFIG}.secrets"
repo_root="$(pwd)"
pid_file="$(mktemp)"

repository() { echo "${1%:*}"; }
tag() { echo "${1##*:}"; }

forward_cleanup() {
  while read -r pid; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done <"$pid_file"
  : >"$pid_file"
}
trap 'forward_cleanup; rm -f "$pid_file"' EXIT

aws() {
  docker run --rm --network "$NETWORK" -e AWS_ACCESS_KEY_ID=opaa-kind \
    -e AWS_SECRET_ACCESS_KEY=opaa-kind-secret -e AWS_DEFAULT_REGION=us-east-1 \
    -e "AWS_ENDPOINT_URL=http://$OBJECT_STORE:9000" -e AWS_REQUEST_CHECKSUM_CALCULATION=when_required \
    -e AWS_RESPONSE_CHECKSUM_VALIDATION=when_required "$@"
}

cluster() {
  if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    kind export kubeconfig --name "$CLUSTER" --kubeconfig "$KUBECONFIG"
  else
    # The cluster in a Docker network of its own, which the object store joins.
    KIND_EXPERIMENTAL_DOCKER_NETWORK="$NETWORK" kind create cluster --name "$CLUSTER" \
      --config "$DIR/kind-config.yaml" --image "$KIND_NODE_IMAGE" --kubeconfig "$KUBECONFIG" --wait 3m
  fi
  kind load docker-image "$BACKEND_IMAGE" "$FRONTEND_IMAGE" --name "$CLUSTER"
}

operator() {
  local manifest
  manifest="$(mktemp)"
  curl -sSfL -o "$manifest" \
    "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-${CNPG_VERSION%.*}/releases/cnpg-$CNPG_VERSION.yaml"
  echo "$CNPG_MANIFEST_SHA256  $manifest" | sha256sum -c --quiet
  kubectl apply --server-side -f "$manifest" >/dev/null
  rm -f "$manifest"
  # On the node no disturbance drains: the operator carries out the switchover of a drain.
  kubectl -n cnpg-system patch deployment cnpg-controller-manager --type merge \
    -p '{"spec":{"template":{"spec":{"nodeSelector":{"opaa-failover/infra":"true"}}}}}'
  kubectl -n cnpg-system rollout status deployment cnpg-controller-manager --timeout=5m
}

database() {
  kubectl create namespace "$DB_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
  kubectl apply -f "$DIR/database.yaml"
  kubectl -n "$DB_NAMESPACE" wait --for=condition=Ready cluster/opaa-db --timeout=10m
}

object_store() {
  if ! docker ps --format '{{.Names}}' | grep -qx "$OBJECT_STORE"; then
    docker run -d --name "$OBJECT_STORE" --network "$NETWORK" --memory 512m \
      -e RUSTFS_ACCESS_KEY=opaa-kind -e RUSTFS_SECRET_KEY=opaa-kind-secret -e RUSTFS_VOLUMES=/data \
      "$OBJECT_STORE_IMAGE" >/dev/null
  fi
  local i corpus
  for i in $(seq 1 30); do
    aws "$AWS_CLI_IMAGE" s3 ls >/dev/null 2>&1 && break
    sleep 2
  done
  aws "$AWS_CLI_IMAGE" s3 mb s3://opaa-originals 2>/dev/null || true
  aws "$AWS_CLI_IMAGE" s3 mb s3://corpus 2>/dev/null || true
  # The corpus the scenario indexes, like multi-instance-test.sh.
  corpus="$(mktemp -d)"
  for i in $(seq -w 1 "$CORPUS_SIZE"); do
    printf "Vermerk %s zur Erprobung der Umschaltung, HALTEMARKE.\n\nDieser Text steht in Dokument %s.\n" \
      "$i" "$i" >"$corpus/vermerk-$i.txt"
  done
  aws -v "$corpus:/corpus:ro" "$AWS_CLI_IMAGE" s3 sync --only-show-errors /corpus s3://corpus/
  rm -rf "$corpus"
}

fixtures() {
  kubectl create namespace "$TEST_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$TEST_NAMESPACE" create configmap ai-stub --from-file=server.mjs=e2e/ai-stub/server.mjs \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl apply -f "$DIR/fixtures.yaml"
  kubectl -n "$TEST_NAMESPACE" rollout status deployment/ai-stub --timeout=5m
  kubectl -n "$TEST_NAMESPACE" rollout status deployment/mailpit --timeout=5m
}

# The signing secret and the AES keys, generated once per cluster and kept next to its kubeconfig,
# so that a later "run" with DB_URL=hosts upgrades with the same ones.
secrets() {
  if [[ ! -s "$secrets_file" ]]; then
    {
      echo "JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')"
      echo "CREDENTIALS_KEY=$(openssl rand -base64 32)"
      echo "SETTINGS_KEY=$(openssl rand -base64 32)"
    } >"$secrets_file"
    chmod 600 "$secrets_file"
  fi
  # shellcheck disable=SC1090
  source "$secrets_file"
}

release() { # install|upgrade
  secrets
  local values=(-f "$DIR/values.yaml")
  # --set splits on commas; the hosts of the URL are separated by them.
  [[ "$DB_URL" == hosts ]] && values+=(-f "$DIR/values-hosts.yaml" --set-string "backend.extraEnv[0].value=${HOSTS_URL//,/\\,}")
  kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
  kubectl label namespace "$NAMESPACE" --overwrite pod-security.kubernetes.io/enforce=restricted \
    pod-security.kubernetes.io/enforce-version=latest
  local start=$SECONDS
  helm upgrade --install "$RELEASE" "$CHART_DIR" --namespace "$NAMESPACE" "${values[@]}" \
    --set-string "secrets.jwtSecret=$JWT_SECRET" \
    --set-string "secrets.credentialsEncryptionKey=$CREDENTIALS_KEY" \
    --set-string "secrets.settingsEncryptionKey=$SETTINGS_KEY" \
    --set "backend.image.repository=$(repository "$BACKEND_IMAGE")" --set "backend.image.tag=$(tag "$BACKEND_IMAGE")" \
    --set "frontend.image.repository=$(repository "$FRONTEND_IMAGE")" --set "frontend.image.tag=$(tag "$FRONTEND_IMAGE")" \
    --wait --wait-for-jobs --timeout 15m
  kubectl -n "$NAMESPACE" rollout status "deployment/$RELEASE-backend" --timeout=10m
  echo "helm upgrade --install (DB_URL=$DB_URL): $((SECONDS - start)) s"
  kubectl get pods -A -o wide --field-selector metadata.namespace!=kube-system
}

forward() { # target local:remote namespace
  kubectl -n "$3" port-forward "$1" "$2" >/dev/null 2>&1 &
  echo $! >>"$pid_file"
}

scenario() { # disturbance run
  mkdir -p "$repo_root/e2e/failover-results"
  (
    cd e2e
    E2E_MULTI_PLATFORM=kind \
      E2E_KIND_NAMESPACE="$NAMESPACE" \
      E2E_KIND_TEST_NAMESPACE="$TEST_NAMESPACE" \
      E2E_KIND_RELEASE="$RELEASE" \
      E2E_KIND_FRONTEND_PORT=18280 \
      E2E_KIND_FORWARD_PIDS="$pid_file" \
      E2E_KIND_LOAD_NODE_SELECTOR='{"opaa-failover/infra":"true"}' \
      E2E_MAILPIT_BASE_URL=http://127.0.0.1:18126 \
      E2E_AI_STUB_BASE_URL=http://127.0.0.1:18190 \
      E2E_SMTP_HOST="mailpit.$TEST_NAMESPACE.svc.cluster.local" \
      E2E_SMTP_PORT=1025 \
      E2E_CORPUS_S3_URL="http://$OBJECT_STORE:9000" \
      E2E_CORPUS_S3_CREDENTIALS=opaa-kind:opaa-kind-secret \
      E2E_CORPUS_SIZE="$CORPUS_SIZE" \
      E2E_DB_POOL_SIZE=5 \
      E2E_FAILOVER_DB_NAMESPACE="$DB_NAMESPACE" \
      E2E_FAILOVER_DRAIN_NODE="$CLUSTER-worker2" \
      E2E_FAILOVER_DISTURBANCE="$1" \
      E2E_FAILOVER_RUN="$RUN_LABEL-$1-$2" \
      E2E_FAILOVER_RESULTS="$repo_root/e2e/failover-results" \
      pnpm exec playwright test --config failover/playwright.config.ts
  )
}

up() {
  cluster
  operator
  database
  object_store
  fixtures
  release
}

run() {
  local desired=""
  [[ "$DB_URL" == hosts ]] && desired="$HOSTS_URL"
  if [[ "$(helm -n "$NAMESPACE" get values "$RELEASE" -o json | node -e \
    'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>console.log((JSON.parse(d).backend?.extraEnv??[]).find(e=>e.name==="OPAA_DB_URL")?.value??""))')" != "$desired" ]]; then
    release
  fi
  forward svc/mailpit 18126:8025 "$TEST_NAMESPACE"
  forward svc/ai-stub 18190:8089 "$TEST_NAMESPACE"
  local failed=0 disturbance i
  for disturbance in $DISTURBANCES; do
    for i in $(seq 1 "$RUNS"); do
      echo "> failover test, DB_URL=$DB_URL, $disturbance, run $i"
      scenario "$disturbance" "$i" || failed=1
    done
  done
  node e2e/failover/summary.mjs "$repo_root/e2e/failover-results"
  return "$failed"
}

down() {
  kind delete cluster --name "$CLUSTER" --kubeconfig "$KUBECONFIG" || true
  docker rm -f -v "$OBJECT_STORE" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -f "$KUBECONFIG" "$secrets_file"
}

case "${1:-all}" in
  up) up ;;
  run) run ;;
  down) down ;;
  all)
    [[ "${KEEP_CLUSTER:-0}" == 1 ]] || trap 'forward_cleanup; rm -f "$pid_file"; down' EXIT
    up
    run
    ;;
  *)
    echo "usage: $0 [all|up|run|down]" >&2
    exit 2
    ;;
esac
