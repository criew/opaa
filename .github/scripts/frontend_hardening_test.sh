#!/usr/bin/env bash
# Checks the hardening of the frontend image's nginx without a backend: an nginx from the same image
# stands in as upstream and answers every request with the forwarding headers it received. Passes
# when client-supplied Forwarded/X-Forwarded-Host/-Port/-Prefix reach the upstream on none of the
# proxy paths, every response carries the Permissions-Policy, and the start refuses an invalid
# OPAA_CSP_CONNECT_SRC_EXTRA while a valid one ends up in the Content-Security-Policy, and with
# OPAA_BACKEND_RESOLVER a name reaches every container behind it, one started after nginx included.
#
#   .github/scripts/frontend_hardening_test.sh <frontend image>
set -euo pipefail

if (($# != 1)); then
  echo "Usage: $0 <frontend image>" >&2
  exit 2
fi
image="$1"
expected_policy='camera=(), geolocation=(), microphone=(self)'

suffix="$$"
network="opaa-hardening-$suffix"
echo_upstream="opaa-hardening-echo-$suffix"
frontend="opaa-hardening-frontend-$suffix"
probe="opaa-hardening-probe-$suffix"

cleanup() {
  docker rm -f "$frontend" "$echo_upstream" "$echo_upstream-a" "$echo_upstream-b" "$probe" \
    >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

# Starts the image hardened as in production with extra arguments.
start_frontend() {
  local name="$1"
  shift
  docker run -d --name "$name" --network "$network" \
    --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
    "$@" -p 127.0.0.1::8080 "$image" >/dev/null
}

docker network create "$network" >/dev/null

# The echo answers with the header values in brackets; an empty pair means the header was absent.
echo_conf='server {
    listen 8080;
    location / {
        default_type text/plain;
        return 200 "forwarded=[$http_forwarded] host=[$http_x_forwarded_host] port=[$http_x_forwarded_port] prefix=[$http_x_forwarded_prefix]\n";
    }
}'
docker run -d --name "$echo_upstream" --network "$network" --network-alias echo \
  -e ECHO_CONF="$echo_conf" --entrypoint sh "$image" \
  -c 'mkdir -p /tmp/nginx/conf.d && printf "%s\n" "$ECHO_CONF" > /tmp/nginx/conf.d/echo.conf && exec nginx -g "daemon off;"' \
  >/dev/null

start_frontend "$frontend" -e OPAA_BACKEND_UPSTREAM=echo:8080
port="$(docker port "$frontend" 8080/tcp | head -n 1 | sed 's/.*://')"
base="http://127.0.0.1:$port"

index=""
for _ in $(seq 1 30); do
  index="$(curl -fsS --max-time 5 "$base/" 2>/dev/null || true)"
  [[ "$index" == *'<div id="root">'* ]] && break
  sleep 1
done
if [[ "$index" != *'<div id="root">'* ]]; then
  docker logs --tail 40 "$frontend" >&2 2>&1 || true
  echo "FAIL: frontend did not serve index.html" >&2
  exit 1
fi

echo "--- forwarding headers"
forged=(-H 'Forwarded: for=192.0.2.1;host=evil.example;proto=https'
  -H 'X-Forwarded-Host: evil.example' -H 'X-Forwarded-Port: 8443' -H 'X-Forwarded-Prefix: /evil')
clean='forwarded=[] host=[] port=[] prefix=[]'
for request in "GET /api/v1/health" "POST /api/v1/transcriptions" "POST /mcp"; do
  method="${request% *}"
  path="${request#* }"
  received="$(curl -sS --max-time 10 -X "$method" "${forged[@]}" "$base$path" || true)"
  if [[ "$received" == "$clean" ]]; then
    echo "stripped as expected: $request"
  else
    fail "$request passed client headers to the upstream: ${received:-no answer}"
  fi
done

echo "--- Permissions-Policy"
entry="$(grep -o 'src="/assets/[^"]*\.js"' <<<"$index" | head -n 1 | sed 's/^src="//; s/"$//' || true)"
[[ -n "$entry" ]] || fail "index.html references no entry bundle under /assets/"
for path in / /index.html "$entry" /runtime-config.js /some/spa/route /api/v1/health /mcp \
  /assets/missing.js; do
  policies="$(curl -sS -o /dev/null -D - --max-time 10 "$base$path" |
    grep -i '^permissions-policy:' | tr -d '\r' || true)"
  if [[ "$policies" == "Permissions-Policy: $expected_policy" ]]; then
    echo "set once: $path"
  else
    fail "$path carries Permissions-Policy '${policies:-none}', expected exactly '$expected_policy'"
  fi
done

echo "--- OPAA_CSP_CONNECT_SRC_EXTRA"
# Starts the image with the value and reports whether nginx came up within a few seconds.
csp_start() {
  docker rm -f "$probe" >/dev/null 2>&1 || true
  start_frontend "$probe" -e OPAA_BACKEND_UPSTREAM=echo:8080 -e "OPAA_CSP_CONNECT_SRC_EXTRA=$1"
  local probe_port answer
  for _ in $(seq 1 15); do
    if [[ "$(docker container inspect --format '{{.State.Running}}' "$probe")" != true ]]; then
      return 1
    fi
    # Empty once the container has stopped; curl would otherwise fall back to port 80.
    probe_port="$(docker port "$probe" 8080/tcp 2>/dev/null | head -n 1 | sed 's/.*://')"
    if [[ -z "$probe_port" ]]; then
      sleep 1
      continue
    fi
    answer="$(curl -sS -o /dev/null -D - --max-time 5 "http://127.0.0.1:$probe_port/" 2>/dev/null || true)"
    if [[ -n "$answer" ]]; then
      csp_header="$(grep -i '^content-security-policy:' <<<"$answer" | tr -d '\r' || true)"
      return 0
    fi
    sleep 1
  done
  return 1
}
newline='
'
for value in "https://login.example.org; script-src *" "'self'" "data: blob:" \
  "https://login.example.org\"" "ftp://files.example.org" "https://login.example.org,https://x.example" \
  "https://\$host" "https://login.example.org${newline}add_header X-Evil 1" "https://a.example.org	b.example.org"; do
  if csp_start "$value"; then
    fail "start accepted OPAA_CSP_CONNECT_SRC_EXTRA='$value'"
  elif docker logs "$probe" 2>&1 | grep -q 'ERROR: OPAA_CSP_CONNECT_SRC_EXTRA must be'; then
    echo "refused as expected: '$value'"
  else
    fail "start failed for OPAA_CSP_CONNECT_SRC_EXTRA='$value' without naming the variable"
    docker logs --tail 10 "$probe" >&2 2>&1 || true
  fi
done
for value in "" "https://login.example.org" "http://localhost:8180" \
  " https://accounts.google.com  https://oauth2.googleapis.com " "login.example.org" \
  "https://*.example.org" "wss://events.example.org:8443" "https://login.example.org:*" \
  "https://login.example.org/realms/opaa/" "https:" "*"; do
  if ! csp_start "$value"; then
    fail "start refused the valid OPAA_CSP_CONNECT_SRC_EXTRA='$value'"
    docker logs --tail 10 "$probe" >&2 2>&1 || true
  elif [[ "$csp_header" != *"connect-src 'self' $value;"* ]]; then
    fail "CSP for '$value' does not carry it in connect-src: ${csp_header:-none}"
  else
    echo "accepted as expected: '$value'"
  fi
done

echo "--- OPAA_BACKEND_RESOLVER"
for value in "backend" "127.0.0.11;" "127.0.0.11 valid=1s" "127.0.0.11${newline}server evil:80"; do
  docker rm -f "$probe" >/dev/null 2>&1 || true
  start_frontend "$probe" -e OPAA_BACKEND_UPSTREAM=echo:8080 -e "OPAA_BACKEND_RESOLVER=$value"
  sleep 3
  if [[ "$(docker container inspect --format '{{.State.Running}}' "$probe")" == true ]]; then
    fail "start accepted OPAA_BACKEND_RESOLVER='$value'"
  elif docker logs "$probe" 2>&1 | grep -q 'ERROR: OPAA_BACKEND_RESOLVER must be'; then
    echo "refused as expected: '$value'"
  else
    fail "start failed for OPAA_BACKEND_RESOLVER='$value' without naming the variable"
  fi
done

# With the Docker resolver every container behind one name is a peer, one started after nginx
# included, and a removed one drops out within the re-resolution interval of 5 s.
docker rm -f "$probe" >/dev/null 2>&1 || true
start_frontend "$probe" -e OPAA_BACKEND_UPSTREAM=named:8080 -e OPAA_BACKEND_RESOLVER=127.0.0.11
named_upstream() {
  docker run -d --name "$echo_upstream-$1" --network "$network" --network-alias named \
    -e ECHO_CONF="server { listen 8080; location / { return 200 \"upstream=$1\n\"; } }" \
    --entrypoint sh "$image" \
    -c 'mkdir -p /tmp/nginx/conf.d && printf "%s\n" "$ECHO_CONF" > /tmp/nginx/conf.d/echo.conf && exec nginx -g "daemon off;"' \
    >/dev/null
}
probe_port="$(docker port "$probe" 8080/tcp | head -n 1 | sed 's/.*://')"
answers() {
  for _ in $(seq 1 12); do
    curl -fsS --max-time 10 "http://127.0.0.1:$probe_port/api/v1/health" 2>/dev/null || echo failed
  done | sort -u | tr '\n' ' '
}
named_upstream a
named_upstream b
sleep 7
seen="$(answers)"
if [[ "$seen" == "upstream=a upstream=b " ]]; then
  echo "both containers behind one name answer: $seen"
else
  fail "requests through a resolved name reached '${seen:-nothing}', expected upstream=a and upstream=b"
fi
docker rm -f "$echo_upstream-a" >/dev/null
sleep 7
seen="$(answers)"
if [[ "$seen" == "upstream=b " ]]; then
  echo "a removed container drops out without a restart of nginx: $seen"
else
  fail "after removing one container the requests reached '${seen:-nothing}', expected upstream=b only"
fi

if ((failures > 0)); then
  echo "$failures hardening check(s) failed." >&2
  exit 1
fi
echo "OK: frontend hardening holds for $image"
