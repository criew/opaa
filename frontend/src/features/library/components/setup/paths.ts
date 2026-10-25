/** The one path separator of every summary, list and breadcrumb. */
export const PATH_SEPARATOR = ' / '

/** A path for display: segments joined by the one separator, without leading or trailing slash. */
export function displayPath(path: string): string {
  const segments = path
    .split('/')
    .map((s) => s.trim())
    .filter(Boolean)
  // a single segment keeps a leading slash („/Projekte“) but never a trailing one („satzungen“)
  return segments.length > 1 ? segments.join(PATH_SEPARATOR) : path.trim().replace(/(.)\/+$/, '$1')
}
