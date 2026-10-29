import type { ChunkAnchor } from '@/shared/api/types'

/** Words per term: few enough to stay inside one block of the page, as a text directive must. */
const TERM_WORDS = 5

/** Length at which the backend cuts a quote, possibly inside a word (ChunkAnchors.QUOTE_LENGTH). */
const QUOTE_LENGTH = 60

/** List numbers, bullets and table separators the extracted text has but the page does not show. */
const MARKER = /^(?:(?:\d+[.)])+|[\p{P}\p{S}]+)$/u

/** Percent-encodes a text directive term: `-`, `,` and `&` are syntax inside `:~:text=`. */
function encodeTerm(words: string[]): string {
  return encodeURIComponent(words.join(' ')).replace(/-/g, '%2D')
}

/**
 * The first run of up to {@link TERM_WORDS} words of `quote` without a marker in it, read from its
 * end when `fromEnd`. A word at the cut end of a full-length quote may be a fragment and is left out.
 */
function termWords(quote: string, fromEnd: boolean): string[] {
  let words = quote.trim().split(/\s+/)
  if (quote.trim().length >= QUOTE_LENGTH) words = fromEnd ? words.slice(1) : words.slice(0, -1)
  if (fromEnd) words.reverse()
  const run: string[] = []
  for (const word of words) {
    if (MARKER.test(word)) {
      if (run.length > 0) break
      continue
    }
    run.push(word)
    if (run.length === TERM_WORDS) break
  }
  return fromEnd ? run.reverse() : run
}

/**
 * `url` with a text fragment (`#:~:text=<start>,<end>`) that lets the browser scroll to the cited
 * passage and highlight it. A browser without text fragments, or a page whose text no longer
 * matches, simply opens the page as before. An existing fragment is kept, as the spec allows.
 */
export function withTextFragment(url: string, anchor: ChunkAnchor | null | undefined): string {
  const start = anchor?.quoteStart ? termWords(anchor.quoteStart, false) : []
  if (start.length === 0) return url
  const end = anchor?.quoteEnd ? termWords(anchor.quoteEnd, true) : []
  const directive = `:~:text=${encodeTerm(start)}${end.length > 0 ? `,${encodeTerm(end)}` : ''}`
  return url.includes('#') ? `${url}${directive}` : `${url}#${directive}`
}
