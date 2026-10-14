import { AFTER_SIGN_IN_ROUTE } from '../routes'

/**
 * Normalises a post-sign-in redirect target to a same-origin relative path; anything else
 * collapses to `fallback`. Canonicalising against the app origin is what makes this robust: it
 * resolves backslashes, protocol-relative forms (`//host`, `/\host`) and percent-encoded variants
 * (`/%2F%2Fevil.example`) that a literal `//` check would miss.
 */
export function safeRedirectPath(
  target: string | null | undefined,
  fallback = AFTER_SIGN_IN_ROUTE,
): string {
  if (!target || !target.startsWith('/')) return fallback
  let resolved: URL
  try {
    resolved = new URL(target, window.location.origin)
  } catch {
    return fallback
  }
  if (resolved.origin !== window.location.origin) return fallback
  const decodedPath = safeDecode(resolved.pathname)
  if (decodedPath === null || decodedPath.startsWith('//') || decodedPath.startsWith('/\\')) {
    return fallback
  }
  return `${resolved.pathname}${resolved.search}${resolved.hash}`
}

function safeDecode(value: string): string | null {
  try {
    return decodeURIComponent(value)
  } catch {
    return null
  }
}

/**
 * Where a sign-in returns to: the route ProtectedRoute was denied, handed on as router state, or
 * `?from=` for a link from outside the app. Both go through {@link safeRedirectPath}, so a crafted
 * link cannot turn the sign-in into a redirect off this origin.
 */
export function redirectTargetOf(state: unknown, search: string): string {
  const fromState =
    typeof state === 'object' && state !== null && 'from' in state && typeof state.from === 'string'
      ? state.from
      : null
  const fromQuery = new URLSearchParams(search).get('from')
  return safeRedirectPath(fromState ?? fromQuery, AFTER_SIGN_IN_ROUTE)
}
