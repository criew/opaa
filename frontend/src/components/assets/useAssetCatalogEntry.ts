import { useCallback, useEffect, useState } from 'react'
import type { AssetType, CatalogEntryResponse } from '../../types/api'
import { getCatalog } from '../../services/catalogApi'
import { markAssetFavorite, unmarkAssetFavorite } from '../../services/assetApi'
import { notify } from '../../stores/notificationStore'

export interface AssetCatalogEntry {
  /** `undefined` while loading, `null` when the catalog does not hold the asset for the caller. */
  entry: CatalogEntryResponse | null | undefined
  /** Fetches the entry again, e.g. after an association changed its space count. */
  reload: () => void
  /** Marks or unmarks the caller's favorite; a failure is announced and leaves the mark as it was. */
  setFavorite: (favorite: boolean) => Promise<void>
}

/** The loaded entry together with the asset it belongs to. */
interface Loaded {
  assetId: string
  entry: CatalogEntryResponse | null
}

/**
 * One asset's own catalog entry - the same source for favorite mark and spread as the catalog's
 * tile. The catalog holds only what the caller may read, so an administrator without a right of
 * the formula gets `null`, and with it no star. After a change of asset the entry is `undefined`
 * until the new one has arrived; the previous asset's mark is never shown for the next.
 */
export function useAssetCatalogEntry(assetType: AssetType, assetId: string): AssetCatalogEntry {
  const [loaded, setLoaded] = useState<Loaded | undefined>(undefined)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    if (!assetId) return
    let cancelled = false
    getCatalog({ type: assetType, ids: [assetId], page: 0, size: 1 })
      .then((page) => {
        if (cancelled) return
        setLoaded({ assetId, entry: page.entries.find((e) => e.assetId === assetId) ?? null })
      })
      .catch(() => {
        if (!cancelled) setLoaded({ assetId, entry: null })
      })
    return () => {
      cancelled = true
    }
  }, [assetType, assetId, version])

  const reload = useCallback(() => setVersion((v) => v + 1), [])

  const setFavorite = useCallback(
    async (favorite: boolean) => {
      try {
        if (favorite) await markAssetFavorite(assetType, assetId)
        else await unmarkAssetFavorite(assetType, assetId)
        setLoaded((current) =>
          current?.assetId === assetId && current.entry
            ? { assetId, entry: { ...current.entry, favorite } }
            : current,
        )
      } catch (err) {
        notify(
          err instanceof Error ? err.message : 'Der Favorit konnte nicht gespeichert werden',
          'error',
        )
      }
    },
    [assetType, assetId],
  )

  const entry = loaded?.assetId === assetId ? loaded.entry : undefined
  return { entry, reload, setFavorite }
}
