/**
 * Dev-mode user selection.
 *
 * In the `dev` auth mode the backend authenticates every request as one of its configured users
 * and lets the caller pick which one via a request header (see `io.opaa.auth.DevAuthFilter`).
 * The frontend exposes that through a `?devUser=<subject>` query parameter, which is remembered
 * for the rest of the browser session so navigation does not have to carry it along.
 *
 * This exists purely for local development and end-to-end tests. In `oidc` mode nothing here is
 * ever called, and the backend ignores the header.
 */
const DEV_USER_STORAGE_KEY = 'opaa.devAuth.user'

export const DEV_USER_HEADER = 'X-OPAA-Dev-User'

/**
 * Reads the dev user from the URL, falling back to the one remembered for this session. A
 * `?devUser=` parameter always wins and replaces what was remembered before.
 */
export function resolveDevUser(): string | null {
  const fromQuery = new URLSearchParams(window.location.search).get('devUser')
  if (fromQuery) {
    sessionStorage.setItem(DEV_USER_STORAGE_KEY, fromQuery)
    return fromQuery
  }
  return sessionStorage.getItem(DEV_USER_STORAGE_KEY)
}

export function getDevUser(): string | null {
  return sessionStorage.getItem(DEV_USER_STORAGE_KEY)
}

export function clearDevUser(): void {
  sessionStorage.removeItem(DEV_USER_STORAGE_KEY)
}
