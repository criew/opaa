/**
 * Route prefixes that render inside the global frame (#787, mockup 2b) - the navy space
 * column disappears there (AppShell checks this). Every prefix must correspond to a
 * GlobalAreaLayout route in App.tsx and vice versa; the coupling is manual. Lives outside
 * GlobalAreaLayout.tsx so that file only exports components
 * (react-refresh/only-export-components).
 */
const GLOBAL_AREA_PREFIXES = ['/admin', '/settings', '/libraries']

/**
 * Views where no space is selected yet (#809): the overview of all spaces and the create
 * wizard. Exact paths on purpose - /spaces/:spaceId and everything below stays space-scoped
 * with the navy column.
 */
const GLOBAL_AREA_EXACT_PATHS = ['/spaces', '/spaces/new']

export function isGlobalAreaPath(pathname: string): boolean {
  return (
    GLOBAL_AREA_EXACT_PATHS.includes(pathname) ||
    GLOBAL_AREA_PREFIXES.some((prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`))
  )
}
