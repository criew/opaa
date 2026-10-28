import { catalogScopePrefixes } from '../assetTypes'

/**
 * Route prefixes that render inside the global frame (mockup 2b) - the navy space
 * column disappears there (AppShell checks this). Every prefix must correspond to a
 * GlobalAreaLayout route in App.tsx and vice versa; the catalog and the asset types' pages come
 * from the type registry, the rest is listed here. Lives outside GlobalAreaLayout.tsx so that file
 * only exports components (react-refresh/only-export-components).
 */
const OTHER_GLOBAL_AREA_PREFIXES = ['/admin', '/settings', '/revision']

export function isGlobalAreaPath(pathname: string): boolean {
  // React Router matches routes tolerant of trailing slashes ("/catalog/" renders the
  // catalog), so the comparison must be equally tolerant - otherwise a typed "/catalog/"
  // brings the space column back next to it.
  const path = pathname.replace(/\/+$/, '') || '/'
  return [...OTHER_GLOBAL_AREA_PREFIXES, ...catalogScopePrefixes()].some(
    (prefix) => path === prefix || path.startsWith(`${prefix}/`),
  )
}
