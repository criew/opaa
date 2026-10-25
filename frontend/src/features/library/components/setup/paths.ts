/** The one path separator of every summary, list and breadcrumb. */
export const PATH_SEPARATOR = ' / '

/** A path for display: segments joined by the one separator, without leading or trailing slash. */
export function displayPath(path: string): string {
  const segments = path
    .split('/')
    .map((s) => s.trim())
    .filter(Boolean)
  return segments.length > 0 ? segments.join(PATH_SEPARATOR) : path.trim()
}
