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

const PAGE_SIZE = 50
const SEARCH_DELAY_MS = 300
const ALL_TYPES = 'all'

interface AssetTilePickerProps {
  /** The types on offer; more than one shows a type filter. */
  types?: AssetType[]
  value: AssetPick[]
  onChange: (value: AssetPick[]) => void
  /** Already associated: shown, but not choosable. */
  excludedKeys?: ReadonlySet<string>
  'aria-label': string
}

/**
 * The tile choice of assets to associate with a space (ADR-0039, Entscheidung 4): the catalog's
 * own list - only what the person may read - with type filter and search, several tiles at once.
 * A choice survives a changed filter; the line under the tiles names everything chosen.
 */
export default function AssetTilePicker({
  types,
  value,
  onChange,
  excludedKeys,
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
  const [query, setQuery] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  // A failed further page keeps the tiles already shown; only the first page replaces them.
  const [moreError, setMoreError] = useState<{ key: string; message: string } | null>(null)
  const latestRequest = useRef(0)

  useEffect(() => {
    const timer = window.setTimeout(() => setAppliedQuery(query), SEARCH_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [query])

  const filterType: AssetType | undefined =
    offered.length === 1 ? offered[0].type : offered.find((d) => d.type === typeFilter)?.type

  const filterKey = `${filterType ?? ''}|${filters.favorites}|${filters.fromMyGroups}|${appliedQuery.trim()}|${typesKey}`

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
        error: err instanceof Error ? err.message : 'Die Auswahl konnte nicht geladen werden.',
      }
    }
  }

  // Only the answer to the latest filter lands; state is set once it arrives, never before.
  useEffect(() => {
    const request = ++latestRequest.current
    void fetchPage(0).then((result) => {
      if (request === latestRequest.current) setLoaded({ key: filterKey, ...result })
    })
    // fetchPage reads exactly what filterKey names.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey])

  async function loadMore() {
    if (!loaded) return
    const request = ++latestRequest.current
    setLoadingMore(true)
    setMoreError(null)
    const result = await fetchPage(loaded.page + 1)
    setLoadingMore(false)
    if (request !== latestRequest.current) return
    if (result.error) {
      setMoreError({ key: filterKey, message: result.error })
      return
    }
    setLoaded({
      ...loaded,
      page: result.page,
      totalPages: result.totalPages,
      entries: [...loaded.entries, ...result.entries],
    })
  }

  const isLoading = loaded?.key !== filterKey
  const entries = isLoading ? [] : (loaded?.entries ?? [])
  const error = isLoading ? null : (loaded?.error ?? null)
  const page = loaded?.page ?? 0
  const totalPages = isLoading ? 0 : (loaded?.totalPages ?? 0)

  const chosenKeys = useMemo(() => new Set(value.map(assetPickKey)), [value])
  const shownKeys = entries.map(assetPickKey)

  const tiles: ChoiceTile<string>[] = entries.map((entry) => {
    const definition = assetTypeDefinition(entry.assetType)
    const Icon = definition?.Icon
    const key = assetPickKey(entry)
    return {
      value: key,
      label: entry.name,
      description: [definition?.title, entry.description].filter(Boolean).join(' – '),
      icon: Icon ? <Icon /> : null,
      disabledReason: excludedKeys?.has(key) ? 'Bereits zugeordnet' : null,
    }
  })

  function handleTiles(next: string[]) {
    const nextSet = new Set(next)
    // Choices outside the shown tiles stay; only the shown ones follow the tile group.
    const kept = value.filter((pick) => !shownKeys.includes(assetPickKey(pick)))
    const fromTiles = entries
      .filter((entry) => nextSet.has(assetPickKey(entry)))
      .map((entry) => ({ assetType: entry.assetType, assetId: entry.assetId, name: entry.name }))
    onChange([...kept, ...fromTiles])
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', alignItems: 'center' }}>
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
          value={filters}
          onToggle={(key) => setFilters((current) => ({ ...current, [key]: !current[key] }))}
        />
        <TextField
          size="small"
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Name oder Beschreibung …"
          slotProps={{ htmlInput: { 'aria-label': 'Suche', maxLength: 200 } }}
          sx={{ flex: 1, minWidth: 220 }}
        />
      </Box>

      {error ? (
        <Alert severity="error">{error}</Alert>
      ) : entries.length === 0 ? (
        <Typography sx={{ color: 'text.secondary', fontSize: 13.5 }}>
          {isLoading
            ? 'Wird geladen …'
            : appliedQuery.trim() || filterType || filters.favorites || filters.fromMyGroups
              ? 'Keine Treffer.'
              : 'Es gibt derzeit nichts, was Sie lesen dürfen und zuordnen könnten.'}
        </Typography>
      ) : (
        <ChoiceTileGroup
          multiple
          tiles={tiles}
          value={shownKeys.filter((key) => chosenKeys.has(key))}
          onChange={handleTiles}
          aria-label={ariaLabel}
        />
      )}

      {moreError?.key === filterKey && (
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

      {page + 1 < totalPages && !error && moreError?.key !== filterKey && (
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

      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }} aria-live="polite">
        {value.length === 0
          ? 'Nichts ausgewählt.'
          : `Ausgewählt: ${value.map((pick) => pick.name).join(', ')}`}
      </Typography>
    </Box>
  )
}
