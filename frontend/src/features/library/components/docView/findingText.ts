import type { ChunkAnchor } from '@/shared/api/types'

/** Where an anchor points, as the card names it: page, slide or section. */
export function anchorPlace(anchor: ChunkAnchor | null | undefined): string | null {
  if (!anchor) return null
  const page =
    anchor.pageFrom != null
      ? anchor.pageTo != null && anchor.pageTo !== anchor.pageFrom
        ? `Seiten ${anchor.pageFrom}–${anchor.pageTo}`
        : `Seite ${anchor.pageFrom}`
      : anchor.slide != null
        ? `Folie ${anchor.slide}`
        : null
  return [page, anchor.path?.at(-1)].filter(Boolean).join(' · ') || null
}

/** The quote of an anchor as the card shows it. */
export function anchorQuote(anchor: ChunkAnchor | null | undefined): string {
  return [anchor?.quoteStart, anchor?.quoteEnd]
    .filter(Boolean)
    .join(' … ')
    .replace(/[*_`#]+/g, '')
}
