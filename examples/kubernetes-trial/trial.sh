#!/usr/bin/env bash
# Trial setup of OPAA on a local Kubernetes cluster - NOT FOR PRODUCTION.
#
#   trial.sh up     installs Keycloak, Ollama, RustFS, Mailpit and OPAA with its evaluation database
#   trial.sh down   removes everything "up" created (both namespaces, including all volumes)
#
# Works on the current kubectl context, or on KUBE_CONTEXT, and only on a local cluster unless
# TRIAL_ALLOW_CONTEXT names the context. Expects a cluster whose ingress controller is Traefik
# (k3s, k3d) and reaches it on TRIAL_PORT of this machine. Needs kubectl, helm and openssl.
# Secrets are generated on the first "up" and kept on every further one.
set -euo pipefail

TRIAL_PORT="${TRIAL_PORT:-8088}"
CHAT_MODEL="${TRIAL_CHAT_MODEL:-qwen2.5:3b}"
IMAGE_TAG="${OPAA_IMAGE_TAG:-main}"
# "main" moves, so every restarted pod pulls its current state; a release tag never changes.
PULL_POLICY=IfNotPresent
if [[ "$IMAGE_TAG" == main ]]; then PULL_POLICY=Always; fi

# Fixed by the manifests and the realm; changing one means changing them as well.
NAMESPACE=opaa-trial
SERVICES_NAMESPACE=opaa-trial-services
RELEASE=opaa
EMBEDDING_MODEL=nomic-embed-text
S3_BUCKET=opaa-uploads
ADMIN_EMAIL=it-postfach@opaa-trial.example

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
chart_dir="$script_dir/../../deploy/helm/opaa"

# Plain arrays with the "+" expansion, because macOS ships bash 3.2, where an empty array counts as
# unset under "set -u".
kubectl_args=()
helm_args=()
if [[ -n "${KUBE_CONTEXT:-}" ]]; then
  kubectl_args=(--context "$KUBE_CONTEXT")
  helm_args=(--kube-context "$KUBE_CONTEXT")
fi

k() { kubectl ${kubectl_args[@]+"${kubectl_args[@]}"} "$@"; }
h() { helm ${helm_args[@]+"${helm_args[@]}"} "$@"; }

info() { printf '\n==> %s\n' "$*"; }
fail() {
  printf 'Fehler: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<EOF
Aufruf: $(basename "$0") up|down

  up     Erprobungsaufbau installieren oder aktualisieren
  down   Erprobungsaufbau vollständig entfernen

Umgebungsvariablen:
  TRIAL_PORT        Port, unter dem der Ingress-Controller auf diesem Rechner erreichbar ist (${TRIAL_PORT})
  TRIAL_CHAT_MODEL  Chat-Modell, das Ollama zieht und OPAA beim ersten Start übernimmt (${CHAT_MODEL})
  OPAA_IMAGE_TAG    Tag der OPAA-Images (${IMAGE_TAG})
  KUBE_CONTEXT      kubectl-Kontext statt des aktuellen
  TRIAL_ALLOW_CONTEXT
                    Name eines Kontexts, der nicht nach lokalem Cluster aussieht, aber gemeint ist
EOF
}

require_tools() {
  local tool
  for tool in kubectl helm openssl base64; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool wird benötigt, ist aber nicht installiert."
  done
}

address() {
  if [[ "$TRIAL_PORT" == 80 ]]; then
    printf 'http://%s' "$1"
  else
    printf 'http://%s:%s' "$1" "$TRIAL_PORT"
  fi
}

# Contexts of clusters on this machine; any other context needs TRIAL_ALLOW_CONTEXT=<its name>,
# so that "up" or "down" never reaches a shared or production cluster by accident.
LOCAL_CONTEXTS=('k3d-*' 'kind-*' docker-desktop rancher-desktop orbstack minikube)

check_context() {
  local context="${KUBE_CONTEXT:-}" pattern is_local=""
  if [[ -z "$context" ]]; then
    context="$(kubectl config current-context 2>/dev/null)" ||
      fail "Kein kubectl-Kontext gewählt; KUBE_CONTEXT setzen oder einen Kontext wählen."
  fi
  info "Kubernetes-Kontext: $context"
  for pattern in "${LOCAL_CONTEXTS[@]}"; do
    # shellcheck disable=SC2053 # the pattern is meant to match as a glob
    if [[ "$context" == $pattern ]]; then is_local=1; fi
  done
  if [[ -z "$is_local" && "${TRIAL_ALLOW_CONTEXT:-}" != "$context" ]]; then
    fail "Der Kontext $context ist kein lokaler Cluster (${LOCAL_CONTEXTS[*]}). Ist er trotzdem gemeint: TRIAL_ALLOW_CONTEXT=$context setzen."
  fi
  k cluster-info >/dev/null 2>&1 || fail "Der Cluster des Kontexts $context ist nicht erreichbar."
}

# Refuses to touch a namespace of the same name that this setup did not create.
check_namespaces() {
  local namespace part_of
  for namespace in "$NAMESPACE" "$SERVICES_NAMESPACE"; do
    k get namespace "$namespace" >/dev/null 2>&1 || continue
    part_of="$(k get namespace "$namespace" \
      -o 'jsonpath={.metadata.labels.app\.kubernetes\.io/part-of}')"
    [[ "$part_of" == opaa-trial ]] ||
      fail "Namespace $namespace gehört nicht zum Erprobungsaufbau (Label app.kubernetes.io/part-of=opaa-trial fehlt); nichts geändert."
  done
}

secret_value() {
  k -n "$1" get secret "$2" -o "jsonpath={.data.$3}" | base64 -d
}

# One private directory (mktemp -d) for the generated keys, removed on every exit; each secret is
# created from a subdirectory with one file per key, so no value appears on a command line.
work_dir=""
cleanup() {
  if [[ -n "$work_dir" ]]; then rm -rf "$work_dir"; fi
}
trap cleanup EXIT

new_secret_dir() {
  mkdir "$work_dir/$1"
  printf '%s' "$work_dir/$1"
}

ensure_secrets() {
  local dir
  work_dir="$(mktemp -d)"
  if ! k -n "$NAMESPACE" get secret opaa-secrets >/dev/null 2>&1; then
    dir="$(new_secret_dir opaa-secrets)"
    openssl rand -base64 48 | tr -d '\n' >"$dir/OPAA_AUTH_JWT_SECRET"
    openssl rand -base64 32 | tr -d '\n' >"$dir/OPAA_CREDENTIALS_ENCRYPTION_KEY"
    openssl rand -base64 32 | tr -d '\n' >"$dir/OPAA_SETTINGS_ENCRYPTION_KEY"
    openssl rand -hex 24 | tr -d '\n' >"$dir/OPAA_DB_PASSWORD"
    openssl rand -hex 24 | tr -d '\n' >"$dir/OPAA_INITIAL_ADMIN_PASSWORD"
    printf 'opaa-%s' "$(openssl rand -hex 6)" >"$dir/OPAA_UPLOAD_S3_ACCESS_KEY"
    openssl rand -hex 24 | tr -d '\n' >"$dir/OPAA_UPLOAD_S3_SECRET_KEY"
    k -n "$NAMESPACE" create secret generic opaa-secrets --from-file="$dir" >/dev/null
    echo "Geheimnisse von OPAA erzeugt (Secret opaa-secrets)."
  fi
  if ! k -n "$SERVICES_NAMESPACE" get secret rustfs-credentials >/dev/null 2>&1; then
    dir="$(new_secret_dir rustfs-credentials)"
    secret_value "$NAMESPACE" opaa-secrets OPAA_UPLOAD_S3_ACCESS_KEY >"$dir/accessKey"
    secret_value "$NAMESPACE" opaa-secrets OPAA_UPLOAD_S3_SECRET_KEY >"$dir/secretKey"
    k -n "$SERVICES_NAMESPACE" create secret generic rustfs-credentials --from-file="$dir" >/dev/null
  fi
  if ! k -n "$SERVICES_NAMESPACE" get secret keycloak-admin >/dev/null 2>&1; then
    dir="$(new_secret_dir keycloak-admin)"
    openssl rand -hex 16 | tr -d '\n' >"$dir/password"
    k -n "$SERVICES_NAMESPACE" create secret generic keycloak-admin --from-file="$dir" >/dev/null
  fi
  cleanup
}

apply_settings() {
  local namespace
  for namespace in "$NAMESPACE" "$SERVICES_NAMESPACE"; do
    k -n "$namespace" create configmap trial-settings \
      --from-literal=OPAA_PUBLIC_BASE_URL="$OPAA_URL" \
      --from-literal=KEYCLOAK_URL="$KEYCLOAK_URL" \
      --from-literal=CHAT_MODEL="$CHAT_MODEL" \
      --from-literal=EMBEDDING_MODEL="$EMBEDDING_MODEL" \
      --from-literal=S3_BUCKET="$S3_BUCKET" \
      --from-literal=ADMIN_EMAIL="$ADMIN_EMAIL" \
      --dry-run=client -o yaml | k apply -f - >/dev/null
  done
  k -n "$SERVICES_NAMESPACE" create configmap keycloak-realm \
    --from-file=realm-opaa.json="$script_dir/keycloak/realm-opaa.json" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
}

# A job's pod template cannot change, so the jobs live outside manifests/ and are replaced on every
# "up" instead of applied over. Only the mail setup stays once it succeeded: it signs in with the
# first password of the administrator, which may have changed since.
apply_jobs() {
  local jobs_dir="$script_dir/jobs"
  k -n "$SERVICES_NAMESPACE" delete job rustfs-create-bucket ollama-pull-models \
    --ignore-not-found >/dev/null
  k apply -f "$jobs_dir/rustfs-create-bucket.yaml" -f "$jobs_dir/ollama-pull-models.yaml" >/dev/null
  if [[ "$(k -n "$NAMESPACE" get job opaa-mail-setup -o 'jsonpath={.status.succeeded}' \
    2>/dev/null)" != 1 ]]; then
    k -n "$NAMESPACE" delete job opaa-mail-setup --ignore-not-found >/dev/null
    k apply -f "$jobs_dir/opaa-mail-setup.yaml" >/dev/null
  fi
}

install_opaa() {
  h upgrade --install "$RELEASE" "$chart_dir" --namespace "$NAMESPACE" \
    --values "$script_dir/opaa-values.yaml" \
    --set-string publicBaseUrl="$OPAA_URL" \
    --set-string ingress.host=opaa.localhost \
    --set-string bootstrap.oidc.issuerUri="$KEYCLOAK_URL/realms/opaa" \
    --set-string frontend.cspConnectSrcExtra="$KEYCLOAK_URL" \
    --set-string bootstrap.initialAdmin.email="$ADMIN_EMAIL" \
    --set-string embedding.model="$EMBEDDING_MODEL" \
    --set-string bootstrap.chatModel.model="$CHAT_MODEL" \
    --set-string uploads.s3.bucket="$S3_BUCKET" \
    --set-string backend.image.tag="$IMAGE_TAG" \
    --set-string frontend.image.tag="$IMAGE_TAG" \
    --set-string backend.image.pullPolicy="$PULL_POLICY" \
    --set-string frontend.image.pullPolicy="$PULL_POLICY"
}

wait_for() {
  local namespace="$1" what="$2" timeout="$3"
  echo "Warte auf $what ..."
  k -n "$namespace" rollout status "$what" --timeout="${timeout}s" >/dev/null
}

# Waits until a job succeeded; ends early once it failed, instead of running into the timeout.
wait_for_job() {
  local namespace="$1" job="$2" timeout="$3" deadline status
  echo "Warte auf job/$job ..."
  deadline=$((SECONDS + timeout))
  while :; do
    status="$(k -n "$namespace" get job "$job" -o \
      'jsonpath={.status.succeeded}/{.status.conditions[?(@.type=="Failed")].status}')"
    case "$status" in
      1/*) return ;;
      */True) fail "job/$job ist fehlgeschlagen: kubectl -n $namespace logs job/$job" ;;
    esac
    ((SECONDS < deadline)) ||
      fail "job/$job ist nach $timeout s nicht fertig: kubectl -n $namespace logs job/$job"
    sleep 5
  done
}

up() {
  OPAA_URL="$(address opaa.localhost)"
  KEYCLOAK_URL="$(address keycloak.localhost)"
  check_context
  check_namespaces

  info "Namespaces, Geheimnisse und Einstellungen"
  k apply -f "$script_dir/manifests/namespaces.yaml" >/dev/null
  ensure_secrets
  apply_settings

  info "Dienste und OPAA installieren"
  k apply -f "$script_dir/manifests/" >/dev/null
  apply_jobs
  install_opaa >/dev/null

  info "Warten, bis alles bereit ist (beim ersten Mal lädt Ollama die Modelle)"
  wait_for "$SERVICES_NAMESPACE" deploy/rustfs 300
  wait_for_job "$SERVICES_NAMESPACE" rustfs-create-bucket 300
  wait_for "$SERVICES_NAMESPACE" deploy/mailpit 300
  wait_for "$SERVICES_NAMESPACE" deploy/keycloak 600
  wait_for "$SERVICES_NAMESPACE" deploy/ollama 900
  wait_for_job "$SERVICES_NAMESPACE" ollama-pull-models 2700
  wait_for "$NAMESPACE" deploy/opaa-frontend 300
  wait_for "$NAMESPACE" deploy/opaa-backend 900
  wait_for_job "$NAMESPACE" opaa-mail-setup 600

  info "Bereit nach ${SECONDS} s"
  cat <<EOF
OPAA:      $OPAA_URL
           Anmeldung über Keycloak: testuser / testpass oder maria.weber / RheinfurtDemo!2026
           Systemverwaltung: $OPAA_URL/login/system mit $ADMIN_EMAIL, Passwort:
             kubectl -n $NAMESPACE get secret opaa-secrets -o jsonpath='{.data.OPAA_INITIAL_ADMIN_PASSWORD}' | base64 -d
Keycloak:  $KEYCLOAK_URL (Verwaltung: admin, Passwort:
             kubectl -n $SERVICES_NAMESPACE get secret keycloak-admin -o jsonpath='{.data.password}' | base64 -d)
Mailpit:   $(address mailpit.localhost)
Chat-Modell: $CHAT_MODEL, Embedding-Modell: $EMBEDDING_MODEL

Nur zur Erprobung. Abbauen mit: $0 down
EOF
}

down() {
  check_context
  check_namespaces
  info "Erprobungsaufbau entfernen"
  k delete namespace "$NAMESPACE" "$SERVICES_NAMESPACE" --ignore-not-found --wait=true \
    --timeout=10m
  info "Entfernt nach ${SECONDS} s"
}

require_tools
case "${1:-}" in
  up) up ;;
  down) down ;;
  *)
    usage
    exit 2
    ;;
esac
