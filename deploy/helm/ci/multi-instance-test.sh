#!/usr/bin/env bash
# Two-instance test of the chart on kind (ADR-0053; docs/entwicklung/build-und-ci.md,
# "Zwei-Instanzen-Suite"), run by .github/workflows/multi-instance.yml and locally from the
# repository root. Runs the scenarios of e2e/multi-instance/ in two phases, each into a fresh
# namespace: "fresh" installs the current images with backend.replicas 2 against an empty
# evaluation database (scenarios 01-04, 06, 07), "rolling" installs the previous images and lets
# scenario 05 upgrade to the current ones under load, followed by a rollout restart.
#
# Expects the current kubectl context to point at a kind cluster holding the images
#   NEXT_BACKEND (default opaa-ci/backend:ci)          PREVIOUS_BACKEND (opaa-ci/backend:previous)
#   NEXT_FRONTEND (default opaa-ci/frontend:ci)        PREVIOUS_FRONTEND (opaa-ci/frontend:previous)
# and e2e/node_modules installed (pnpm install in e2e/). Needs kubectl, helm, node and pnpm.
# PHASES selects phases (default "fresh rolling"). Deletes the namespaces opaa-multi and multi-test
# before and after the run; KEEP_NAMESPACES=1 leaves them after a failed run for the search.
set -euo pipefail

CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
VALUES="deploy/helm/ci/install-values-multi.yaml"
NAMESPACE=opaa-multi
TEST_NAMESPACE=multi-test
RELEASE=opaa
NEXT_BACKEND="${NEXT_BACKEND:-opaa-ci/backend:ci}"
NEXT_FRONTEND="${NEXT_FRONTEND:-opaa-ci/frontend:ci}"
PREVIOUS_BACKEND="${PREVIOUS_BACKEND:-opaa-ci/backend:previous}"
PREVIOUS_FRONTEND="${PREVIOUS_FRONTEND:-opaa-ci/frontend:previous}"
PHASES="${PHASES:-fresh rolling}"
CORPUS_SIZE="${CORPUS_SIZE:-40}"
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

# The backend pods first, while the evaluation database still answers: a pod whose database is gone
# before it is spends its whole stop window on refused connections.
uninstall() {
  if kubectl -n "$NAMESPACE" get deployment "$RELEASE-backend" >/dev/null 2>&1; then
    kubectl -n "$NAMESPACE" scale deployment "$RELEASE-backend" --replicas=0
    kubectl -n "$NAMESPACE" wait --for=delete pod -l app.kubernetes.io/component=backend --timeout=5m || true
  fi
  helm uninstall "$RELEASE" --namespace "$NAMESPACE" --wait --timeout 5m >/dev/null 2>&1 || true
}

delete_namespaces() {
  uninstall
  kubectl delete namespace "$NAMESPACE" "$TEST_NAMESPACE" --ignore-not-found --wait=true --timeout=5m
}

failed=0
on_exit() {
  forward_cleanup
  rm -f "$pid_file"
  if [[ "$failed" -ne 0 && "${KEEP_NAMESPACES:-0}" == 1 ]]; then
    echo "Namespaces $NAMESPACE and $TEST_NAMESPACE are kept for the search (KEEP_NAMESPACES=1)."
  else
    delete_namespaces
  fi
}
trap on_exit EXIT

forward() { # target local:remote namespace
  kubectl -n "$3" port-forward "$1" "$2" >/dev/null 2>&1 &
  echo $! >>"$pid_file"
}

fixtures() {
  kubectl create namespace "$TEST_NAMESPACE"
  kubectl -n "$TEST_NAMESPACE" create configmap ai-stub --from-file=server.mjs=e2e/ai-stub/server.mjs
  kubectl apply -f deploy/helm/ci/multi-instance-fixtures.yaml
  for deployment in objectstore ai-stub mailpit; do
    kubectl -n "$TEST_NAMESPACE" rollout status "deployment/$deployment" --timeout=5m
  done
  # The bucket of the originals and the corpus the scenarios index, like corpus-init in
  # e2e/docker-compose.multi-instance.yml.
  kubectl -n "$TEST_NAMESPACE" run buckets --rm -i --restart=Never --image=amazon/aws-cli:2.38.1 \
    --env AWS_ACCESS_KEY_ID=opaa-kind --env AWS_SECRET_ACCESS_KEY=opaa-kind-secret \
    --env AWS_DEFAULT_REGION=us-east-1 --env AWS_ENDPOINT_URL=http://objectstore:9000 \
    --env AWS_REQUEST_CHECKSUM_CALCULATION=when_required \
    --env AWS_RESPONSE_CHECKSUM_VALIDATION=when_required --env CORPUS_SIZE="$CORPUS_SIZE" \
    --command -- /bin/sh -c '
      aws s3 mb s3://opaa-originals && aws s3 mb s3://corpus && mkdir -p /tmp/corpus &&
      for i in $(seq -w 1 "$CORPUS_SIZE"); do
        printf "Vermerk %s zur Erprobung mit zwei Instanzen, HALTEMARKE.\n\nDieser Text steht in Dokument %s und wird von genau einer Instanz eingelesen.\n" "$i" "$i" >"/tmp/corpus/vermerk-$i.txt"
      done && aws s3 sync --only-show-errors /tmp/corpus s3://corpus/ && aws s3 ls s3://corpus/ | wc -l'
  forward svc/mailpit 18026:8025 "$TEST_NAMESPACE"
  forward svc/ai-stub 18090:8089 "$TEST_NAMESPACE"
}

install() { # backend-image frontend-image
  kubectl create namespace "$NAMESPACE"
  kubectl label namespace "$NAMESPACE" pod-security.kubernetes.io/enforce=restricted \
    pod-security.kubernetes.io/enforce-version=latest
  local start=$SECONDS
  helm install "$RELEASE" "$CHART_DIR" --namespace "$NAMESPACE" -f "$VALUES" \
    --set "backend.image.repository=$(repository "$1")" --set "backend.image.tag=$(tag "$1")" \
    --set "frontend.image.repository=$(repository "$2")" --set "frontend.image.tag=$(tag "$2")" \
    --wait --wait-for-jobs --timeout 15m
  echo "helm install ($1): $((SECONDS - start)) s"
  kubectl -n "$NAMESPACE" get pods -o wide
}

scenarios() { # spec-filter...
  local upgrade
  upgrade="$(node -e '
    const [chart, ns, values, backend, frontend] = process.argv.slice(1);
    const split = (image) => [image.slice(0, image.lastIndexOf(":")), image.slice(image.lastIndexOf(":") + 1)];
    const [br, bt] = split(backend);
    const [fr, ft] = split(frontend);
    console.log(JSON.stringify(["upgrade", "opaa", chart, "--namespace", ns, "-f", values,
      "--set", `backend.image.repository=${br}`, "--set", `backend.image.tag=${bt}`,
      "--set", `frontend.image.repository=${fr}`, "--set", `frontend.image.tag=${ft}`,
      "--wait", "--wait-for-jobs", "--timeout", "15m"]));
  ' "$(realpath "$CHART_DIR")" "$NAMESPACE" "$(realpath "$VALUES")" "$NEXT_BACKEND" "$NEXT_FRONTEND")"
  (
    cd e2e
    E2E_MULTI_PLATFORM=kind \
      E2E_KIND_NAMESPACE="$NAMESPACE" \
      E2E_KIND_TEST_NAMESPACE="$TEST_NAMESPACE" \
      E2E_KIND_RELEASE="$RELEASE" \
      E2E_KIND_UPGRADE_ARGS="$upgrade" \
      E2E_KIND_FORWARD_PIDS="$pid_file" \
      E2E_MAILPIT_BASE_URL=http://127.0.0.1:18026 \
      E2E_AI_STUB_BASE_URL=http://127.0.0.1:18090 \
      E2E_AI_STUB_INTERNAL_URL="http://ai-stub.$TEST_NAMESPACE.svc.cluster.local:8089" \
      E2E_SMTP_HOST="mailpit.$TEST_NAMESPACE.svc.cluster.local" \
      E2E_SMTP_PORT=1025 \
      E2E_CORPUS_S3_URL="http://objectstore.$TEST_NAMESPACE.svc.cluster.local:9000" \
      E2E_CORPUS_S3_CREDENTIALS=opaa-kind:opaa-kind-secret \
      E2E_CORPUS_SIZE="$CORPUS_SIZE" \
      E2E_DB_POOL_SIZE=5 \
      E2E_EXPECT_VERSION_CHANGE="${E2E_EXPECT_VERSION_CHANGE:-false}" \
      pnpm exec playwright test --config multi-instance/playwright.config.ts "$@"
  )
}

delete_namespaces
fixtures
for phase in $PHASES; do
  echo "> multi-instance on kind, phase $phase"
  case "$phase" in
    fresh)
      install "$NEXT_BACKEND" "$NEXT_FRONTEND"
      scenarios 01- 02- 03- 04- 06- 07- || failed=1
      ;;
    rolling)
      install "$PREVIOUS_BACKEND" "$PREVIOUS_FRONTEND"
      scenarios 05- || failed=1
      ;;
    *)
      echo "unknown phase $phase" >&2
      exit 2
      ;;
  esac
  if [[ "$failed" -ne 0 ]]; then
    kubectl -n "$NAMESPACE" get pods -o wide || true
    kubectl -n "$NAMESPACE" logs -l app.kubernetes.io/component=backend --all-containers \
      --tail=-1 --prefix >"$repo_root/e2e/kind-multi-instance-$phase.log" 2>&1 || true
    exit 1
  fi
  uninstall
  kubectl delete namespace "$NAMESPACE" --wait=true --timeout=5m
done
