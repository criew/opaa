#!/usr/bin/env bash
# Trial setup of OPAA on a local Kubernetes cluster - NOT FOR PRODUCTION.
#
#   trial.sh up     installs Keycloak, Ollama, RustFS, Mailpit and OPAA with its evaluation database
#   trial.sh down   removes everything "up" created (both namespaces, including all volumes)
#
# Works on the current kubectl context, or on KUBE_CONTEXT. Expects a cluster whose ingress
# controller is Traefik (k3s, k3d) and reaches it on TRIAL_PORT of this machine. Needs kubectl,
# helm and openssl. Secrets are generated on the first "up" and kept on every further one.
set -euo pipefail

TRIAL_PORT="${TRIAL_PORT:-8088}"
CHAT_MODEL="${TRIAL_CHAT_MODEL:-qwen2.5:3b}"
IMAGE_TAG="${OPAA_IMAGE_TAG:-main}"

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

show_context() {
  local context="${KUBE_CONTEXT:-}"
  if [[ -z "$context" ]]; then
    context="$(kubectl config current-context 2>/dev/null)" ||
      fail "Kein kubectl-Kontext gewählt; KUBE_CONTEXT setzen oder einen Kontext wählen."
  fi
  info "Kubernetes-Kontext: $context"
  k cluster-info >/dev/null 2>&1 || fail "Der Cluster des Kontexts $context ist nicht erreichbar."
}

secret_value() {
  k -n "$1" get secret "$2" -o "jsonpath={.data.$3}" | base64 -d
}

# Creates a secret from a directory holding one file per key (mktemp -d is private to the user),
# so no value appears on a command line, and removes the directory.
create_secret_from_dir() {
  local namespace="$1" name="$2" dir="$3"
  k -n "$namespace" create secret generic "$name" --from-file="$dir" >/dev/null
  rm -rf "$dir"
}

ensure_secrets() {
  local dir
  if ! k -n "$NAMESPACE" get secret opaa-secrets >/dev/null 2>&1; then
    dir="$(mktemp -d)"
    openssl rand -base64 48 | tr -d '\n' >"$dir/OPAA_AUTH_JWT_SECRET"
    openssl rand -base64 32 | tr -d '\n' >"$dir/OPAA_CREDENTIALS_ENCRYPTION_KEY"
    openssl rand -base64 32 | tr -d '\n' >"$dir/OPAA_SETTINGS_ENCRYPTION_KEY"
    openssl rand -hex 24 | tr -d '\n' >"$dir/OPAA_DB_PASSWORD"
    openssl rand -hex 24 | tr -d '\n' >"$dir/OPAA_INITIAL_ADMIN_PASSWORD"
    printf 'opaa-%s' "$(openssl rand -hex 6)" >"$dir/OPAA_UPLOAD_S3_ACCESS_KEY"
    openssl rand -hex 24 | tr -d '\n' >"$dir/OPAA_UPLOAD_S3_SECRET_KEY"
    create_secret_from_dir "$NAMESPACE" opaa-secrets "$dir"
    echo "Geheimnisse von OPAA erzeugt (Secret opaa-secrets)."
  fi
  if ! k -n "$SERVICES_NAMESPACE" get secret rustfs-credentials >/dev/null 2>&1; then
    dir="$(mktemp -d)"
    secret_value "$NAMESPACE" opaa-secrets OPAA_UPLOAD_S3_ACCESS_KEY >"$dir/accessKey"
    secret_value "$NAMESPACE" opaa-secrets OPAA_UPLOAD_S3_SECRET_KEY >"$dir/secretKey"
    create_secret_from_dir "$SERVICES_NAMESPACE" rustfs-credentials "$dir"
  fi
  if ! k -n "$SERVICES_NAMESPACE" get secret keycloak-admin >/dev/null 2>&1; then
    dir="$(mktemp -d)"
    openssl rand -hex 16 | tr -d '\n' >"$dir/password"
    create_secret_from_dir "$SERVICES_NAMESPACE" keycloak-admin "$dir"
  fi
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

# A job's pod template cannot change, so jobs run again on every "up". Only the mail setup stays
# once it succeeded: it signs in with the first password of the administrator, which may have
# changed since.
reset_jobs() {
  k -n "$SERVICES_NAMESPACE" delete job rustfs-create-bucket ollama-pull-models \
    --ignore-not-found >/dev/null
  if [[ "$(k -n "$NAMESPACE" get job opaa-mail-setup -o 'jsonpath={.status.succeeded}' \
    2>/dev/null)" != 1 ]]; then
    k -n "$NAMESPACE" delete job opaa-mail-setup --ignore-not-found >/dev/null
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
    --set-string frontend.image.tag="$IMAGE_TAG"
}

wait_for() {
  local namespace="$1" what="$2" timeout="$3"
  echo "Warte auf $what ..."
  case "$what" in
    job/*) k -n "$namespace" wait --for=condition=complete "$what" --timeout="$timeout" >/dev/null ;;
    *) k -n "$namespace" rollout status "$what" --timeout="$timeout" >/dev/null ;;
  esac
}

up() {
  OPAA_URL="$(address opaa.localhost)"
  KEYCLOAK_URL="$(address keycloak.localhost)"
  show_context

  info "Namespaces, Geheimnisse und Einstellungen"
  k apply -f "$script_dir/manifests/namespaces.yaml" >/dev/null
  ensure_secrets
  apply_settings
  reset_jobs

  info "Dienste und OPAA installieren"
  k apply -f "$script_dir/manifests/" >/dev/null
  install_opaa >/dev/null

  info "Warten, bis alles bereit ist (beim ersten Mal lädt Ollama die Modelle)"
  wait_for "$SERVICES_NAMESPACE" deploy/rustfs 5m
  wait_for "$SERVICES_NAMESPACE" job/rustfs-create-bucket 5m
  wait_for "$SERVICES_NAMESPACE" deploy/mailpit 5m
  wait_for "$SERVICES_NAMESPACE" deploy/keycloak 10m
  wait_for "$SERVICES_NAMESPACE" deploy/ollama 15m
  wait_for "$SERVICES_NAMESPACE" job/ollama-pull-models 45m
  wait_for "$NAMESPACE" deploy/opaa-frontend 5m
  wait_for "$NAMESPACE" deploy/opaa-backend 15m
  wait_for "$NAMESPACE" job/opaa-mail-setup 10m

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
  show_context
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
