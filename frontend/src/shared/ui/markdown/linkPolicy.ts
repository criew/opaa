/**
 * The text an image of an answer is shown as instead of being loaded - in the chat, in the HTML of
 * a copy and in every copied or exported Markdown. The address follows it separately.
 */
export function blockedImageText(alt: string | null | undefined): string {
  return `[Bild: ${alt?.trim() || 'ohne Beschreibung'}]`
}

/**
 * Whether a link in an answer or a document preview may be clickable: only an http(s) target whose
 * origin is exactly the application's own or one the administration allows - no subdomain or
 * suffix match. Relative and protocol-relative links resolve against the page first. Everything
 * else, `mailto:`, `javascript:` and `data:` included, is shown as text with its full address.
 */
export function isAllowedLinkTarget(
  href: string | undefined,
  pageHref: string,
  allowedOrigins: readonly string[],
): boolean {
  if (!href) {
    return false
  }
  let url: URL
  let page: URL
  try {
    page = new URL(pageHref)
    url = new URL(href, page)
  } catch {
    return false
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    return false
  }
  return url.origin === page.origin || allowedOrigins.includes(url.origin)
}
