FROM node:24-alpine AS build
WORKDIR /app
# corepack ships with node:22 and installs the pnpm version pinned in the
# "packageManager" field of package.json on first invocation.
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ .
COPY opaa-api/src/main/openapi/ /opaa-api/src/main/openapi/
RUN pnpm run build

# The unprivileged variant runs nginx as UID 101 on port 8080 and keeps its pid and temp files
# under /tmp - the prerequisite for Kubernetes' restricted Pod Security Standard (ADR-0042).
FROM nginxinc/nginx-unprivileged:alpine AS runtime
USER root
# Same contract as backend/Dockerfile: pull the Alpine security updates published after the base
# image was built, so the image is not frozen on the base image's build date (issue #1450).
# The generated server config moves from /etc/nginx/conf.d to /tmp/nginx/conf.d, so a read-only
# root filesystem only needs /tmp to be writable; the grep fails the build if the base image's
# include line ever changes and the sed silently misses it.
RUN apk upgrade --no-cache \
    && rm /etc/nginx/conf.d/default.conf \
    && sed -i 's#include /etc/nginx/conf.d/\*\.conf;#include /tmp/nginx/conf.d/*.conf;#' /etc/nginx/nginx.conf \
    && grep -q 'include /tmp/nginx/conf.d/\*\.conf;' /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
# .template in /etc/nginx/templates/ is processed by the base image's entrypoint with envsubst
# into $NGINX_ENVSUBST_OUTPUT_DIR at container start (see docker-entrypoint.d/20-envsubst-on-
# templates.sh in the base image). NGINX_ENVSUBST_FILTER restricts substitution to
# OPAA_CSP_*/OPAA_DEMO_*/OPAA_BACKEND_* variables - without it, envsubst would also try to
# substitute the file's own nginx variables ($scheme, $host, $remote_addr, ...) against any
# matching container environment variable, which is not the intent here.
COPY frontend/nginx.conf /etc/nginx/templates/default.conf.template
COPY --chmod=755 frontend/docker-entrypoint.d/05-opaa-runtime.sh /docker-entrypoint.d/
ENV NGINX_ENVSUBST_OUTPUT_DIR=/tmp/nginx/conf.d
ENV NGINX_ENVSUBST_FILTER=^OPAA_(CSP|DEMO|BACKEND)_
ENV OPAA_CSP_CONNECT_SRC_EXTRA=""
# Shows the demo/source notice in the UI (#230); intended only for demo instances - default off.
ENV OPAA_DEMO_MODE="false"
# Where /api/ and /mcp are forwarded to, as host[:port]; validated at start by 05-opaa-runtime.sh.
ENV OPAA_BACKEND_UPSTREAM="backend:8080"
# Numeric, so Kubernetes can verify runAsNonRoot without a runAsUser override.
USER 101
EXPOSE 8080
