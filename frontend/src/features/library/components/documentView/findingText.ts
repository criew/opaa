import type { ChunkAnchor } from '@/shared/api/types'

/** Where an anchor points, as the finding card names it: page, slide, row or section. */
export function anchorPlace(anchor: ChunkAnchor | null | undefined): string | null {
  if (!anchor) return null
  const unit =
    anchor.pageFrom != null
      ? range('Seite', 'Seiten', anchor.pageFrom, anchor.pageTo)
      : anchor.slide != null
        ? `Folie ${anchor.slide}`
        : anchor.rowFrom != null
          ? range('Zeile', 'Zeilen', anchor.rowFrom, anchor.rowTo)
          : null
  return [unit, anchor.path?.at(-1)].filter(Boolean).join(' · ') || null
}

function range(one: string, many: string, from: number, to: number | null | undefined): string {
  return to != null && to !== from ? `${many} ${from}–${to}` : `${one} ${from}`
}

/**
 * The quote of an anchor as the card shows it: start and end joined by an ellipsis, without the
 * Markdown characters of the source and, for a table, without its raw cell separators.
 */
export function anchorQuote(anchor: ChunkAnchor | null | undefined, table = false): string {
  const quote = [anchor?.quoteStart, anchor?.quoteEnd].filter(Boolean).join(' … ')
  const plain = quote
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[*_`#>]+/g, '')
    .replace(/(^|\s)[-+](?=\s)/g, '$1')
  const cells = table ? plain.replace(/\s*[;|\t]\s*/g, ' · ') : plain
  return cells.replace(/\s+/g, ' ').trim()
}
