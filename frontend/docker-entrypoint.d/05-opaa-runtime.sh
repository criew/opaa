#!/bin/sh
# Runs before the base image's 20-envsubst-on-templates.sh. Everything the container writes at
# start lives below /tmp, so the root filesystem can stay read-only (Kubernetes mounts an emptyDir
# there). A failing check aborts the container start instead of letting nginx run half-configured.
set -eu

ME=$(basename "$0")

# The value is spliced verbatim into proxy_pass by envsubst; anything beyond host[:port] could
# inject nginx directives. grep matches line by line, so a line break is rejected separately.
NEWLINE='
'
case "$OPAA_BACKEND_UPSTREAM" in
  *"$NEWLINE"*) VALID=false ;;
  *) if printf '%s' "$OPAA_BACKEND_UPSTREAM" \
    | grep -Eqx '([A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(:[0-9]{1,5})?'; then
    VALID=true
  else
    VALID=false
  fi ;;
esac
if [ "$VALID" != true ]; then
  echo "$ME: ERROR: OPAA_BACKEND_UPSTREAM must be host[:port], got '$OPAA_BACKEND_UPSTREAM'" >&2
  exit 1
fi

mkdir -p "$NGINX_ENVSUBST_OUTPUT_DIR"
