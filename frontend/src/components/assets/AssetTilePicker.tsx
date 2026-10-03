import { useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import type { AssetType, CatalogEntryResponse } from '../../types/api'
import { getCatalog } from '../../services/catalogApi'
import { appendNewEntries, pagesForMore } from '../../services/catalogPaging'
import { markAssetFavorite, unmarkAssetFavorite } from '../../services/assetApi'
import AssetTile from './AssetTile'
import { tileFromCatalogEntry, type AssetTileData } from './assetTileData'
import { ASSET_TYPES } from './assetTypeRegistry'
import { assetPickKey, type AssetPick } from './assetPick'
import { type AssetFilters } from './AssetFilterChips'
import AssetFilterBar from './AssetFilterBar'

/** The loaded tiles for one filter; a different key means the shown result is outdated. */
interface Loaded {
  key: string
  entries: CatalogEntryResponse[]
  page: number
  totalPages: number
  totalElements: number
  error: string | null
}

/** The catalog entries of the chosen assets, for showing them as full tiles. */
interface ChosenDetails {
  key: string
  entries: ReadonlyMap<string, CatalogEntryResponse>
  error: string | null
}

/** A pick's tile while its catalog entry is still on its way: name, description and type. */
function tileOfPick(pick: AssetPick): AssetTileData {
  return {
    assetType: pick.assetType,
    assetId: pick.assetId,
    name: pick.name,
    description: pick.description ?? null,
  }
}

const PAGE_SIZE = 50
/** The most ids the catalog takes in one request; 50 UUIDs keep the request line under 8 KB. */
const IDS_PER_REQUEST = 50
const SEARCH_DELAY_MS = 300
const LOAD_ERROR = 'Die Auswahl konnte nicht geladen werden.'

interface AssetTilePickerProps {
  /** The types on offer; more than one shows a type filter. */
  types?: AssetType[]
  value: AssetPick[]
  onChange: (value: AssetPick[]) => void
  /** Offers a chip of this name that narrows the tiles to the chosen ones; it starts switched on. */
  chosenOnlyLabel?: string
  /** Shows the chosen tiles only, without letting the choice change. */
  readOnly?: boolean
  /** Tiles whose change is underway. */
  busyKeys?: ReadonlySet<string>
  /** Shown instead of tiles while only the chosen are shown and nothing is chosen; null: nothing. */
  noneChosenText?: string | null
  /** The line naming everything chosen; off where each choice is confirmed on its own. */
  showSummary?: boolean
  'aria-label': string
}

/**
 * The tile choice of assets for a space (ADR-0039, Entscheidung 4): the catalog's own list and
 * order - only what the person may read - with the catalog's filter row, several tiles at once.
 * A choice survives a changed filter. Narrowed to the chosen ones, a tile unchosen meanwhile stays
 * in place until the narrowing is switched on anew.
 */
export default function AssetTilePicker({
  types,
  value,
  onChange,
  chosenOnlyLabel,
  readOnly = false,
  busyKeys,
  noneChosenText,
  showSummary = true,
  'aria-label': ariaLabel,
}: AssetTilePickerProps) {
  // Keyed by content, so a caller passing a fresh array each render does not reload the list.
  const typesKey = types ? types.join(',') : ''
  const offered = useMemo(() => {
    const keys = typesKey.split(',')
    return typesKey ? ASSET_TYPES.filter((d) => keys.includes(d.type)) : ASSET_TYPES
  }, [typesKey])
  const [typeFilter, setTypeFilter] = useState<AssetType | undefined>(undefined)
  const [filters, setFilters] = useState<AssetFilters>({ favorites: false })
  const [chosenOnly, setChosenOnly] = useState(true)
  const [query, setQuery] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  // The filter a further page is loading for; a changed filter is not blocked by it.
  const [loadingMoreFor, setLoadingMoreFor] = useState<string | null>(null)
  // A failed further page keeps the tiles already shown; only the first page replaces them.
  const [moreError, setMoreError] = useState<{ key: string; message: string } | null>(null)
  const [details, setDetails] = useState<ChosenDetails | null>(null)
  // Every pick chosen since the narrowing to the chosen was switched on.
  const [seen, setSeen] = useState<AssetPick[]>(value)
  // Favorites set on a tile here; the server holds them, the tiles show them at once.
  const [favorites, setFavorites] = useState<ReadonlyMap<string, boolean>>(new Map())
  const [favoriteError, setFavoriteError] = useState<string | null>(null)
  const latestRequest = useRef(0)
  // The latest "Weitere laden"; only it may end the loading state, even when its answer is stale.
  const latestMore = useRef(0)
  // A favorite toggled since the last first page; see pagesForMore.
  const reorderedSinceLoad = useRef(false)

  const offersChosenOnly = chosenOnlyLabel !== undefined && !readOnly
  const showsChosenOnly = readOnly || (offersChosenOnly && chosenOnly)

  const seenKeys = new Set(seen.map(assetPickKey))
  const unseen = value.filter((pick) => !seenKeys.has(assetPickKey(pick)))
  if (unseen.length > 0) setSeen([...seen, ...unseen])

  useEffect(() => {
    const timer = window.setTimeout(() => setAppliedQuery(query), SEARCH_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [query])

  const filterType: AssetType | undefined =
    offered.length === 1 ? offered[0].type : offered.find((d) => d.type === typeFilter)?.type

  const filterKey = `${filterType ?? ''}|${filters.favorites}|${appliedQuery.trim()}|${typesKey}`
  const catalogKey = showsChosenOnly ? null : filterKey
  const chosenSource = readOnly ? value : seen
  const detailsKey = showsChosenOnly
    ? chosenSource
        .map((pick) => pick.assetId)
        .sort()
        .join(',')
    : null

  async function fetchPage(page: number): Promise<Omit<Loaded, 'key'>> {
    try {
      const result = await getCatalog({
        type: filterType,
        q: appliedQuery,
        favorites: filters.favorites,
        page,
        size: PAGE_SIZE,
      })
      return {
        entries: result.entries.filter((entry) => offered.some((d) => d.type === entry.assetType)),
        page: result.page,
        totalPages: result.totalPages,
        totalElements: result.totalElements,
        error: null,
      }
    } catch (err) {
      return {
        entries: [],
        page,
        totalPages: 0,
        totalElements: 0,
        error: err instanceof Error ? err.message : LOAD_ERROR,
      }
    }
  }

  // Only the answer to the latest filter lands; state is set once it arrives, never before.
  useEffect(() => {
    if (catalogKey === null) return
    const request = ++latestRequest.current
    void fetchPage(0).then((result) => {
      if (request !== latestRequest.current) return
      reorderedSinceLoad.current = false
      setLoaded({ key: catalogKey, ...result })
    })
    // fetchPage reads exactly what catalogKey names.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [catalogKey])

  // Narrowed to the chosen, their catalog entries make the same full tiles as the list. What the
  // catalog does not return, the person may not read, and it stays out.
  useEffect(() => {
    if (detailsKey === null) return
    let current = true
    async function fetchDetails(ids: string[]): Promise<Omit<ChosenDetails, 'key'>> {
      const entries = new Map<string, CatalogEntryResponse>()
      try {
        for (let from = 0; from < ids.length; from += IDS_PER_REQUEST) {
          const result = await getCatalog({
            ids: ids.slice(from, from + IDS_PER_REQUEST),
            page: 0,
            size: IDS_PER_REQUEST,
          })
          result.entries.forEach((entry) => entries.set(assetPickKey(entry), entry))
        }
        return { entries, error: null }
      } catch (err) {
        return { entries, error: err instanceof Error ? err.message : LOAD_ERROR }
      }
    }
    const ids = detailsKey ? detailsKey.split(',') : []
    void fetchDetails(ids).then((result) => {
      if (current) setDetails({ key: detailsKey, ...result })
    })
    return () => {
      current = false
    }
  }, [detailsKey])

  async function loadMore() {
    if (!loaded || catalogKey === null) return
    const request = ++latestRequest.current
    const more = ++latestMore.current
    setLoadingMoreFor(catalogKey)
    setMoreError(null)
    const { from, through } = pagesForMore(loaded.page, reorderedSinceLoad.current)
    let entries = from === 0 ? [] : loaded.entries
    let result: Omit<Loaded, 'key'> | null = null
    try {
      for (let page = from; page <= through; page++) {
        result = await fetchPage(page)
        if (request !== latestRequest.current) return
        if (result.error) break
        entries = appendNewEntries(entries, result.entries)
        if (page + 1 >= result.totalPages) break
      }
    } finally {
      if (more === latestMore.current) setLoadingMoreFor(null)
    }
    if (!result) return
    if (result.error) {
      setMoreError({ key: catalogKey, message: result.error })
      return
    }
    reorderedSinceLoad.current = false
    setLoaded({
      ...loaded,
      page: result.page,
      totalPages: result.totalPages,
      totalElements: result.totalElements,
      entries,
    })
  }

  const valueByKey = useMemo(
    () => new Map(value.map((pick) => [assetPickKey(pick), pick])),
    [value],
  )
  const chosenKeys = useMemo(() => new Set(valueByKey.keys()), [valueByKey])

  // While a new filter loads, the tiles of the previous answer stay in place, so a focused tile
  // keeps its focus; only the very first load shows the loading text.
  let sources: AssetTileData[]
  let isLoading: boolean
  let refreshing: boolean
  let error: string | null
  if (showsChosenOnly) {
    const needle = appliedQuery.trim().toLowerCase()
    const detailsReady = details?.key === detailsKey
    isLoading = !detailsReady && details === null
    refreshing = !detailsReady && !isLoading
    error = detailsReady ? (details?.error ?? null) : null
    const known = details?.entries ?? new Map<string, CatalogEntryResponse>()
    // As loaded, like the catalog's own filter: a star cleared here keeps its tile in place.
    const favoriteOf = (key: string) => known.get(key)?.favorite ?? false
    sources = isLoading
      ? []
      : chosenSource
          .map((pick) => valueByKey.get(assetPickKey(pick)) ?? pick)
          // Once the entries for exactly these picks are in, a missing one is not readable.
          .filter((pick) => !detailsReady || error !== null || known.has(assetPickKey(pick)))
          .filter((pick) => offered.some((d) => d.type === pick.assetType))
          .filter((pick) => !filterType || pick.assetType === filterType)
          .filter(
            (pick) =>
              !needle ||
              pick.name.toLowerCase().includes(needle) ||
              (pick.description ?? '').toLowerCase().includes(needle),
          )
          .filter((pick) => !filters.favorites || favoriteOf(assetPickKey(pick)))
          .sort((a, b) => a.name.localeCompare(b.name, 'de'))
          .map((pick) => {
            const entry = known.get(assetPickKey(pick))
            return entry ? tileFromCatalogEntry(entry) : tileOfPick(pick)
          })
  } else {
    const current = loaded?.key === filterKey
    isLoading = !current && (!loaded || loaded.error !== null)
    refreshing = !current && !isLoading
    error = current ? (loaded?.error ?? null) : null
    sources = isLoading ? [] : (loaded?.entries ?? []).map(tileFromCatalogEntry)
  }
  const page = loaded?.page ?? 0
  const currentPage = !showsChosenOnly && loaded?.key === filterKey
  const totalPages = currentPage ? (loaded?.totalPages ?? 0) : 0
  const totalElements = currentPage ? (loaded?.totalElements ?? 0) : 0
  const loadingMore = loadingMoreFor !== null && loadingMoreFor === catalogKey
  const narrowed = Boolean(appliedQuery.trim() || filterType || filters.favorites)

  const tiles = sources.map((source) => {
    const key = assetPickKey(source)
    return favorites.has(key) ? { ...source, favorite: favorites.get(key) } : source
  })

  function toggle(tile: AssetTileData) {
    const key = assetPickKey(tile)
    if (chosenKeys.has(key)) {
      onChange(value.filter((pick) => assetPickKey(pick) !== key))
      return
    }
    onChange([
      ...value,
      {
        assetType: tile.assetType,
        assetId: tile.assetId,
        name: tile.name,
        description: tile.description ?? null,
      },
    ])
  }

  async function setFavorite(tile: AssetTileData, favorite: boolean) {
    setFavoriteError(null)
    try {
      if (favorite) await markAssetFavorite(tile.assetType, tile.assetId)
      else await unmarkAssetFavorite(tile.assetType, tile.assetId)
      reorderedSinceLoad.current = true
      setFavorites((current) => new Map(current).set(assetPickKey(tile), favorite))
    } catch (err) {
      setFavoriteError(
        err instanceof Error ? err.message : 'Der Favorit konnte nicht gespeichert werden',
      )
    }
  }

  function toggleFilter(key: keyof AssetFilters) {
    if (key === 'selectedOnly') {
      if (!chosenOnly) setSeen(value)
      setChosenOnly(!chosenOnly)
      return
    }
    setFilters((current) => ({ ...current, [key]: !current[key] }))
  }

  const emptyText =
    isLoading || refreshing
      ? 'Wird geladen …'
      : narrowed
        ? 'Keine Treffer.'
        : showsChosenOnly
          ? noneChosenText === undefined
            ? 'Nichts ausgewählt.'
            : noneChosenText
          : 'Es gibt derzeit nichts, was Sie lesen dürfen und zuordnen könnten.'

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <AssetFilterBar
        search={{ value: query, onChange: setQuery, maxLength: 200 }}
        types={{ offered, value: filterType, onChange: setTypeFilter }}
        filters={offersChosenOnly ? { ...filters, selectedOnly: chosenOnly } : filters}
        onToggle={toggleFilter}
        selectedOnlyLabel={chosenOnlyLabel}
      />

      {favoriteError && (
        <Alert severity="error" onClose={() => setFavoriteError(null)}>
          {favoriteError}
        </Alert>
      )}

      {error ? (
        <Alert severity="error">{error}</Alert>
      ) : tiles.length === 0 ? (
        emptyText && (
          <Typography sx={{ color: 'text.secondary', fontSize: 13.5 }}>{emptyText}</Typography>
        )
      ) : (
        <Box
          role="group"
          aria-label={ariaLabel}
          aria-busy={refreshing || undefined}
          sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
            gap: 2,
          }}
        >
          {tiles.map((tile) => (
            <AssetTile
              key={assetPickKey(tile)}
              tile={tile}
              mode={{
                kind: 'select',
                selected: chosenKeys.has(assetPickKey(tile)),
                onToggle: () => toggle(tile),
                busy: busyKeys?.has(assetPickKey(tile)),
                readOnly,
              }}
              onFavoriteChange={(favorite) => setFavorite(tile, favorite)}
            />
          ))}
        </Box>
      )}

      {refreshing && tiles.length > 0 && (
        <Typography sx={{ color: 'text.secondary', fontSize: 12.5 }}>
          Wird aktualisiert …
        </Typography>
      )}

      {catalogKey !== null && moreError?.key === catalogKey && (
        <Alert
          severity="error"
          action={
            <Button
              color="inherit"
              size="small"
              disabled={loadingMore}
              onClick={() => void loadMore()}
            >
              Erneut versuchen
            </Button>
          }
        >
          Weitere Einträge konnten nicht geladen werden: {moreError.message}
        </Alert>
      )}

      {page + 1 < totalPages && !error && moreError?.key !== catalogKey && (
        <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 1 }}>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            {sources.length} von {totalElements} angezeigt
          </Typography>
          <Button
            variant="outlined"
            size="small"
            disabled={loadingMore}
            onClick={() => void loadMore()}
          >
            Weitere laden
          </Button>
        </Box>
      )}

      {showSummary && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }} aria-live="polite">
          {value.length === 0
            ? 'Nichts ausgewählt.'
            : `Ausgewählt: ${value.map((pick) => pick.name).join(', ')}`}
        </Typography>
      )}
    </Box>
  )
}
