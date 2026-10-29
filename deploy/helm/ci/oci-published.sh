#!/usr/bin/env bash
# Tells whether a tag exists in an OCI repository, container image and Helm chart alike, for the
# release guards in publish-images.yml: prints "true" or "false". Asks the registry API itself and
# counts only a 404 carrying the registry error MANIFEST_UNKNOWN or NAME_UNKNOWN as free; any other
# answer (401, 403, 5xx, a plain 404 of a router, a refused token, a network error) fails, so an
# unreadable repository never passes as an unused version. Optional credentials: REGISTRY_USER and
# REGISTRY_PASSWORD. REGISTRY_SCHEME=http serves a local registry.
# With --digest it prints the digest of the manifest the tag points to instead, computed from the
# bytes the registry returned and checked against its Docker-Content-Digest header; an unpublished
# tag then fails like any other error.
set -euo pipefail

mode=published
if [[ "${1:-}" == --digest ]]; then
  mode=digest
  shift
fi
if (($# != 2)); then
  echo "Usage: $0 [--digest] <[oci://]registry/repository> <tag>" >&2
  exit 2
fi
ref="${1#oci://}"
tag="$2"
registry="${ref%%/*}"
repository="${ref#*/}"
# Repository names as the OCI distribution spec allows them: lower case only. A name outside it
# never reaches the registry API, whose router may answer it with a plain 404.
host_pattern='^[A-Za-z0-9.-]+(:[0-9]+)?$'
name_pattern='^[a-z0-9]+((\.|_|__|-+)[a-z0-9]+)*(/[a-z0-9]+((\.|_|__|-+)[a-z0-9]+)*)*$'
if [[ "$ref" != */* ]] || ! [[ "$registry" =~ $host_pattern ]]; then
  echo "$1 is not a reference registry/repository" >&2
  exit 2
fi
if ! [[ "$repository" =~ $name_pattern ]]; then
  echo "$repository is not a valid OCI repository name (lower case, see the OCI distribution spec)" >&2
  exit 2
fi
if ! [[ "$tag" =~ ^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$ ]]; then
  echo "$tag is not a valid OCI tag" >&2
  exit 2
fi

command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is not installed" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is not installed" >&2; exit 1; }

url="${REGISTRY_SCHEME:-https}://$registry/v2/$repository/manifests/$tag"
accept='application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json'

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# Quotes a value for a curl config file. Credentials go to curl through its config on stdin, so they
# never appear in the process list.
quote() {
  local value="${1//\\/\\\\}"
  printf '"%s"' "${value//\"/\\\"}"
}

credentials() {
  if [[ -n "${REGISTRY_USER:-}" || -n "${REGISTRY_PASSWORD:-}" ]]; then
    echo "user = $(quote "${REGISTRY_USER:-}:${REGISTRY_PASSWORD:-}")"
  fi
}

# Runs curl with the config lines on stdin and prints the HTTP status; headers and body land in
# $work_dir. A transport error (no connection, timeout, TLS) fails the script.
call() {
  local config="$1"
  shift
  local status
  if ! status="$(printf '%s\n' "$config" | curl --silent --show-error --max-time 30 --config - \
    --dump-header "$work_dir/headers" --output "$work_dir/body" --write-out '%{http_code}' "$@")"; then
    echo "Lookup of $ref:$tag failed: no answer from $registry" >&2
    exit 1
  fi
  echo "$status"
}

fail() {
  echo "Lookup of $ref:$tag failed: $1 $(head -c 500 "$work_dir/body" 2>/dev/null || true)" >&2
  exit 1
}

challenge_param() {
  sed -n "s/.*[ ,]$1=\"\([^\"]*\)\".*/\1/p" <<<"$2"
}

status="$(call "" --header "Accept: $accept" "$url")"

if [[ "$status" == 401 ]]; then
  challenge="$(grep -i '^www-authenticate:' "$work_dir/headers" | head -n 1 | tr -d '\r' || true)"
  case "$challenge" in
    *[Bb]earer*)
      realm="$(challenge_param realm "$challenge")"
      service="$(challenge_param service "$challenge")"
      scope="$(challenge_param scope "$challenge")"
      [[ -n "$realm" ]] || fail "HTTP 401 without a token realm ($challenge)"
      token_status="$(call "$(credentials)" --get --data-urlencode "service=$service" \
        --data-urlencode "scope=${scope:-repository:$repository:pull}" "$realm")"
      [[ "$token_status" == 200 ]] || fail "token request answered HTTP $token_status"
      token="$(jq -r '.token // .access_token // empty' "$work_dir/body")"
      [[ -n "$token" ]] || fail "token request returned no token"
      status="$(call "header = $(quote "Authorization: Bearer $token")" --header "Accept: $accept" "$url")"
      ;;
    *[Bb]asic*)
      [[ -n "$(credentials)" ]] || fail "HTTP 401 and no credentials given"
      status="$(call "$(credentials)" --header "Accept: $accept" "$url")"
      ;;
  esac
fi

# The digest of a manifest is the SHA-256 of its exact bytes, as the registry stores them.
print_digest() {
  local digest header
  digest="sha256:$(sha256sum "$work_dir/body" | cut -d ' ' -f 1)"
  header="$(sed -n 's/^[Dd]ocker-[Cc]ontent-[Dd]igest: *//p' "$work_dir/headers" | tr -d '\r' | tail -n 1)"
  if [[ -n "$header" && "$header" != "$digest" ]]; then
    fail "the registry names digest $header, the manifest it returned has $digest"
  fi
  echo "$digest"
}

case "$status" in
  200)
    if [[ "$mode" == digest ]]; then
      print_digest
    else
      echo true
    fi
    ;;
  404)
    if [[ "$mode" == digest ]]; then
      fail "HTTP 404, the tag is not published"
    elif jq -e '[.errors[]?.code] | any(. == "MANIFEST_UNKNOWN" or . == "NAME_UNKNOWN")' \
      "$work_dir/body" >/dev/null 2>&1; then
      echo false
    else
      fail "HTTP 404 without the registry error MANIFEST_UNKNOWN or NAME_UNKNOWN"
    fi
    ;;
  *) fail "HTTP $status" ;;
esac
