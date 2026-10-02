import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import type { CatalogEntryResponse, CatalogEntryStatus, CatalogVisibility } from '../types/api'
import type { CatalogSort } from '../services/catalogApi'
import { CATALOG_QUERY_MAX_LENGTH, useCatalogStore } from '../stores/catalogStore'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import {
  ASSET_TYPES,
  assetTypeDefinition,
  creatableAssetTypes,
  type AssetTypeDefinition,
} from '../components/assets/assetTypeRegistry'
import OverviewPage, { OverviewCard, OverviewCardLink } from '../components/overview/OverviewPage'
import MetaBadge from '../components/MetaBadge'
import AssetFilterChips from '../components/assets/AssetFilterChips'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import {
  assetRoleLabel,
  catalogStatusLabel,
  catalogVisibilityDescription,
  catalogVisibilityLabel,
} from '../utils/labels'
import { CATALOG_NEW_ROUTE } from '../routes'

/** Long enough to let a word be typed out before the server is asked. */
const SEARCH_DELAY_MS = 300

const ALL = 'all'

/** The address values of the filters and the sort; the API's own values stay out of the URL. */
const VISIBILITY_SLUGS: Record<CatalogVisibility, string> = {
  PUBLIC: 'public',
  RESTRICTED: 'restricted',
}
const SORT_SLUGS: Record<CatalogSort, string> = { name: 'name', updatedAt: 'updated' }

function fromSlug<K extends string>(slugs: Record<K, string>, slug: string | null): K | undefined {
  return (Object.keys(slugs) as K[]).find((key) => slugs[key] === slug)
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/**
 * The type's own state - for a knowledge library its indexing, which stays visible while the
 * succession is open; every other type has no measure of its own and is READY.
 */
function ownStatus(entry: CatalogEntryResponse): CatalogEntryStatus {
  if (entry.knowledgeLibrary) return entry.knowledgeLibrary.indexingStatus
  return entry.status === 'SUCCESSION_OPEN' ? 'READY' : entry.status
}

const STATUS_DOT: Record<CatalogEntryStatus, string> = {
  READY: 'success.main',
  UPDATING: 'info.main',
  UPDATE_FAILED: 'error.main',
  NOT_YET_AVAILABLE: 'text.disabled',
  SUCCESSION_OPEN: 'warning.main',
}

/**
 * "Stand <date>" when ready - the last successful run of a knowledge library, otherwise the last
 * change - and the state in words when not. The colour sits in the dot, never in the text, as in
 * the shared StatusLine; this compact form fits a tile and knows the "updating" tone.
 */
function CatalogStatusLine({ entry }: { entry: CatalogEntryResponse }) {
  const status = ownStatus(entry)
  // An upload library has no runs, so no lastIndexedAt; its Stand is then its last change.
  const standAt = entry.knowledgeLibrary?.lastIndexedAt ?? entry.updatedAt
  const text =
    status === 'READY'
      ? standAt
        ? `Stand ${formatDate(standAt)}`
        : ''
      : catalogStatusLabel(status)
  if (!text) return null
  return (
    <Box
      component="span"
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, fontSize: 11.5 }}
    >
      <Box
        component="span"
        aria-hidden
        sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: STATUS_DOT[status] }}
      />
      <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
        {text}
      </Typography>
    </Box>
  )
}

/**
 * Who to turn to: while the succession is open that is its addressee, otherwise the owner. A name
 * the caller may not see stays unnamed (ADR-0036, Entscheidung 9).
 */
function responsibleLabel(entry: CatalogEntryResponse): string {
  if (entry.succession) return entry.succession.addresseeLabel
  if (entry.ownerLabel) return entry.ownerLabel
  return entry.ownerType === 'GROUP' ? 'eine Gruppe' : 'eine Person'
}

function spreadLabel(spaceCount: number): string {
  if (spaceCount === 0) return 'in keinem Space'
  return spaceCount === 1 ? 'in 1 Space' : `in ${spaceCount} Spaces`
}

/** The type badge: icon and type name, in the accent of the role badges. */
function TypeBadge({ definition }: { definition: AssetTypeDefinition }) {
  const Icon = definition.Icon
  return (
    <Typography
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.5,
        alignSelf: 'flex-start',
        fontSize: 10.5,
        color: 'primary.main',
        border: 1,
        borderColor: (t) => alpha(t.palette.primary.main, 0.4),
        borderRadius: '4px',
        px: 0.75,
        py: 0.25,
        whiteSpace: 'nowrap',
      }}
    >
      <Icon aria-hidden sx={{ fontSize: 13 }} />
      {definition.title}
    </Typography>
  )
}

/**
 * The caller's own favorite mark (ADR-0039, Entscheidung 7): a toggle beside the card's link, never
 * inside it. Its name says what the next press does and thereby the state; `aria-pressed` would say
 * the state a second time, in the opposite direction. While a press is pending it is only
 * `aria-disabled`: a natively disabled button would drop the keyboard focus.
 */
function FavoriteToggle({ entry }: { entry: CatalogEntryResponse }) {
  const setFavorite = useCatalogStore((s) => s.setFavorite)
  const pending = useRef(false)
  const [busy, setBusy] = useState(false)
  const label = entry.favorite
    ? `„${entry.name}“ aus den Favoriten entfernen`
    : `„${entry.name}“ als Favorit markieren`

  async function toggle() {
    if (pending.current) return
    pending.current = true
    setBusy(true)
    await setFavorite(entry, !entry.favorite)
    pending.current = false
    setBusy(false)
  }

  return (
    <IconButton
      size="small"
      aria-label={label}
      aria-disabled={busy || undefined}
      onClick={() => void toggle()}
      sx={{ position: 'relative', zIndex: 1, m: -0.75, ml: 'auto' }}
    >
      {entry.favorite ? (
        <StarIcon sx={{ fontSize: 20, color: 'primary.main' }} />
      ) : (
        <StarBorderIcon sx={{ fontSize: 20 }} />
      )}
    </IconButton>
  )
}

function CatalogCard({
  entry,
  definition,
}: {
  entry: CatalogEntryResponse
  definition: AssetTypeDefinition
}) {
  return (
    <OverviewCard>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 0.75 }}>
        <TypeBadge definition={definition} />
        <Tooltip title={catalogVisibilityDescription(entry.visibility)} describeChild>
          <span>
            <MetaBadge>{catalogVisibilityLabel(entry.visibility)}</MetaBadge>
          </span>
        </Tooltip>
        <MetaBadge accent>{assetRoleLabel(entry.myRole)}</MetaBadge>
        <FavoriteToggle entry={entry} />
      </Box>
      <OverviewCardLink to={definition.detailRoute(entry.assetId)}>
        <Typography component="span" sx={{ fontSize: 16.5, fontWeight: 600 }}>
          {entry.name}
        </Typography>
      </OverviewCardLink>
      <Typography
        component="p"
        sx={{
          fontSize: 12.5,
          color: 'text.secondary',
          m: 0,
          flex: 1,
          display: '-webkit-box',
          WebkitLineClamp: 2,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}
      >
        {entry.description ?? ''}
      </Typography>
      <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
        {definition.extentLabel(entry.itemCount)} · {spreadLabel(entry.spaceCount)}
      </Typography>
      <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
        zuständig: {responsibleLabel(entry)}
      </Typography>
      <CatalogStatusLine entry={entry} />
      <SuccessionStateNote succession={entry.succession} variant="badge" />
    </OverviewCard>
  )
}

/** A filter group with its visible title, which is also the group's accessible name. */
function FilterGroup({
  id,
  title,
  children,
  push = false,
}: {
  id: string
  title: string
  children: (labelId: string) => ReactNode
  push?: boolean
}) {
  const labelId = `catalog-filter-${id}`
  return (
    <Box
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 1, ...(push ? { ml: 'auto' } : {}) }}
    >
      <Typography id={labelId} component="span" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
        {title}
      </Typography>
      {children(labelId)}
    </Box>
  )
}

/**
 * The one entry for every asset type (ADR-0039, Entscheidung 1): what the person may read, as
 * cards. Search, filters and sort run on the server; filters and sort stand in the address
 * (`?type=&visibility=&groups=1&favorites=1&sort=`), so `/libraries` and `/prompts` can lead here narrowed to
 * their type and a filtered view survives a reload.
 */
export default function CatalogPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const typeFilter = ASSET_TYPES.find((definition) => definition.slug === searchParams.get('type'))
  const visibility = fromSlug(VISIBILITY_SLUGS, searchParams.get('visibility'))
  const fromMyGroups = searchParams.get('groups') === '1'
  const favoritesOnly = searchParams.get('favorites') === '1'
  const sort = fromSlug(SORT_SLUGS, searchParams.get('sort')) ?? 'name'
  const [query, setQuery] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')
  const entries = useCatalogStore((s) => s.entries)
  const page = useCatalogStore((s) => s.page)
  const totalPages = useCatalogStore((s) => s.totalPages)
  const total = useCatalogStore((s) => s.totalElements)
  const loadedQuery = useCatalogStore((s) => s.loadedQuery)
  const isLoading = useCatalogStore((s) => s.isLoading)
  const error = useCatalogStore((s) => s.error)
  const load = useCatalogStore((s) => s.load)
  const loadMore = useCatalogStore((s) => s.loadMore)
  const { isMissing } = useMyCapabilities()
  const canCreate = creatableAssetTypes(isMissing).length > 0

  useEffect(() => {
    const timer = window.setTimeout(() => setAppliedQuery(query), SEARCH_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [query])

  const filterType = typeFilter?.type
  useEffect(() => {
    void load({
      type: filterType,
      q: appliedQuery,
      visibility,
      fromMyGroups,
      favorites: favoritesOnly,
      sort,
    })
  }, [load, filterType, appliedQuery, visibility, fromMyGroups, favoritesOnly, sort])

  /** Sets one address parameter; `null` removes it, so the default view has a bare address. */
  function setParam(key: string, value: string | null) {
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value === null) next.delete(key)
        else next.set(key, value)
        return next
      },
      { replace: true },
    )
  }

  return (
    <OverviewPage<CatalogEntryResponse>
      title="Katalog"
      countLabel={(count) => (count === 1 ? '1 Eintrag' : `${count} Einträge`)}
      createLabel={canCreate ? 'Neu' : undefined}
      onCreate={canCreate ? () => navigate(CATALOG_NEW_ROUTE) : undefined}
      items={entries}
      itemKey={(entry) => `${entry.assetType}:${entry.assetId}`}
      search={{
        value: query,
        onChange: setQuery,
        resultFor: loadedQuery ?? undefined,
        maxLength: CATALOG_QUERY_MAX_LENGTH,
      }}
      filters={
        <>
          <FilterGroup id="type" title="Typ">
            {(labelId) => (
              <ToggleButtonGroup
                size="small"
                exclusive
                value={typeFilter?.slug ?? ALL}
                onChange={(_event, next: string | null) =>
                  next && setParam('type', next === ALL ? null : next)
                }
                aria-labelledby={labelId}
              >
                <ToggleButton value={ALL} sx={{ px: 1.5 }}>
                  Alle
                </ToggleButton>
                {ASSET_TYPES.map((definition) => {
                  const Icon = definition.Icon
                  return (
                    <ToggleButton
                      key={definition.type}
                      value={definition.slug}
                      sx={{ px: 1.5, gap: 0.75 }}
                    >
                      <Icon aria-hidden sx={{ fontSize: 16 }} />
                      {definition.label}
                    </ToggleButton>
                  )
                })}
              </ToggleButtonGroup>
            )}
          </FilterGroup>
          <FilterGroup id="visibility" title="Sichtbarkeit">
            {(labelId) => (
              <ToggleButtonGroup
                size="small"
                exclusive
                value={visibility ? VISIBILITY_SLUGS[visibility] : ALL}
                onChange={(_event, next: string | null) =>
                  next && setParam('visibility', next === ALL ? null : next)
                }
                aria-labelledby={labelId}
              >
                <ToggleButton value={ALL} sx={{ px: 1.5 }}>
                  Alle
                </ToggleButton>
                {(Object.keys(VISIBILITY_SLUGS) as CatalogVisibility[]).map((value) => (
                  <ToggleButton key={value} value={VISIBILITY_SLUGS[value]} sx={{ px: 1.5 }}>
                    {catalogVisibilityLabel(value)}
                  </ToggleButton>
                ))}
              </ToggleButtonGroup>
            )}
          </FilterGroup>
          <AssetFilterChips
            value={{ favorites: favoritesOnly, fromMyGroups }}
            onToggle={(key) =>
              key === 'favorites'
                ? setParam('favorites', favoritesOnly ? null : '1')
                : setParam('groups', fromMyGroups ? null : '1')
            }
          />
          <FilterGroup id="sort" title="Sortierung" push>
            {(labelId) => (
              <ToggleButtonGroup
                size="small"
                exclusive
                value={SORT_SLUGS[sort]}
                onChange={(_event, next: string | null) =>
                  next && setParam('sort', next === SORT_SLUGS.name ? null : next)
                }
                aria-labelledby={labelId}
              >
                <ToggleButton value={SORT_SLUGS.name} sx={{ px: 1.5 }}>
                  Name
                </ToggleButton>
                <ToggleButton value={SORT_SLUGS.updatedAt} sx={{ px: 1.5 }}>
                  Zuletzt geändert
                </ToggleButton>
              </ToggleButtonGroup>
            )}
          </FilterGroup>
        </>
      }
      filtered={
        typeFilter !== undefined || visibility !== undefined || fromMyGroups || favoritesOnly
      }
      total={total}
      isLoading={isLoading}
      error={error}
      renderCard={(entry) => {
        const definition = assetTypeDefinition(entry.assetType)
        return definition ? <CatalogCard entry={entry} definition={definition} /> : null
      }}
      emptyState={
        <Typography sx={{ color: 'text.secondary' }}>
          Der Katalog ist noch leer. Er zeigt alles, was Sie lesen dürfen.
        </Typography>
      }
      listFooter={
        page + 1 < totalPages ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', mt: 2.5 }}>
            <Button variant="outlined" onClick={() => void loadMore()} disabled={isLoading}>
              Weitere laden
            </Button>
          </Box>
        ) : undefined
      }
      footNote="Der Katalog zeigt nur, was Sie lesen dürfen. „Für alle“: an alle Konten freigegeben; „Eingeschränkt“: nur über Freigaben an Personen oder Gruppen erreichbar."
    />
  )
}
