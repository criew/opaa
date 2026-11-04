#!/usr/bin/env bash
# Safe defaults of the Compose templates: every tracked docker-compose*.yml (any directory) and the
# two environment templates .env.docker.example and .env.example. Fails when one of them
# - publishes a port beyond loopback: a ports: entry must bind 127.0.0.1 (or [::1]), or default a
#   bind variable of BIND_VARIABLES to 127.0.0.1; the long syntax needs host_ip 127.0.0.1, and
#   network_mode: host is refused; an environment template sets no *_BIND_ADDRESS beyond loopback,
# - switches on anonymous system administration: OPAA_AUTH_DEV_ALLOW_REMOTE=true, or a frontend
#   published through OPAA_FRONTEND_BIND_ADDRESS without handing the same address to the backend
#   as OPAA_AUTH_DEV_PUBLISHED_ADDRESS (the input of the dev profile's start refusal),
# - carries a fixed secret: a key ending in SECRET, PASSWORD, PASSWD, TOKEN, API_KEY, SECRET_KEY,
#   SECRET_ACCESS_KEY, ENCRYPTION_KEY or PRIVATE_KEY with a literal value or a ${VAR:-default}
#   default, unless SECRET_EXCEPTIONS names file, service and key with a reason.
# An exception that matches nothing any more fails as well. The rules are listed for operators in
# docs/handbuch/betrieb/docker-compose.md, "Härtung des Compose-Stapels".
# Line-based: ports: and environment: in flow style are refused rather than read.
set -euo pipefail

# Variables that may open a published port; each is documented as such in the handbook chapter.
BIND_VARIABLES=(OPAA_FRONTEND_BIND_ADDRESS OPAA_KEYCLOAK_BIND_ADDRESS)

# file|service|key|reason - fixed secrets of loopback-only development and test services. The
# service is the Compose service (or other top-level key) the key stands under, "-" in an
# environment template.
SECRET_EXCEPTIONS=(
  "docker-compose.yml|postgres|POSTGRES_PASSWORD|Database of the development stack, published on loopback only; the handbook names a password of one's own"
  ".env.docker.example|-|OPAA_DB_PASSWORD|The same database password, read by the backend in the stack"
  ".env.example|-|OPAA_DB_PASSWORD|The same database password, read by a backend started on the host"
  "docker-compose.yml|keycloak|KC_BOOTSTRAP_ADMIN_PASSWORD|Keycloak in start-dev mode for development, loopback only; hardening in the handbook"
  "docker-compose.yml|objectstore|RUSTFS_SECRET_KEY|Demo object store with the public demo corpus (profile demo), loopback only"
  "docker-compose.yml|objectstore-seed|AWS_SECRET_ACCESS_KEY|Seed job of the demo object store"
  "docker-compose.yml|upload-store|RUSTFS_SECRET_KEY|Upload store of a trial (profile upload-s3), loopback only; the handbook demands own values"
  "docker-compose.yml|upload-store-init|AWS_SECRET_ACCESS_KEY|Init job of the same upload store"
  "e2e/docker-compose.multi-instance.yml|corpus-init|AWS_SECRET_ACCESS_KEY|Upload store of the two-instance test stack, the default of docker-compose.yml"
  "e2e/docker-compose.e2e.yml|nextcloud|NEXTCLOUD_ADMIN_PASSWORD|Public test value of the E2E stack's Nextcloud, loopback only"
)

SECRET_KEY='(SECRET|PASSWORD|PASSWD|TOKEN|API_KEY|SECRET_KEY|SECRET_ACCESS_KEY|ENCRYPTION_KEY|PRIVATE_KEY)'
LOOPBACK='^(127\.0\.0\.1|::1|\[::1\]|localhost)$'

cd "$(git rev-parse --show-toplevel)"

files=()
while IFS= read -r -d '' file; do
  files+=("$file")
done < <(git ls-files -z -- 'docker-compose*.yml' '**/docker-compose*.yml' '.env.docker.example' \
  '.env.example')

errors=0
error() {
  errors=$((errors + 1))
  echo "::error file=$1,line=$2::$3"
}

declare -A used_exceptions=()
exception_for() {
  local entry
  for entry in "${SECRET_EXCEPTIONS[@]}"; do
    IFS='|' read -r e_file e_scope e_key _ <<<"$entry"
    if [[ "$e_file" == "$1" && "$e_scope" == "$2" && "$e_key" == "$3" ]]; then
      used_exceptions["$1|$2|$3"]=1
      return 0
    fi
  done
  return 1
}

# A scalar without trailing comment, surrounding blanks and quotes.
scalar() {
  local value="$1"
  [[ "$value" =~ ^(.*[^[:space:]])[[:space:]]+#.*$ ]] && value="${BASH_REMATCH[1]}"
  [[ "$value" =~ ^[[:space:]]*(.*[^[:space:]])[[:space:]]*$ ]] && value="${BASH_REMATCH[1]}"
  [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]] && value="${BASH_REMATCH[1]}"
  [[ "$value" =~ ^[[:space:]]*$ ]] && value=""
  printf '%s' "$value"
}

# The value a key ends up with by default: a literal, or the default of ${VAR:-x}/${VAR-x}; empty
# for ${VAR}, ${VAR:?msg} and no value at all.
default_value() {
  local value
  value="$(scalar "$1")"
  if [[ "$value" =~ ^\$\{[A-Za-z_][A-Za-z0-9_]*:?-([^}]*)\}$ ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  elif [[ "$value" =~ ^\$\{[^}]*\}$ ]]; then
    printf ''
  else
    printf '%s' "$value"
  fi
}

# A ports: entry in short syntax binds loopback, or a bind variable that defaults to it.
loopback_entry() {
  local entry variable
  entry="$(scalar "$1")"
  [[ "$entry" == 127.0.0.1:* || "$entry" == '[::1]:'* ]] && return 0
  for variable in "${BIND_VARIABLES[@]}"; do
    [[ "$entry" == "\${$variable:-127.0.0.1}:"* ]] && return 0
  done
  return 1
}

check_key() {
  local file="$1" line="$2" scope="$3" key="$4" value="$5" fixed
  [[ "$key" == OPAA_AUTH_DEV_ALLOW_REMOTE || "$key" =~ ${SECRET_KEY}$ || "$key" == *_BIND_ADDRESS ]] ||
    return 0
  fixed="$(default_value "$value")"
  if [[ "$key" == OPAA_AUTH_DEV_ALLOW_REMOTE && "${fixed,,}" == true ]]; then
    error "$file" "$line" "OPAA_AUTH_DEV_ALLOW_REMOTE=true lets the dev profile, where every request is a system administrator, start with a frontend beyond loopback. Remove it from the template."
  fi
  if [[ "$scope" == - && "$key" == *_BIND_ADDRESS && -n "$fixed" && ! "$fixed" =~ $LOOPBACK ]]; then
    error "$file" "$line" "$key=$fixed opens a published port beyond loopback by default. Leave it out of the template."
  fi
  if [[ "$key" =~ ${SECRET_KEY}$ && -n "$fixed" ]] && ! exception_for "$file" "$scope" "$key"; then
    error "$file" "$line" "$key carries the fixed value '$fixed'. Leave it empty or required (\${$key:?...}), or add file, service and key with a reason to SECRET_EXCEPTIONS in .github/scripts/check_compose_defaults.sh."
  fi
}

check_env_file() {
  local file="$1" number=0 line
  while IFS= read -r line || [[ -n "$line" ]]; do
    number=$((number + 1))
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    check_key "$file" "$number" - "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
  done <"$file"
}

check_compose_file() {
  local file="$1" number=0 line indent ports_indent=-1 item="" item_line=0 item_indent=-1
  local frontend_bind=0 published_address=0 top="" scope="" host_ip
  # Ends a long-syntax ports item: it must name host_ip 127.0.0.1.
  finish_item() {
    if [[ -n "$item" ]]; then
      host_ip=""
      [[ "$item" =~ host_ip:[[:space:]]*([^[:space:]]+) ]] && host_ip="$(scalar "${BASH_REMATCH[1]}")"
      [[ "$host_ip" == 127.0.0.1 || "$host_ip" == ::1 ]] ||
        error "$file" "$item_line" "A ports: entry in long syntax without host_ip: 127.0.0.1 publishes on every interface (host_ip '${host_ip}')."
    fi
    item=""
  }
  while IFS= read -r line || [[ -n "$line" ]]; do
    number=$((number + 1))
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    indent="${line%%[! ]*}"
    indent=${#indent}
    if ((ports_indent >= 0)); then
      # A list item may stand at the key's own indentation ("ports:\n- ...").
      if ((indent < ports_indent)) || { ((indent == ports_indent)) && [[ "$line" != *([[:space:]])-[[:space:]]* ]]; }; then
        finish_item
        ports_indent=-1
      elif [[ "$line" =~ ^[[:space:]]*-[[:space:]]+([a-z_]+):[[:space:]]* ]]; then
        finish_item
        item="$line"
        item_line=$number
        item_indent=$indent
        continue
      elif [[ "$line" =~ ^[[:space:]]*-[[:space:]]+(.*)$ ]]; then
        finish_item
        entry="$(scalar "${BASH_REMATCH[1]}")"
        [[ "$entry" == *OPAA_FRONTEND_BIND_ADDRESS* ]] && frontend_bind=1
        loopback_entry "$entry" ||
          error "$file" "$number" "The port $entry is published beyond loopback. Bind it to 127.0.0.1, or default a variable of BIND_VARIABLES in .github/scripts/check_compose_defaults.sh to 127.0.0.1."
        continue
      elif ((item_indent >= 0 && indent > item_indent)) && [[ -n "$item" ]]; then
        item+=$'\n'"$line"
        continue
      fi
    fi
    if ((indent == 0)) && [[ "$line" =~ ^([A-Za-z0-9_.-]+): ]]; then
      top="${BASH_REMATCH[1]}"
      scope="$top"
    elif ((indent == 2)) && [[ "$top" == services && "$line" =~ ^[[:space:]]+([A-Za-z0-9_.-]+): ]]; then
      scope="${BASH_REMATCH[1]}"
    fi
    if [[ "$line" =~ ^[[:space:]]*ports:[[:space:]]*(.*)$ ]]; then
      rest="$(scalar "${BASH_REMATCH[1]}")"
      if [[ -z "$rest" || "$rest" == '!override' ]]; then
        ports_indent=$indent
      elif [[ ! "$rest" =~ ^(!reset[[:space:]]+|!override[[:space:]]+)?\[[[:space:]]*\]$ ]]; then
        error "$file" "$number" "ports: in flow style ($rest) is not read by this check; write one entry per line."
      fi
      continue
    fi
    if [[ "$line" =~ ^[[:space:]]*environment:[[:space:]]*(.*)$ ]]; then
      rest="$(scalar "${BASH_REMATCH[1]}")"
      if [[ "$rest" =~ ^(!override[[:space:]]+)?[\{\[] && ! "$rest" =~ ^(!reset[[:space:]]+|!override[[:space:]]+)?(\{[[:space:]]*\}|\[[[:space:]]*\])$ ]]; then
        error "$file" "$number" "environment: in flow style ($rest) is not read by this check; write one entry per line."
      fi
    fi
    if [[ "$line" =~ ^[[:space:]]*network_mode:[[:space:]]*[\"\']?host[\"\']?[[:space:]]*$ ]]; then
      error "$file" "$number" "network_mode: host publishes every port of the service on every interface."
    fi
    [[ "$line" == *'OPAA_AUTH_DEV_PUBLISHED_ADDRESS: ${OPAA_FRONTEND_BIND_ADDRESS:-127.0.0.1}'* ]] &&
      published_address=1
    # A mapping entry (KEY: value) or a list entry ("KEY=value"), whose closing quote is cut off.
    if [[ "$line" =~ ^[[:space:]]*(-[[:space:]]+)?([\"\']?)([A-Z][A-Z0-9_]*)[\"\']?[[:space:]]*[:=][[:space:]]*(.*)$ ]]; then
      value="${BASH_REMATCH[4]}"
      if [[ -n "${BASH_REMATCH[2]}" && "$value" != [\"\']* ]]; then
        value="${value%"${BASH_REMATCH[2]}"}"
      fi
      check_key "$file" "$number" "$scope" "${BASH_REMATCH[3]}" "$value"
    fi
  done <"$file"
  finish_item
  if ((frontend_bind && !published_address)); then
    error "$file" 1 "The frontend is published through OPAA_FRONTEND_BIND_ADDRESS, but the backend does not get OPAA_AUTH_DEV_PUBLISHED_ADDRESS: \${OPAA_FRONTEND_BIND_ADDRESS:-127.0.0.1}; the dev profile could then not refuse to start beyond loopback."
  fi
}

shopt -s extglob
for file in "${files[@]}"; do
  case "$file" in
  .env.docker.example | .env.example) check_env_file "$file" ;;
  *) check_compose_file "$file" ;;
  esac
done

for entry in "${SECRET_EXCEPTIONS[@]}"; do
  IFS='|' read -r e_file e_scope e_key _ <<<"$entry"
  if [[ ! -v used_exceptions["$e_file|$e_scope|$e_key"] ]]; then
    error "$e_file" 1 "SECRET_EXCEPTIONS names $e_key of $e_scope in $e_file, which carries no fixed value there any more; remove the exception."
  fi
done

if ((${#files[@]} == 0)); then
  echo "::error::No Compose template found - the check would pass without checking anything."
  exit 1
fi
if ((errors > 0)); then
  echo "::error::$errors finding(s) in the Compose templates (${#files[@]} files checked)."
  exit 1
fi
echo "Compose templates: ${#files[@]} files, ports on loopback, no anonymous administration, no unlisted fixed secret."
