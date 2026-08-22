declare global {
  interface Window {
    /** Set by frontend/public/runtime-config.js (dev/build fallback) or, in the production
     * container, by the nginx location generated from OPAA_DEMO_MODE at container start (see
     * frontend/nginx.conf, frontend/Dockerfile). */
    __OPAA_DEMO_MODE__?: string
  }
}

/**
 * Whether the demo/source notice (#230) should be shown. Controlled by the frontend container's
 * `OPAA_DEMO_MODE` environment variable, not a build-time flag - the notice belongs only on demo
 * instances, not on every OPAA installation. Defaults to off if the flag is missing or malformed.
 */
export function isDemoModeEnabled(): boolean {
  return window.__OPAA_DEMO_MODE__ === 'true'
}
