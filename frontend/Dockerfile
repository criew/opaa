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
# OPAA_CSP_* variables - without it, envsubst would also try to substitute the file's own nginx
# variables ($scheme, $host, $remote_addr, ...) against any matching container environment
# variable, which is not the intent here.
COPY frontend/nginx.conf /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_FILTER=^OPAA_CSP_
ENV OPAA_CSP_CONNECT_SRC_EXTRA=""
EXPOSE 80
