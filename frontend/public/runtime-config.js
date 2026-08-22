// Default for `pnpm run dev`/`pnpm run build` previews, where nginx's envsubst never runs. The
// production container overwrites this file at start with the value of OPAA_DEMO_MODE (see
// frontend/nginx.conf, frontend/Dockerfile) - it is served from a location block, not this
// static file, once nginx is in front.
window.__OPAA_DEMO_MODE__ = 'false'
