#!/bin/sh
# Runs before the base image's 20-envsubst-on-templates.sh. Everything the container writes at
# start lives below /tmp, so the root filesystem can stay read-only (Kubernetes mounts an emptyDir
# there). A failing check aborts the container start instead of letting nginx run half-configured.
set -eu

ME=$(basename "$0")

# The value is spliced verbatim into proxy_pass by envsubst; anything beyond host[:port] could
# inject nginx directives. Hostnames may contain "_" (Docker container names), ports run 1-65535.
# grep matches line by line, so a line break is rejected separately.
NEWLINE='
'
case "$OPAA_BACKEND_UPSTREAM" in
  *"$NEWLINE"*) VALID=false ;;
  *) if printf '%s' "$OPAA_BACKEND_UPSTREAM" \
    | grep -Eqx '([A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(:([1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5]))?'; then
    VALID=true
  else
    VALID=false
  fi ;;
esac
if [ "$VALID" != true ]; then
  echo "$ME: ERROR: OPAA_BACKEND_UPSTREAM must be host[:port], got '$OPAA_BACKEND_UPSTREAM'" >&2
  exit 1
fi

# Spliced into proxy_read_timeout the same way. A unit is required: nginx reads a bare number as
# seconds, Spring as milliseconds, and the value sits next to backend durations such as
# OPAA_RERANK_TIMEOUT.
case "$OPAA_BACKEND_READ_TIMEOUT" in
  *"$NEWLINE"*) VALID=false ;;
  *) if printf '%s' "$OPAA_BACKEND_READ_TIMEOUT" | grep -Eqx '[1-9][0-9]{0,5}[smh]'; then
    VALID=true
  else
    VALID=false
  fi ;;
esac
if [ "$VALID" != true ]; then
  echo "$ME: ERROR: OPAA_BACKEND_READ_TIMEOUT must be a number with unit s, m or h, e.g. 600s, got '$OPAA_BACKEND_READ_TIMEOUT'" >&2
  exit 1
fi

mkdir -p "$NGINX_ENVSUBST_OUTPUT_DIR"

# nginx.conf includes /tmp/nginx/listen.d/*.conf in its server block. The IPv6 listen is only
# written where the kernel offers IPv6 - the same /proc/net/if_inet6 check the base image uses -
# because a [::] listen without it aborts nginx with "Address family not supported".
LISTEN_DIR=/tmp/nginx/listen.d
mkdir -p "$LISTEN_DIR"
if [ -f /proc/net/if_inet6 ]; then
  echo 'listen [::]:8080;' > "$LISTEN_DIR/ipv6.conf"
else
  rm -f "$LISTEN_DIR/ipv6.conf"
  echo "$ME: info: ipv6 not available, listening on IPv4 only"
fi
