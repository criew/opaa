FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
COPY backend/src/main/resources/openapi/opaa-api.yaml /backend/src/main/resources/openapi/opaa-api.yaml
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# .template in /etc/nginx/templates/ is processed by the base image's entrypoint with envsubst
# into /etc/nginx/conf.d/default.conf at container start (see docker-entrypoint.d/20-envsubst-on-
# templates.sh in the nginx:alpine image). NGINX_ENVSUBST_FILTER restricts substitution to
# OPAA_CSP_*/OPAA_DEMO_* variables - without it, envsubst would also try to substitute the file's
# own nginx variables ($scheme, $host, $remote_addr, ...) against any matching container
# environment variable, which is not the intent here.
COPY frontend/nginx.conf /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_FILTER=^OPAA_(CSP|DEMO)_
ENV OPAA_CSP_CONNECT_SRC_EXTRA=""
# Shows the demo/source notice in the UI (#230); intended only for demo instances - default off.
ENV OPAA_DEMO_MODE="false"
EXPOSE 80
