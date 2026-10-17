const ALLOWED_PROTOCOLS: ReadonlySet<string> = new Set(['http:', 'https:'])

/**
 * Returns `url` as a link target only if it is an absolute http(s) address, normalised by the
 * WHATWG URL parser - the same parser the browser applies to `href`, so leading whitespace or
 * control characters and embedded tabs/newlines cannot smuggle in another scheme. Relative,
 * unparsable and empty values, and every other scheme (`javascript:`, `data:`, `file:`, ...),
 * yield `undefined`: the caller then renders no link.
 */
export function safeExternalHref(url: string | null | undefined): string | undefined {
  if (!url) return undefined
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return undefined
  }
  return ALLOWED_PROTOCOLS.has(parsed.protocol) ? parsed.href : undefined
}
