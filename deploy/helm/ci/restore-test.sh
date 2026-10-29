#!/usr/bin/env bash
# Restore test of the OPAA chart, run by .github/workflows/helm-chart.yml after install-test.sh and
# locally from the repository root. Automates the restore drill of
# docs/handbuch/betrieb/sicherung-und-wiederherstellung.md (section "Wiederherstellungsprobe") on
# the reference setup: a source release is filled through the API, backed up with the commands of
# the handbook (kubernetes.md, "Sicherung und Wiederherstellung" and "Erprobungsdatenbank sichern"),
# changed after the backup and restored into a second, empty namespace with the same encryption
# keys and a new signing secret.
# Checks P1 to P4, P6 and P9 to P12; P5, P7 and P8 need a model endpoint and stay manual. Any
# deviation fails the run. Expects the current kubectl context to point at a kind cluster that
# already holds the images named in install-values.yaml. Writes checks, times and sizes to
# $GITHUB_STEP_SUMMARY when set, to stdout otherwise. Deletes the namespaces opaa-probe-quelle and
# opaa-probe-ziel without asking, before the run and after a successful one; a failed run keeps them.
# Needs kubectl, helm, curl, jq, openssl and GNU date, sha256sum, comm and paste.
set -euo pipefail

CI_DIR="deploy/helm/ci"
CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
QUELLE="${SOURCE_NAMESPACE:-opaa-probe-quelle}"
ZIEL="${TARGET_NAMESPACE:-opaa-probe-ziel}"
API_PORT="${API_PORT:-18080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-18081}"
# The source's first start migrates the schema, like the first start of install-test.sh.
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-15m}"
ADMIN_EMAIL="$(sed -n 's/^    email: //p' "$CI_DIR/restore-values.yaml")"
ADMIN_PASSWORD="$(sed -n 's/^  initialAdminPassword: //p' "$CI_DIR/restore-values.yaml")"
# The AES keys of this run, generated once: source and target share them, as the restore requires,
# and no file of the repository carries a value the "oidc" profile accepts. The source's signing
# secret is generated as well; the target replaces it with new_jwt below.
CREDENTIALS_KEY="$(openssl rand -base64 32)"
SETTINGS_KEY="$(openssl rand -base64 32)"
SOURCE_JWT="$(openssl rand -base64 48 | tr -d '\n')"
VALUES=(--values "$CI_DIR/install-values.yaml" --values "$CI_DIR/restore-values.yaml"
  --set-string "secrets.jwtSecret=$SOURCE_JWT"
  --set-string "secrets.credentialsEncryptionKey=$CREDENTIALS_KEY"
  --set-string "secrets.settingsEncryptionKey=$SETTINGS_KEY")
# The chat model endpoint of T6, which the checks never reach.
MODEL_BASE_URL="http://models.invalid/v1"
LATE_UPLOAD="probe-nach-der-sicherung.txt"

work_dir="$(mktemp -d)"
results="$work_dir/results.md"
: >"$results"
declare -A marks=()
stamps=()
declare -A sizes=()
forward_pids=()
token=""

# --- Reporting -------------------------------------------------------------------------------------

note_time() {
  marks[$1]="$(date -u +%s)"
  stamps+=("| Zeitpunkt $1 | $(date -u -d "@${marks[$1]}" +%T) UTC |")
  echo "$1 $(date -u -d "@${marks[$1]}" +%FT%TZ)"
}

pass() {
  echo "PASS $1"
  printf '| %s | bestanden | %s |\n' "$1" "$2" >>"$results"
}

fail() {
  echo "FAIL $1: $2" >&2
  printf '| %s | **nicht bestanden** | %s |\n' "$1" "$2" >>"$results"
  exit 1
}

# Seconds between two marks, or a dash while one of them is missing.
duration() {
  if [[ -n "${marks[$1]:-}" && -n "${marks[$2]:-}" ]]; then
    echo "$((marks[$2] - marks[$1])) s"
  else
    echo "–"
  fi
}

write_summary() {
  local outcome="bestanden"
  [[ "$1" == 0 ]] || outcome="**nicht bestanden**"
  {
    echo "## Wiederherstellungsprobe"
    echo
    echo "Ergebnis: $outcome. Commit \`${GITHUB_SHA:-lokal}\`, Backend \`${backend_version:-unbekannt}\`, $(date -u +%F)."
    echo
    echo "| Prüfung | Ergebnis | Beleg |"
    echo "|---|---|---|"
    cat "$results"
    echo
    echo "| Kennzahl | Wert |"
    echo "|---|---|"
    if ((${#stamps[@]})); then printf '%s\n' "${stamps[@]}"; fi
    echo "| RPO-Ist (\`tA − tS\`) | $(duration tS tA) |"
    echo "| Originale (\`t1 − t0\`) | $(duration t0 t1) |"
    echo "| Datenbank (\`t2 − t1\`) | $(duration t1 t2) |"
    echo "| Start (\`t3 − t2\`) | $(duration t2 t3) |"
    echo "| RTO-Ist (\`t4 − t0\`) | $(duration t0 t4) |"
    echo "| Dump (\`pg_dump -Fc\`) | ${sizes[dump]:-–} Bytes |"
    echo "| Originale | ${sizes[originals]:-–} Bytes in ${sizes[original_files]:-–} Dateien |"
    echo "| Archiv der Originale | ${sizes[tar]:-–} Bytes |"
    echo "| Dokumente der Upload-Bibliothek zum Zeitpunkt tS | ${sizes[documents]:-–} |"
  } >>"${GITHUB_STEP_SUMMARY:-/dev/stdout}"
}

cleanup() {
  local status=$?
  stop_forwards
  if [[ "$status" != 0 ]]; then
    for namespace in "$QUELLE" "$ZIEL"; do
      echo "--- backend log in $namespace"
      kubectl -n "$namespace" logs deploy/opaa-backend --tail=200 2>&1 || true
    done
  fi
  write_summary "$status" || true
  # A failed run keeps both namespaces for the cluster diagnostics of the workflow.
  if [[ "$status" == 0 && "${KEEP_NAMESPACES:-}" != 1 ]]; then
    kubectl delete namespace "$QUELLE" "$ZIEL" --ignore-not-found --wait=false || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

# --- Cluster ---------------------------------------------------------------------------------------

create_namespace() {
  kubectl create namespace "$1" --dry-run=client --output yaml | kubectl apply -f -
  kubectl label --overwrite namespace "$1" \
    pod-security.kubernetes.io/enforce=restricted pod-security.kubernetes.io/enforce-version=latest
}

# Port forwards as in the drill: the frontend for the API, the backend's management port for the
# version. A restarted backend is a new pod, so every start reconnects.
stop_forwards() {
  local pid
  for pid in "${forward_pids[@]}"; do
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  forward_pids=()
}

forward() {
  kubectl -n "$1" port-forward "$2" "$3:$4" >"$work_dir/forward-$3.log" 2>&1 &
  forward_pids+=("$!")
  for _ in $(seq 1 30); do
    if curl -s -o /dev/null "http://127.0.0.1:$3/"; then
      return 0
    fi
    sleep 1
  done
  cat "$work_dir/forward-$3.log" >&2
  return 1
}

connect() {
  stop_forwards
  forward "$1" svc/opaa-frontend "$API_PORT" 80
  forward "$1" deploy/opaa-backend "$MANAGEMENT_PORT" 8081
  for _ in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:$API_PORT/api/health" | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  echo "no UP from the backend in $1" >&2
  return 1
}

stop_backend() {
  kubectl -n "$1" scale deploy/opaa-backend --replicas=0
  kubectl -n "$1" wait --for=delete pod \
    -l app.kubernetes.io/instance=opaa,app.kubernetes.io/component=backend --timeout=2m
}

# The copy pod of the handbook, with the namespace of the drill instead of opaa and the fsGroup of
# the value set.
start_copy_pod() {
  kubectl apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: opaa-uploads-kopie
  namespace: $1
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 65532
    runAsGroup: 65532
    fsGroup: 65532
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: kopie
      image: docker.io/library/busybox:1.37
      command: ["sleep", "3600"]
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: [ALL]
      volumeMounts:
        - name: uploads
          mountPath: /uploads
  volumes:
    - name: uploads
      persistentVolumeClaim:
        claimName: opaa-uploads
EOF
  kubectl -n "$1" wait --for=condition=Ready pod/opaa-uploads-kopie --timeout=5m
}

checksum_originals() {
  kubectl -n "$1" exec opaa-uploads-kopie -- \
    sh -c 'cd /uploads && find . -type f -exec sha256sum {} \;' | sort -k2 >"$2"
}

count_rows() {
  kubectl -n "$1" exec -i opaa-postgresql-0 -- \
    sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F";" -f -' <"$work_dir/zaehlung.sql" >"$2"
}

# The versions flyway_schema_history records as applied, sorted for comm.
migrations() {
  kubectl -n "$1" exec opaa-postgresql-0 -- \
    sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT version FROM flyway_schema_history WHERE success"' |
    LC_ALL=C sort
}

# The chat model of the answer file: the E2E suite's ai-stub, under the restricted Pod Security
# Standard of the namespace.
start_ai_stub() {
  kubectl -n "$1" create configmap ai-stub --from-file=server.mjs=e2e/ai-stub/server.mjs
  kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-stub
  namespace: $1
spec:
  replicas: 1
  selector:
    matchLabels: { app: ai-stub }
  template:
    metadata:
      labels: { app: ai-stub }
    spec:
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        runAsGroup: 1000
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: ai-stub
          image: node:24-alpine
          command: ["node", "/app/server.mjs"]
          env:
            - { name: PORT, value: "8089" }
            - { name: EMBEDDING_DIMENSIONS, value: "1024" }
          ports:
            - containerPort: 8089
          readinessProbe:
            httpGet: { path: /health, port: 8089 }
            periodSeconds: 2
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          volumeMounts:
            - { name: app, mountPath: /app }
          resources:
            limits: { memory: 256Mi }
      volumes:
        - name: app
          configMap:
            name: ai-stub
---
apiVersion: v1
kind: Service
metadata:
  name: ai-stub
  namespace: $1
spec:
  selector: { app: ai-stub }
  ports:
    - port: 8089
      targetPort: 8089
EOF
  kubectl -n "$1" rollout status deployment/ai-stub --timeout=5m
}

# --- API -------------------------------------------------------------------------------------------

login() {
  jq -n --arg email "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" '{email: $email, password: $password}' |
    curl -sS --fail-with-body -X POST -H 'Content-Type: application/json' --data @- \
      "http://127.0.0.1:$API_PORT/api/v1/auth/local/login" | jq -er .accessToken
}

# Prints the body; a status outside 2xx fails the call.
api() {
  local method="$1" path="$2"
  shift 2
  curl -sS --fail-with-body -X "$method" -H "Authorization: Bearer $token" \
    "http://127.0.0.1:$API_PORT$path" "$@"
}

api_json() {
  api "$1" "$2" -H 'Content-Type: application/json' --data "$3"
}

status_of_token() {
  curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $1" \
    "http://127.0.0.1:$API_PORT/api/v1/auth/me"
}

documents() {
  api GET "/api/v1/libraries/$library_id/documents?size=100"
}

# "Verbindung testen" of T6 without a key: the backend decrypts the stored key before it calls the
# endpoint, so an undecryptable key answers differently than in the source. Status and body.
test_model() {
  jq -n --arg url "$MODEL_BASE_URL" --arg id "$model_id" \
    '{baseUrl: $url, modelIdentifier: "probe-modell", modelId: $id}' |
    curl -sS -w '\n%{http_code}' -X POST -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' --data @- "http://127.0.0.1:$API_PORT/api/v1/admin/models/test"
}

cat >"$work_dir/zaehlung.sql" <<'EOF'
SELECT format('%I.%I', n.nspname, c.relname) AS tabelle,
       (xpath('/row/n/text()',
              query_to_xml(format('SELECT count(*) AS n FROM %I.%I', n.nspname, c.relname),
                           false, true, '')))[1]::text AS zeilen
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname NOT LIKE 'pg\_toast%'
 ORDER BY 1;
EOF

# A namespace kept from an earlier local run would turn the target into a non-empty one.
kubectl delete namespace "$QUELLE" "$ZIEL" --ignore-not-found --timeout=5m

# --- 1. Source and its test data -------------------------------------------------------------------

echo "--- source: install"
create_namespace "$QUELLE"
start_ai_stub "$QUELLE"
helm install opaa "$CHART_DIR" -n "$QUELLE" "${VALUES[@]}"
kubectl -n "$QUELLE" rollout status deploy/opaa-backend --timeout="$ROLLOUT_TIMEOUT"
connect "$QUELLE"
token="$(login)"
# The session of step 1 that P10 replays against the target.
token_before_backup="$token"
info_source="$(curl -sS --fail "http://127.0.0.1:$MANAGEMENT_PORT/actuator/info" | jq -S .)"
backend_version="$(jq -r '.build.version // "unbekannt"' <<<"$info_source")"

echo "--- source: test data"
# T1: an upload library with three simple files, one original per document.
library_id="$(api_json POST /api/v1/libraries '{"name": "Probe T1", "sourceType": "UPLOAD"}' | jq -er .id)"
mkdir "$work_dir/t1"
printf 'Probe-Marker vor der Sicherung: %s\n' "$(openssl rand -hex 8)" >"$work_dir/t1/probe-vor-der-sicherung.txt"
printf '# Zweites Dokument\n\nInhalt der Wiederherstellungsprobe.\n' >"$work_dir/t1/zweites-dokument.md"
printf 'spalte;wert\neins;1\nzwei;2\n' >"$work_dir/t1/drittes-dokument.csv"
for file in "$work_dir"/t1/*; do
  api POST "/api/v1/libraries/$library_id/documents" -F "file=@$file" >/dev/null
done
sizes[documents]="$(documents | jq -er .totalElements)"
# T6: an inactive chat model with a stored key.
model_id="$(jq -n --arg url "$MODEL_BASE_URL" \
  '{displayName: "Probe T6", baseUrl: $url, modelIdentifier: "probe-modell", maxTokens: 256, apiKey: "probe-schluessel"}' |
  api POST /api/v1/admin/models -H 'Content-Type: application/json' --data @- | jq -er .id)"
model_test_source="$(test_model)"
if [[ "$(tail -n 1 <<<"$model_test_source")" != 200 ]]; then
  echo "the model test fails in the source already: $model_test_source" >&2
  exit 1
fi
# T7: a web source with stored Basic credentials; only its address, never fetched by the checks.
web_library_id="$(api_json POST /api/v1/libraries \
  '{"name": "Probe T7", "sourceType": "HTTP_DIRECTORY", "sourceUrl": "http://quelle.invalid/dokumente/", "sourceCredentials": "probe:geheim"}' |
  jq -er .id)"
if [[ "$(api GET "/api/v1/libraries/$web_library_id" | jq -r .sourceCredentialsSet)" != true ]]; then
  echo "the source does not report the credentials of T7 as stored" >&2
  exit 1
fi

# --- 2. Backup (tS) --------------------------------------------------------------------------------

echo "--- source: backup"
stop_forwards
stop_backend "$QUELLE"
note_time tS
count_rows "$QUELLE" "$work_dir/zaehlung-quelle.txt"
migrations "$QUELLE" >"$work_dir/migrationen-quelle.txt"
kubectl -n "$QUELLE" exec opaa-postgresql-0 -- \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"$work_dir/opaa-db.dump"
start_copy_pod "$QUELLE"
checksum_originals "$QUELLE" "$work_dir/originale-quelle.txt"
sizes[originals]="$(kubectl -n "$QUELLE" exec opaa-uploads-kopie -- \
  sh -c 'cd /uploads && find . -type f -exec cat {} + | wc -c' | tr -d ' ')"
sizes[original_files]="$(wc -l <"$work_dir/originale-quelle.txt")"
kubectl -n "$QUELLE" exec opaa-uploads-kopie -- tar -C /uploads -cf - . >"$work_dir/opaa-uploads.tar"
kubectl -n "$QUELLE" delete pod opaa-uploads-kopie
(cd "$work_dir" && sha256sum opaa-db.dump opaa-uploads.tar)
sizes[dump]="$(wc -c <"$work_dir/opaa-db.dump")"
sizes[tar]="$(wc -c <"$work_dir/opaa-uploads.tar")"
kubectl -n "$QUELLE" scale deploy/opaa-backend --replicas=1
kubectl -n "$QUELLE" rollout status deploy/opaa-backend --timeout="$ROLLOUT_TIMEOUT"

# --- 3. Data loss (tA) -----------------------------------------------------------------------------

echo "--- source: change after the backup"
connect "$QUELLE"
if [[ "$(status_of_token "$token_before_backup")" != 200 ]]; then
  echo "the source no longer accepts the session of step 1, so P10 would prove nothing" >&2
  exit 1
fi
printf 'Probe-Marker nach der Sicherung: %s\n' "$(openssl rand -hex 8)" >"$work_dir/$LATE_UPLOAD"
api POST "/api/v1/libraries/$library_id/documents" -F "file=@$work_dir/$LATE_UPLOAD" >/dev/null
if [[ "$(documents | jq -er .totalElements)" != "$((sizes[documents] + 1))" ]]; then
  echo "the upload after the backup is missing in the source" >&2
  exit 1
fi
# The outage: the source stops for good and frees the cluster for the target.
stop_forwards
stop_backend "$QUELLE"
note_time tA

# --- 4. Restore (t0 to t4) -------------------------------------------------------------------------

echo "--- target: restore"
note_time t0
create_namespace "$ZIEL"
new_jwt="$(openssl rand -base64 48 | tr -d '\n')"
helm install opaa "$CHART_DIR" -n "$ZIEL" "${VALUES[@]}" \
  --set backend.replicas=0 --set-string secrets.jwtSecret="$new_jwt"

start_copy_pod "$ZIEL"
kubectl -n "$ZIEL" exec -i opaa-uploads-kopie -- tar -C /uploads -xf - <"$work_dir/opaa-uploads.tar"
checksum_originals "$ZIEL" "$work_dir/originale-ziel.txt"
kubectl -n "$ZIEL" delete pod opaa-uploads-kopie
note_time t1

kubectl -n "$ZIEL" rollout status statefulset/opaa-postgresql --timeout=5m
kubectl -n "$ZIEL" wait --for=condition=Ready pod/opaa-postgresql-0 --timeout=5m
kubectl -n "$ZIEL" exec opaa-postgresql-0 -- sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "CREATE ROLE opaa_audit_owner NOLOGIN" -c "GRANT opaa_audit_owner TO \"$POSTGRES_USER\""'
restore_status=0
kubectl -n "$ZIEL" exec -i opaa-postgresql-0 -- \
  sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error' <"$work_dir/opaa-db.dump" ||
  restore_status=$?
echo "pg_restore: $restore_status"
[[ "$restore_status" == 0 ]] || fail P1 "\`pg_restore --exit-on-error\` endete mit $restore_status"
pass P1 "\`pg_restore --exit-on-error\` endete mit 0"
note_time t2

count_rows "$ZIEL" "$work_dir/zaehlung-ziel.txt"
migrations "$ZIEL" >"$work_dir/migrationen-vor-dem-start.txt"
helm upgrade opaa "$CHART_DIR" -n "$ZIEL" "${VALUES[@]}" --set-string secrets.jwtSecret="$new_jwt"
kubectl -n "$ZIEL" rollout status deploy/opaa-backend --timeout="$ROLLOUT_TIMEOUT"
note_time t3

echo "--- target: checks"
[[ -s "$work_dir/zaehlung-quelle.txt" && -s "$work_dir/zaehlung-ziel.txt" ]] ||
  fail P2 "Die Zeilenzählung von Quelle oder Ziel ist leer"
if ! diff "$work_dir/zaehlung-quelle.txt" "$work_dir/zaehlung-ziel.txt"; then
  fail P2 "Zeilenzahlen von Quelle und Ziel weichen ab (Ausgabe von diff im Log)"
fi
pass P2 "$(wc -l <"$work_dir/zaehlung-ziel.txt") Tabellen, Zeilenzahl je Tabelle gleich"

if ! diff "$work_dir/originale-quelle.txt" "$work_dir/originale-ziel.txt"; then
  fail P3 "Prüfsummen der Originale weichen ab (Ausgabe von diff im Log)"
fi
pass P3 "${sizes[original_files]} Originale bitgleich"

connect "$ZIEL"
info_target="$(curl -sS --fail "http://127.0.0.1:$MANAGEMENT_PORT/actuator/info" | jq -S .)"
for info in "$info_source" "$info_target"; do
  jq -e '.build.version' <<<"$info" >/dev/null || fail P4 "/actuator/info nennt keine Version: $(jq -c . <<<"$info")"
done
[[ "$info_target" == "$info_source" ]] || fail P4 "Version im Ziel weicht ab: $(jq -c . <<<"$info_target")"
migrations "$ZIEL" >"$work_dir/migrationen-nach-dem-start.txt"
if ! diff "$work_dir/migrationen-quelle.txt" "$work_dir/migrationen-vor-dem-start.txt" >/dev/null; then
  fail P4 "\`flyway_schema_history\` im Ziel vor dem Start weicht von der Quelle ab"
fi
# A start of the same version on its own restored database applies no migration.
if ! diff "$work_dir/migrationen-vor-dem-start.txt" "$work_dir/migrationen-nach-dem-start.txt" >/dev/null; then
  fail P4 "Der Start änderte \`flyway_schema_history\`: $(diff "$work_dir/migrationen-vor-dem-start.txt" "$work_dir/migrationen-nach-dem-start.txt" | paste -sd ' ')"
fi
count="$(wc -l <"$work_dir/migrationen-nach-dem-start.txt")"
pass P4 "Version \`$backend_version\` wie in der Quelle; \`flyway_schema_history\` mit $count Migrationen vor und nach dem Start unverändert"

token_target="$(login)"
token="$token_target"

report="$(api_json POST /api/v1/admin/upload-store/orphan-originals/report "{\"libraryId\": \"$library_id\"}")"
documents_target="$(documents)"
document_count="$(jq -er .totalElements <<<"$documents_target")"
if ! jq -e --argjson n "$document_count" \
  '.orphanCount == 0 and .withinGracePeriodCount == 0 and .referencedCount == $n and .scannedCount == $n' \
  <<<"$report" >/dev/null; then
  fail P6 "Bericht verwaister Originale: $(jq -c 'del(.orphans)' <<<"$report"), Dokumente: $document_count"
fi
[[ "$document_count" == "${sizes[documents]}" ]] ||
  fail P6 "$document_count Dokumente im Ziel, ${sizes[documents]} zum Zeitpunkt tS"
pass P6 "keine verwaisten Originale, $document_count referenzierte Originale für $document_count Dokumente"

if jq -e --arg name "$LATE_UPLOAD" 'any(.items[]; .fileName == $name)' <<<"$documents_target" >/dev/null; then
  fail P9 "Der Upload nach der Sicherung ist im Ziel vorhanden"
fi
pass P9 "Der Upload nach der Sicherung fehlt im Ziel"

old_status="$(status_of_token "$token_before_backup")"
[[ "$old_status" == 401 ]] || fail P10 "Das Ziel beantwortet die Sitzung von vor der Sicherung mit $old_status"
pass P10 "Sitzung von vor der Sicherung: 401; neue Anmeldung gelingt"

model_test_target="$(test_model)"
if [[ "$model_test_target" != "$model_test_source" ]]; then
  fail P11 "Verbindungstest von T6 im Ziel: $(paste -sd ' ' <<<"$model_test_target"), in der Quelle: $(paste -sd ' ' <<<"$model_test_source")"
fi
pass P11 "Verbindungstest von T6 antwortet wie in der Quelle: $(head -n 1 <<<"$model_test_target" | jq -r .message)"

credentials_set="$(api GET "/api/v1/libraries/$web_library_id" | jq -r .sourceCredentialsSet)"
[[ "$credentials_set" == true ]] || fail P12 "Die Zugangsdaten von T7 gelten im Ziel als nicht gesetzt"
pass P12 "Die Zugangsdaten von T7 gelten im Ziel als hinterlegt"
note_time t4
