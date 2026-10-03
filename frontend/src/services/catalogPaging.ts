import type { CatalogEntryResponse } from '../types/api'

function entryKey(entry: CatalogEntryResponse): string {
  return `${entry.assetType}:${entry.assetId}`
}

/** `next` after `shown`, without an entry `shown` already holds. */
export function appendNewEntries(
  shown: CatalogEntryResponse[],
  next: CatalogEntryResponse[],
): CatalogEntryResponse[] {
  const keys = new Set(shown.map(entryKey))
  return [...shown, ...next.filter((entry) => !keys.has(entryKey(entry)))]
}

/**
 * The pages "Weitere laden" fetches after page `shownPage`. The server sorts favorites first, so a
 * favorite toggled since the last load has moved there while its tile stayed put; the next page by
 * offset would repeat one entry and skip another. Then everything shown is fetched again, plus one
 * page, in the server's current order.
 */
export function pagesForMore(
  shownPage: number,
  reorderedSinceLoad: boolean,
): { from: number; through: number } {
  const next = shownPage + 1
  return reorderedSinceLoad ? { from: 0, through: next } : { from: next, through: next }
}
