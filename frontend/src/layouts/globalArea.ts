/**
 * Route prefixes that render inside the global frame (#787, mockup 2b) - the navy space
 * column disappears there (AppShell checks this). #789 (/libraries) extends this list. Lives outside GlobalAreaLayout.tsx so that file only exports components
 * (react-refresh/only-export-components).
 */
const GLOBAL_AREA_PREFIXES = ['/admin', '/settings']

export function isGlobalAreaPath(pathname: string): boolean {
  return GLOBAL_AREA_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  )
}
