import { useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import type { AssetType, CatalogEntryResponse } from '../../types/api'
import { getCatalog } from '../../services/catalogApi'
import ChoiceTileGroup, { type ChoiceTile } from '../choice/ChoiceTileGroup'
import { ASSET_TYPES, assetTypeDefinition } from './assetTypeRegistry'
import { assetPickKey, type AssetPick } from './assetPick'
import AssetFilterChips, { type AssetFilters } from './AssetFilterChips'

/** The loaded tiles for one filter; a different key means the shown result is outdated. */
interface Loaded {
  key: string
  entries: CatalogEntryResponse[]
  page: number
  totalPages: number
  error: string | null
}

/** The keys a personal filter lets through, for narrowing the chosen tiles alone. */
interface PersonalKeys {
  key: string
  keys: ReadonlySet<string> | null
  error: string | null
}

/** What a tile needs, whether it comes from the catalog or from the chosen picks. */
type TileSource = Pick<AssetPick, 'assetType' | 'assetId' | 'name' | 'description'>

const PAGE_SIZE = 50
const PERSONAL_PAGE_SIZE = 200
const SEARCH_DELAY_MS = 300
const ALL_TYPES = 'all'
const LOAD_ERROR = 'Die Auswahl konnte nicht geladen werden.'

interface AssetTilePickerProps {
  /** The types on offer; more than one shows a type filter. */
  types?: AssetType[]
  value: AssetPick[]
  onChange: (value: AssetPick[]) => void
  /** Already associated: shown, but not choosable. */
  excludedKeys?: ReadonlySet<string>
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
 * The tile choice of assets for a space (ADR-0039, Entscheidung 4): the catalog's own list - only
 * what the person may read - with search, type filter and personal filters, several tiles at once.
 * A choice survives a changed filter. Narrowed to the chosen ones, a tile unchosen meanwhile stays
 * in place until the narrowing is switched on anew.
 */
export default function AssetTilePicker({
  types,
  value,
  onChange,
  excludedKeys,
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
  const [typeFilter, setTypeFilter] = useState<string>(ALL_TYPES)
  const [filters, setFilters] = useState<AssetFilters>({ favorites: false, fromMyGroups: false })
  const [chosenOnly, setChosenOnly] = useState(true)
  const [query, setQuery] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  // A failed further page keeps the tiles already shown; only the first page replaces them.
  const [moreError, setMoreError] = useState<{ key: string; message: string } | null>(null)
  const [personal, setPersonal] = useState<PersonalKeys | null>(null)
  // Every pick chosen since the narrowing to the chosen was switched on.
  const [seen, setSeen] = useState<AssetPick[]>(value)
  const latestRequest = useRef(0)

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

  const filterKey = `${filterType ?? ''}|${filters.favorites}|${filters.fromMyGroups}|${appliedQuery.trim()}|${typesKey}`
  const catalogKey = showsChosenOnly ? null : filterKey
  const personalFiltered = filters.favorites || filters.fromMyGroups
  const personalKey =
    showsChosenOnly && personalFiltered ? `${filters.favorites}|${filters.fromMyGroups}` : null

  async function fetchPage(page: number): Promise<Omit<Loaded, 'key'>> {
    try {
      const result = await getCatalog({
        type: filterType,
        q: appliedQuery,
        favorites: filters.favorites,
        fromMyGroups: filters.fromMyGroups,
        page,
        size: PAGE_SIZE,
      })
      return {
        entries: result.entries.filter((entry) => offered.some((d) => d.type === entry.assetType)),
        page: result.page,
        totalPages: result.totalPages,
        error: null,
      }
    } catch (err) {
      return {
        entries: [],
        page,
        totalPages: 0,
        error: err instanceof Error ? err.message : LOAD_ERROR,
      }
    }
  }

  // Only the answer to the latest filter lands; state is set once it arrives, never before.
  useEffect(() => {
    if (catalogKey === null) return
    const request = ++latestRequest.current
    void fetchPage(0).then((result) => {
      if (request === latestRequest.current) setLoaded({ key: catalogKey, ...result })
    })
    // fetchPage reads exactly what catalogKey names.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [catalogKey])

  // The personal filters exist on the server only; narrowed to the chosen, their keys come from
  // the catalog in full - a person's own favorites are few.
  useEffect(() => {
    if (personalKey === null) return
    let current = true
    async function fetchKeys(): Promise<Omit<PersonalKeys, 'key'>> {
      const keys = new Set<string>()
      try {
        for (let page = 0, pages = 1; page < pages; page++) {
          const result = await getCatalog({
            favorites: filters.favorites,
            fromMyGroups: filters.fromMyGroups,
            page,
            size: PERSONAL_PAGE_SIZE,
          })
          result.entries.forEach((entry) => keys.add(assetPickKey(entry)))
          pages = result.totalPages
        }
        return { keys, error: null }
      } catch (err) {
        return { keys: null, error: err instanceof Error ? err.message : LOAD_ERROR }
      }
    }
    void fetchKeys().then((result) => {
      if (current) setPersonal({ key: personalKey, ...result })
    })
    return () => {
      current = false
    }
    // fetchKeys reads exactly what personalKey names.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [personalKey])

  async function loadMore() {
    if (!loaded || catalogKey === null) return
    const request = ++latestRequest.current
    setLoadingMore(true)
    setMoreError(null)
    const result = await fetchPage(loaded.page + 1)
    setLoadingMore(false)
    if (request !== latestRequest.current) return
    if (result.error) {
      setMoreError({ key: catalogKey, message: result.error })
      return
    }
    setLoaded({
      ...loaded,
      page: result.page,
      totalPages: result.totalPages,
      entries: [...loaded.entries, ...result.entries],
    })
  }

  const valueByKey = useMemo(
    () => new Map(value.map((pick) => [assetPickKey(pick), pick])),
    [value],
  )
  const chosenKeys = useMemo(() => new Set(valueByKey.keys()), [valueByKey])

  // While a new filter loads, the tiles of the previous answer stay in place, so a focused tile
  // keeps its focus; only the very first load shows the loading text.
  let sources: TileSource[]
  let isLoading: boolean
  let refreshing: boolean
  let error: string | null
  if (showsChosenOnly) {
    const needle = appliedQuery.trim().toLowerCase()
    const personalReady = personalKey === null || personal?.key === personalKey
    const previousKeys = personal?.error ? null : (personal?.keys ?? null)
    isLoading = !personalReady && previousKeys === null
    refreshing = !personalReady && !isLoading
    error = personalKey !== null && personalReady ? (personal?.error ?? null) : null
    const allowed = personalKey === null ? null : personalReady ? personal?.keys : previousKeys
    sources = isLoading
      ? []
      : (readOnly ? value : seen)
          .map((pick) => valueByKey.get(assetPickKey(pick)) ?? pick)
          .filter((pick) => offered.some((d) => d.type === pick.assetType))
          .filter((pick) => !filterType || pick.assetType === filterType)
          .filter(
            (pick) =>
              !needle ||
              pick.name.toLowerCase().includes(needle) ||
              (pick.description ?? '').toLowerCase().includes(needle),
          )
          .filter((pick) => !allowed || allowed.has(assetPickKey(pick)))
          .sort((a, b) => a.name.localeCompare(b.name, 'de'))
  } else {
    const current = loaded?.key === filterKey
    isLoading = !current && (!loaded || loaded.error !== null)
    refreshing = !current && !isLoading
    error = current ? (loaded?.error ?? null) : null
    sources = isLoading ? [] : (loaded?.entries ?? [])
  }
  const page = loaded?.page ?? 0
  const totalPages =
    showsChosenOnly || loaded?.key !== filterKey ? 0 : (loaded?.totalPages ?? 0)
  const shownKeys = sources.map(assetPickKey)
  const narrowed = Boolean(appliedQuery.trim() || filterType || personalFiltered)

  const tiles: ChoiceTile<string>[] = sources.map((source) => {
    const definition = assetTypeDefinition(source.assetType)
    const Icon = definition?.Icon
    const key = assetPickKey(source)
    return {
      value: key,
      label: source.name,
      description: [definition?.title, source.description].filter(Boolean).join(' – '),
      icon: Icon ? <Icon /> : null,
      disabledReason: excludedKeys?.has(key) ? 'Bereits zugeordnet' : null,
      busy: busyKeys?.has(key),
    }
  })

  function handleTiles(next: string[]) {
    const nextSet = new Set(next)
    // Choices outside the shown tiles stay; only the shown ones follow the tile group.
    const kept = value.filter((pick) => !shownKeys.includes(assetPickKey(pick)))
    const fromTiles = sources
      .filter((source) => nextSet.has(assetPickKey(source)))
      .map((source) => ({
        assetType: source.assetType,
        assetId: source.assetId,
        name: source.name,
        description: source.description ?? null,
      }))
    onChange([...kept, ...fromTiles])
  }

  function toggleFilter(key: keyof AssetFilters) {
    if (key === 'selectedOnly') {
      if (!chosenOnly) setSeen(value)
      setChosenOnly(!chosenOnly)
      return
    }
    setFilters((current) => ({ ...current, [key]: !current[key] }))
  }

  const emptyText = isLoading || refreshing
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
      <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', alignItems: 'center' }}>
        <TextField
          size="small"
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Name oder Beschreibung …"
          slotProps={{ htmlInput: { 'aria-label': 'Suche', maxLength: 200 } }}
          sx={{ flex: '1 1 220px', minWidth: 220 }}
        />
        {offered.length > 1 && (
          <ToggleButtonGroup
            size="small"
            exclusive
            value={typeFilter}
            onChange={(_event, next: string | null) => {
              if (next) setTypeFilter(next)
            }}
            aria-label="Typ"
          >
            <ToggleButton value={ALL_TYPES} sx={{ px: 1.5 }}>
              Alle
            </ToggleButton>
            {offered.map((definition) => {
              const Icon = definition.Icon
              return (
                <ToggleButton
                  key={definition.type}
                  value={definition.type}
                  sx={{ px: 1.5, gap: 0.75 }}
                >
                  <Icon aria-hidden sx={{ fontSize: 16 }} />
                  {definition.label}
                </ToggleButton>
              )
            })}
          </ToggleButtonGroup>
        )}
        <AssetFilterChips
          value={offersChosenOnly ? { ...filters, selectedOnly: chosenOnly } : filters}
          onToggle={toggleFilter}
          selectedOnlyLabel={chosenOnlyLabel}
        />
      </Box>

      {error ? (
        <Alert severity="error">{error}</Alert>
      ) : tiles.length === 0 ? (
        emptyText && (
          <Typography sx={{ color: 'text.secondary', fontSize: 13.5 }}>{emptyText}</Typography>
        )
      ) : (
        <Box aria-busy={refreshing || undefined}>
          <ChoiceTileGroup
            multiple
            tiles={tiles}
            value={shownKeys.filter((key) => chosenKeys.has(key))}
            onChange={handleTiles}
            readOnly={readOnly}
            aria-label={ariaLabel}
          />
        </Box>
      )}

      {refreshing && tiles.length > 0 && (
        <Typography sx={{ color: 'text.secondary', fontSize: 12.5 }}>Wird aktualisiert …</Typography>
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
        <Box>
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
