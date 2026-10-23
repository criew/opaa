#!/usr/bin/env bash
# Checks the hardening of the frontend image's nginx without a backend: an nginx from the same image
# stands in as upstream and answers every request with the forwarding headers it received. Passes
# when client-supplied Forwarded/X-Forwarded-Host/-Port/-Prefix reach the upstream on none of the
# proxy paths, every response carries the Permissions-Policy, only the self-disclosure download
# may be framed (by the application itself), and the start refuses an invalid
# OPAA_CSP_CONNECT_SRC_EXTRA while a valid one ends up in the Content-Security-Policy, and with
# OPAA_BACKEND_RESOLVER a name reaches every container behind it, one started after nginx included.
# Under parallel requests one of two containers behind the name stops: every request is answered
# by the other, with its body and URI intact, within a few seconds. A slow answer, a streamed /mcp
# answer and uploads up to the size limits pass unchanged. A request other than GET and HEAD that a
# backend has received is never sent to a second one, neither when that backend closes the
# connection without an answer nor when the answer outlasts OPAA_BACKEND_READ_TIMEOUT; next to an
# address that refuses connections it is still answered.
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
stub="opaa-hardening-stub-$suffix"
workdir="$(mktemp -d)"

cleanup() {
  docker rm -f "$frontend" "$echo_upstream" "$echo_upstream-a" "$echo_upstream-b" "$probe" \
    "$stub-a" "$stub-b" "$stub-c" "$stub-d" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$workdir"
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
for request in "GET /api/v1/health" "PUT /api/v1/health" "POST /api/v1/transcriptions" "POST /mcp" \
  "DELETE /mcp" "POST /api/v1/me/self-disclosure/download"; do
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

echo "--- framing"
# Only the self-disclosure download may be framed, and only by the application itself.
framing() {
  local headers
  headers="$(curl -sS -o /dev/null -D - --max-time 10 -X "$1" "$base$2" | tr -d '\r' || true)"
  printf '%s|%s' "$(grep -i '^x-frame-options:' <<<"$headers" | sed 's/^[^:]*: //' || true)" \
    "$(grep -i '^content-security-policy:' <<<"$headers" | grep -o "frame-ancestors [^;]*" || true)"
}
for request in "GET /" "GET /api/v1/health" "GET /some/spa/route"; do
  got="$(framing "${request% *}" "${request#* }")"
  [[ "$got" == "DENY|frame-ancestors 'none'" ]] && echo "unframeable: $request" ||
    fail "$request carries framing '$got', expected DENY and frame-ancestors 'none'"
done
got="$(framing POST /api/v1/me/self-disclosure/download)"
[[ "$got" == "SAMEORIGIN|frame-ancestors 'self'" ]] && echo "same-origin frame: download" ||
  fail "the self-disclosure download carries framing '$got', expected SAMEORIGIN and 'self'"

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

echo "--- one of two containers stops under load"
# A full nginx configuration with njs: the stub answers with its name, the URI it received and the
# length of the request body; /api/v1/slow after 4 s, /mcp/stream as five events over 4 s, and
# /api/v1/drop and /mcp/drop close the connection without an answer. Every request it receives is
# logged with its X-Test-Request header.
stub_conf='load_module /etc/nginx/modules/ngx_http_js_module.so;
env STUB_NAME;
pid /tmp/stub.pid;
error_log stderr warn;
events {}
http {
    client_body_temp_path /tmp/stub-body;
    proxy_temp_path /tmp/stub-proxy;
    fastcgi_temp_path /tmp/stub-fastcgi;
    uwsgi_temp_path /tmp/stub-uwsgi;
    scgi_temp_path /tmp/stub-scgi;
    js_import stub from /tmp/stub.js;
    log_format hits "HIT $request_method $http_x_test_request";
    access_log /dev/stdout hits;
    server {
        listen 8080;
        client_max_body_size 64m;
        client_body_buffer_size 64m;
        client_body_in_single_buffer on;
        postpone_output 0;
        location ~ "^/(api/v1|mcp)/drop$" {
            return 444;
        }
        location / {
            js_content stub.handle;
        }
    }
}'
stub_js='function handle(r) {
    if (r.uri === "/api/v1/slow") {
        setTimeout(function () { r.return(200, "slow\n"); }, 4000);
        return;
    }
    if (r.uri === "/mcp/stream") {
        var n = 0;
        r.status = 200;
        r.headersOut["Content-Type"] = "text/event-stream";
        r.sendHeader();
        var tick = function () {
            r.send("data: " + n + "\n\n");
            n++;
            if (n < 5) {
                setTimeout(tick, 1000);
            } else {
                r.finish();
            }
        };
        tick();
        return;
    }
    var body = r.requestBuffer;
    r.return(200, "upstream=" + process.env.STUB_NAME + " uri=" + r.variables.request_uri
        + " len=" + (body ? body.length : 0) + "\n");
}
export default { handle };'
failover_stub() {
  docker run -d --name "$stub-$1" --network "$network" --network-alias failover \
    -e STUB_NAME="$1" -e STUB_CONF="$stub_conf" -e STUB_JS="$stub_js" --entrypoint sh "$image" \
    -c 'printf "%s\n" "$STUB_CONF" > /tmp/stub.conf && printf "%s\n" "$STUB_JS" > /tmp/stub.js && exec nginx -c /tmp/stub.conf -g "daemon off;"' \
    >/dev/null
}
failover_stub a
failover_stub b
docker rm -f "$probe" >/dev/null 2>&1 || true
start_frontend "$probe" -e OPAA_BACKEND_UPSTREAM=failover:8080 -e OPAA_BACKEND_RESOLVER=127.0.0.11
probe_port="$(docker port "$probe" 8080/tcp | head -n 1 | sed 's/.*://')"
failover_base="http://127.0.0.1:$probe_port"
seen=""
for _ in $(seq 1 20); do
  seen="$(for _ in $(seq 1 6); do
    curl -fsS --max-time 5 "$failover_base/api/v1/health" 2>/dev/null | cut -d ' ' -f 1 || true
  done | sort -u | tr '\n' ' ')"
  [[ "$seen" == "upstream=a upstream=b " ]] && break
  sleep 1
done
[[ "$seen" == "upstream=a upstream=b " ]] ||
  fail "both stub containers should answer before the stop, seen: '${seen:-nothing}'"

# Prints "<status> <seconds to first byte> <seconds in total>" and leaves the body in $1.
fetch() {
  local body="$1"
  shift
  rm -f "$body"
  curl -sS --max-time 30 -o "$body" -w '%{http_code} %{time_starttransfer} %{time_total}' "$@" \
    2>/dev/null || true
}
# Whether $1 >= $2 as decimal numbers.
at_least() {
  awk -v a="$1" -v b="$2" 'BEGIN { exit !(a >= b) }'
}

read -r code _ total <<<"$(fetch "$workdir/slow" "$failover_base/api/v1/slow")"
if [[ "$code" == 200 && "$(cat "$workdir/slow" 2>/dev/null)" == slow ]] && at_least "$total" 3.5; then
  echo "an answer after 4 s passes /api/: $total s"
else
  fail "the 4 s answer on /api/ ended with ${code:-no status} after ${total:-?} s"
fi
read -r code first total <<<"$(fetch "$workdir/stream" -N "$failover_base/mcp/stream")"
if [[ "$code" == 200 && "$(grep -c '^data: ' "$workdir/stream" 2>/dev/null)" == 5 ]] &&
  at_least 1.5 "$first" && at_least "$total" 3.5; then
  echo "a stream on /mcp arrives event by event: first byte after $first s, end after $total s"
else
  fail "the stream on /mcp ended with ${code:-no status}, first byte after ${first:-?} s, end after ${total:-?} s"
fi
head -c 41943040 /dev/zero >"$workdir/upload"
head -c 10485760 /dev/zero >"$workdir/dictation"
for upload in "/api/v1/documents upload 41943040" "/api/v1/transcriptions dictation 10485760"; do
  read -r path file size <<<"$upload"
  read -r code _ total <<<"$(fetch "$workdir/answer" -X POST --data-binary @"$workdir/$file" \
    -H 'Content-Type: application/octet-stream' "$failover_base$path")"
  if [[ "$code" == 200 && "$(cat "$workdir/answer" 2>/dev/null)" == *" uri=$path len=$size" ]]; then
    echo "an upload of $size bytes reaches the backend through $path"
  else
    fail "the upload of $size bytes through $path ended with ${code:-no status}: $(cat "$workdir/answer" 2>/dev/null)"
  fi
done

# Each worker cycles through a GET with escapes, a POST with a body on /api/ and one on /mcp, and
# logs "<status> <seconds> <body as expected?> <id> <request>" per request; the id travels in the
# X-Test-Request header, so the stubs' logs show how often each request arrived.
head -c 65536 /dev/zero | tr '\0' x >"$workdir/form"
failover_worker() {
  local log="$1" until="$2" i=0 result answer verdict request expected id
  while (($(date +%s) < until)); do
    id="$(basename "$log")-$i"
    case $((i % 3)) in
      0)
        request="GET /api/v1/a%20b?x=%2F"
        expected="uri=/api/v1/a%20b?x=%2F len=0"
        result="$(fetch "$log.body" -H "X-Test-Request: $id" "$failover_base/api/v1/a%20b?x=%2F")"
        ;;
      1)
        request="POST /api/v1/form"
        expected="uri=/api/v1/form len=65536"
        result="$(fetch "$log.body" -X POST -H "X-Test-Request: $id" --data-binary @"$workdir/form" \
          "$failover_base/api/v1/form")"
        ;;
      *)
        request="POST /mcp"
        expected="uri=/mcp len=2"
        result="$(fetch "$log.body" -X POST -H "X-Test-Request: $id" \
          -H 'Content-Type: application/json' --data-binary '{}' "$failover_base/mcp")"
        ;;
    esac
    answer="$(cat "$log.body" 2>/dev/null || true)"
    verdict=wrong-body
    [[ "$answer" == *" $expected" ]] && verdict=ok
    read -r code _ total <<<"$result"
    echo "${code:-000} ${total:-0} $verdict $id $request" >>"$log"
    i=$((i + 1))
  done
}
# The frontend's proxy_connect_timeout: a request that met the stopped container's address waits
# this long, so the slowest request shows whether the stop window was hit at all.
connect_timeout=4
max_seconds=10
until=$(($(date +%s) + 12))
for worker in $(seq 1 8); do
  failover_worker "$workdir/worker-$worker" "$until" &
done
sleep 3
# QUIT, the image's stop signal, named here: nginx finishes requests in flight before it exits.
docker stop --signal QUIT -t 10 "$stub-b" >/dev/null
wait
results="$(cat "$workdir"/worker-?)"
total_requests="$(wc -l <<<"$results" | tr -d ' ')"
bad="$(awk -v max="$max_seconds" '$1 != 200 || $3 != "ok" || $2 + 0 > max' <<<"$results")"
if [[ -z "$bad" ]]; then
  slowest="$(awk '$2 + 0 > m { m = $2 + 0 } END { print m }' <<<"$results")"
  echo "all $total_requests requests answered while one container stopped, slowest after $slowest s"
  if ! at_least "$slowest" "$((connect_timeout - 1)).5"; then
    fail "stop window not hit: no request waited for the connect timeout of $connect_timeout s"
  fi
else
  fail "$(wc -l <<<"$bad" | tr -d ' ') of $total_requests requests while one container stopped failed or took longer than $max_seconds s:
$(head -n 10 <<<"$bad")"
  docker logs --tail 10 "$probe" >&2 2>&1 || true
fi
# Each POST reached a backend exactly once: no request is sent again once a backend has it.
arrivals="$( (docker logs "$stub-a" 2>/dev/null; docker logs "$stub-b" 2>/dev/null) |
  awk '$1 == "HIT" && $2 == "POST" && $3 != "-" { print $3 }' | sort | uniq -c)"
posts="$(awk '$1 == 200 && $5 == "POST" { print $4 }' <<<"$results" | sort)"
not_once="$(awk 'NR == FNR { seen[$2] = $1; next } seen[$1] != 1 { print $1 " arrived " (seen[$1] + 0) " times" }' \
  <(echo "$arrivals") <(echo "$posts"))"
twice="$(awk '$1 > 1 { print $2 " arrived " $1 " times" }' <<<"$arrivals")"
if [[ -z "$not_once$twice" ]]; then
  echo "each of $(wc -l <<<"$posts" | tr -d ' ') answered POST requests reached a backend exactly once"
else
  fail "POST requests did not reach a backend exactly once:
$(printf '%s\n%s\n' "$not_once" "$twice" | sed '/^$/d' | head -n 10)"
fi

echo "--- a request a backend has received goes to no second one"
# Both stubs answer again; the frontend gives up on an answer after 2 s, so /api/v1/slow outlasts it.
docker start "$stub-b" >/dev/null
docker rm -f "$probe" >/dev/null 2>&1 || true
start_frontend "$probe" -e OPAA_BACKEND_UPSTREAM=failover:8080 -e OPAA_BACKEND_RESOLVER=127.0.0.11 \
  -e OPAA_BACKEND_READ_TIMEOUT=2s
probe_port="$(docker port "$probe" 8080/tcp | head -n 1 | sed 's/.*://')"
once_base="http://127.0.0.1:$probe_port"
seen=""
for _ in $(seq 1 20); do
  seen="$(for _ in $(seq 1 6); do
    curl -fsS --max-time 5 "$once_base/api/v1/health" 2>/dev/null | cut -d ' ' -f 1 || true
  done | sort -u | tr '\n' ' ')"
  [[ "$seen" == "upstream=a upstream=b " ]] && break
  sleep 1
done
[[ "$seen" == "upstream=a upstream=b " ]] ||
  fail "both stub containers should answer again, seen: '${seen:-nothing}'"
# Every method on each "<path> <expected status>"; a unique X-Test-Request id per request lets the
# stubs' logs show how often it arrived.
once_results=""
for target in "/api/v1/drop 502" "/api/v1/slow 504" "/mcp/drop 502"; do
  read -r path expected <<<"$target"
  for method in GET PUT DELETE POST PATCH; do
    id="once-$method-${path//\//-}"
    body=()
    [[ "$method" == GET ]] || body=(--data-binary '{}')
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 -X "$method" \
      -H "X-Test-Request: $id" ${body[@]+"${body[@]}"} "$once_base$path" 2>/dev/null || true)"
    once_results+="$method $path $expected ${code:-000} $id"$'\n'
  done
done
# A stub logs a request when it ends; the last slow one ends 4 s after it arrived.
sleep 4
hits="$( (docker logs "$stub-a" 2>/dev/null; docker logs "$stub-b" 2>/dev/null) |
  awk '$1 == "HIT" && $3 ~ /^once-/ { print $3 }' | sort | uniq -c)"
while read -r method path expected code id; do
  [[ -n "$method" ]] || continue
  arrived="$(awk -v id="$id" '$2 == id { print $1 }' <<<"$hits")"
  arrived="${arrived:-0}"
  if [[ "$method" == GET ]]; then
    echo "$method $path ended with $code after reaching $arrived backend(s); a GET may be sent again"
  elif [[ "$code" == "$expected" && "$arrived" == 1 ]]; then
    echo "$method $path ended with $code after reaching exactly one backend"
  else
    fail "$method $path ended with $code (expected $expected) after reaching a backend $arrived times, expected once"
  fi
done <<<"$once_results"

# Prints the status of one request to $once_base; extra arguments go to curl.
once_status() {
  curl -sS -o "$workdir/once-body" -w '%{http_code}' --max-time 30 "$@" 2>/dev/null || echo 000
}

# Failures after acceptance on both stubs, as any caller can provoke them, leave no backend out:
# the next PUT and DELETE are answered at once.
once_status -X PUT "$once_base/api/v1/drop" >/dev/null
once_status -X PUT "$once_base/api/v1/drop" >/dev/null
after_drops=""
for method in PUT DELETE PUT DELETE; do
  after_drops+="$method $(once_status -X "$method" --data-binary '{}' "$once_base/api/v1/item") "
done
if [[ "$after_drops" == "PUT 200 DELETE 200 PUT 200 DELETE 200 " ]]; then
  echo "after failures on both stubs PUT and DELETE are still answered: $after_drops"
else
  fail "after failures on both stubs PUT and DELETE ended with: $after_drops"
fi

# PUT and DELETE on /mcp arrive decoded and re-escaped, with merged slashes.
for check in "/mcp%3Bx /mcp;x" "/mcp// /mcp/" "/mcp;x=1 /mcp;x=1"; do
  read -r sent expected <<<"$check"
  code="$(once_status -X DELETE "$once_base$sent")"
  if [[ "$code" == 200 && "$(cat "$workdir/once-body")" == *" uri=$expected len=0" ]]; then
    echo "DELETE $sent reaches the backend as $expected"
  else
    fail "DELETE $sent ended with $code: $(cat "$workdir/once-body" 2>/dev/null)"
  fi
done

# A PUT or DELETE keeps the body limit of the path it came from, also without a declared length.
head -c 2097152 /dev/zero >"$workdir/two-mib"
head -c 12582912 /dev/zero >"$workdir/twelve-mib"
for check in "/mcp two-mib" "/api/v1/transcriptions twelve-mib"; do
  read -r path file <<<"$check"
  for method in PUT DELETE; do
    code="$(once_status -X "$method" -H 'Transfer-Encoding: chunked' \
      --data-binary @"$workdir/$file" "$once_base$path")"
    if [[ "$code" == 413 ]]; then
      echo "a chunked $method of $file on $path is refused with 413"
    else
      fail "a chunked $method of $file on $path ended with $code, expected 413"
    fi
  done
done

# Behind the name one stub answers and two addresses refuse every connection. Under parallel PUT
# and DELETE a restarted request must not meet the refusing ones again and again: every one is
# answered, exactly once.
docker stop "$stub-b" >/dev/null
for refusing in c d; do
  docker run -d --name "$stub-$refusing" --network "$network" --network-alias failover \
    --entrypoint sleep "$image" 600 >/dev/null
done
# Two re-resolutions, so the peer lists no longer change while the requests run. Then a few
# requests one after another: a stub that failed in the checks above sits out until a request it
# answers clears that failure, and must not sit out while the parallel requests start.
sleep 12
for _ in $(seq 1 6); do
  once_status -X PUT --data-binary '{}' "$once_base/api/v1/item" >/dev/null
done
sleep 1
refused_worker() {
  local log="$1" until="$2" i=0 method id
  while (($(date +%s) < until)); do
    method=PUT
    ((i % 2)) && method=DELETE
    id="refused-$(basename "$log")-$i"
    echo "$method $(once_status -X "$method" -H "X-Test-Request: $id" --data-binary '{}' \
      "$once_base/api/v1/item") $id" >>"$log"
    i=$((i + 1))
  done
}
until=$(($(date +%s) + 8))
for worker in $(seq 1 8); do
  refused_worker "$workdir/refused-$worker" "$until" &
done
wait
refused_results="$(cat "$workdir"/refused-?)"
hits="$( (docker logs "$stub-a" 2>/dev/null; docker logs "$stub-b" 2>/dev/null) |
  awk '$1 == "HIT" && $3 ~ /^refused-/ { print $3 }' | sort | uniq -c)"
refusals="$(docker logs "$probe" 2>&1 | grep -c 'connect() failed (111' || true)"
refused_bad="$(awk 'NR == FNR { seen[$2] = $1; next } $2 != 200 || seen[$3] != 1 { print $3 " ended with " $2 " after " (seen[$3] + 0) " arrivals" }' \
  <(echo "$hits") <(echo "$refused_results"))"
if [[ -n "$refused_bad" ]]; then
  fail "$(wc -l <<<"$refused_bad" | tr -d ' ') of $(wc -l <<<"$refused_results" | tr -d ' ') PUT and DELETE next to a refusing address were not answered exactly once:
$(head -n 10 <<<"$refused_bad")"
elif ((refusals == 0)); then
  fail "no PUT or DELETE met the refusing address"
else
  echo "$(wc -l <<<"$refused_results" | tr -d ' ') parallel PUT and DELETE next to a refusing address answered, each by one backend ($refusals refused attempts)"
fi

if ((failures > 0)); then
  echo "$failures hardening check(s) failed." >&2
  exit 1
fi
echo "OK: frontend hardening holds for $image"
