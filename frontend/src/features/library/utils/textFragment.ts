import type { ChunkAnchor } from '@/shared/api/types'

/** Percent-encodes a text directive term: `-`, `,` and `&` are syntax inside `:~:text=`. */
function term(text: string): string {
  return encodeURIComponent(text.replace(/\s+/g, ' ').trim()).replace(/-/g, '%2D')
}

/**
 * `url` with a text fragment (`#:~:text=<start>,<end>`) that lets the browser scroll to the cited
 * passage and highlight it. A browser without text fragments, or a page whose text no longer
 * matches, simply opens the page as before. An existing fragment is kept, as the spec allows.
 */
export function withTextFragment(url: string, anchor: ChunkAnchor | null | undefined): string {
  const start = anchor?.quoteStart?.trim()
  if (!start) return url
  const end = anchor?.quoteEnd?.trim()
  const directive = `:~:text=${term(start)}${end ? `,${term(end)}` : ''}`
  return url.includes('#') ? `${url}${directive}` : `${url}#${directive}`
}
