import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import PersonOutlinedIcon from '@mui/icons-material/PersonOutlined'
import PublicIcon from '@mui/icons-material/Public'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import WorkspacesOutlinedIcon from '@mui/icons-material/WorkspacesOutlined'
import type { CatalogEntryResponse, CatalogEntryStatus } from '../types/api'
import { CATALOG_QUERY_MAX_LENGTH, useCatalogStore } from '../stores/catalogStore'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import {
  ASSET_TYPES,
  assetTypeDefinition,
  creatableAssetTypes,
  type AssetTypeDefinition,
} from '../components/assets/assetTypeRegistry'
import OverviewPage, { OverviewCard, OverviewCardLink } from '../components/overview/OverviewPage'
import { UseInSpaceDialog } from '../components/assets/UseInSpaceButton'
import AssetFilterBar from '../components/assets/AssetFilterBar'
import { catalogStatusLabel } from '../utils/labels'
import { CATALOG_NEW_ROUTE } from '../routes'

/** Long enough to let a word be typed out before the server is asked. */
const SEARCH_DELAY_MS = 300

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

/** Only a state that needs attention carries a dot; READY has none. */
const STATUS_DOT: Record<Exclude<CatalogEntryStatus, 'READY'>, string> = {
  UPDATING: 'info.main',
  UPDATE_FAILED: 'error.main',
  NOT_YET_AVAILABLE: 'text.disabled',
  SUCCESSION_OPEN: 'warning.main',
}

/** A state that needs attention, in words behind a coloured dot - the colour never in the text. */
function StateDotLine({ status }: { status: Exclude<CatalogEntryStatus, 'READY'> }) {
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
        {catalogStatusLabel(status)}
      </Typography>
    </Box>
  )
}

/**
 * "Aktualisiert am <date>" when ready - the last successful run of a knowledge library, otherwise
 * the last change - and the state behind a dot when not. An open succession is a line of its own;
 * its addressee is already the responsible party.
 */
function CatalogStatusLine({ entry }: { entry: CatalogEntryResponse }) {
  const status = ownStatus(entry)
  if (status === 'READY') {
    // An upload library has no runs, so no lastIndexedAt; its date is then its last change.
    const updatedAt = entry.knowledgeLibrary?.lastIndexedAt ?? entry.updatedAt
    if (!updatedAt) return null
    return (
      <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
        Aktualisiert am {formatDate(updatedAt)}
      </Typography>
    )
  }
  return <StateDotLine status={status} />
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

/** The responsible party behind a person or group symbol; a succession addresses a group. */
function ResponsibleLine({ entry }: { entry: CatalogEntryResponse }) {
  const group = entry.succession !== null && entry.succession !== undefined
  const Icon = group || entry.ownerType === 'GROUP' ? GroupsOutlinedIcon : PersonOutlinedIcon
  return (
    <Box
      component="span"
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, minWidth: 0 }}
    >
      <Icon
        titleAccess={
          group || entry.ownerType === 'GROUP' ? 'Zuständige Gruppe' : 'Zuständige Person'
        }
        sx={{ fontSize: 15, color: 'text.secondary' }}
      />
      <Typography component="span" noWrap sx={{ fontSize: 11.5, color: 'text.secondary' }}>
        {responsibleLabel(entry)}
      </Typography>
    </Box>
  )
}

/** Only an asset released to all accounts carries the globe; a restricted one carries nothing. */
function PublicMark() {
  const label = 'Für alle Konten freigegeben'
  return (
    <Tooltip title={label}>
      <PublicIcon
        titleAccess={label}
        aria-label={label}
        sx={{ fontSize: 16, color: 'text.secondary' }}
      />
    </Tooltip>
  )
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

/**
 * "⋯": the card's further actions in a menu beside the star - like the star its own tab stop above
 * the card's stretched link, never inside it.
 */
function MoreActions({ entry }: { entry: CatalogEntryResponse }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const [useInSpace, setUseInSpace] = useState(false)
  const buttonId = `catalog-actions-${entry.assetType}-${entry.assetId}`
  const menuId = `${buttonId}-menu`
  return (
    <>
      <IconButton
        id={buttonId}
        size="small"
        aria-label={`Weitere Aktionen für ‚${entry.name}‘`}
        aria-haspopup="menu"
        aria-controls={anchor ? menuId : undefined}
        aria-expanded={anchor ? true : undefined}
        onClick={(event) => setAnchor(event.currentTarget)}
        sx={{ position: 'relative', zIndex: 1, m: -0.75, ml: 0.25 }}
      >
        <MoreHorizIcon sx={{ fontSize: 20 }} />
      </IconButton>
      <Menu
        id={menuId}
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ list: { 'aria-labelledby': buttonId } }}
      >
        <MenuItem
          onClick={() => {
            setAnchor(null)
            setUseInSpace(true)
          }}
        >
          <WorkspacesOutlinedIcon aria-hidden sx={{ fontSize: 18, mr: 1.5 }} />
          In Space verwenden
        </MenuItem>
      </Menu>
      <UseInSpaceDialog
        assetType={entry.assetType}
        assetId={entry.assetId}
        name={entry.name}
        open={useInSpace}
        onClose={() => setUseInSpace(false)}
      />
    </>
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
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <TypeBadge definition={definition} />
        {entry.visibility === 'PUBLIC' && <PublicMark />}
        <FavoriteToggle entry={entry} />
        <MoreActions entry={entry} />
      </Box>
      <OverviewCardLink to={definition.detailRoute(entry.assetId)}>
        <Typography component="span" sx={{ fontSize: 16.5, fontWeight: 600 }}>
          {entry.name}
        </Typography>
      </OverviewCardLink>
      {entry.description && (
        <Typography
          component="p"
          sx={{
            fontSize: 12.5,
            color: 'text.secondary',
            m: 0,
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
          }}
        >
          {entry.description}
        </Typography>
      )}
      <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary', mt: 'auto' }}>
        {definition.extentLabel(entry.itemCount)} · {spreadLabel(entry.spaceCount)}
      </Typography>
      <ResponsibleLine entry={entry} />
      <CatalogStatusLine entry={entry} />
      {entry.succession && <StateDotLine status="SUCCESSION_OPEN" />}
    </OverviewCard>
  )
}

/**
 * The one entry for every asset type (ADR-0039, Entscheidung 1): what the person may read, as
 * cards, in the fixed order favorites first, then by name. Search and filters run on the server;
 * the filters stand in the address (`?type=&favorites=1`), so `/libraries` and `/prompts` can lead
 * here narrowed to their type and a filtered view survives a reload.
 */
export default function CatalogPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const typeFilter = ASSET_TYPES.find((definition) => definition.slug === searchParams.get('type'))
  const favoritesOnly = searchParams.get('favorites') === '1'
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
    void load({ type: filterType, q: appliedQuery, favorites: favoritesOnly })
  }, [load, filterType, appliedQuery, favoritesOnly])

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
      subtitle="Alles, was Sie nutzen dürfen. Ihre Favoriten stehen oben."
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
      filterBar={
        <AssetFilterBar
          search={{
            value: query,
            onChange: setQuery,
            maxLength: CATALOG_QUERY_MAX_LENGTH,
          }}
          types={{
            offered: ASSET_TYPES,
            value: typeFilter?.type,
            onChange: (type) => setParam('type', assetTypeDefinition(type)?.slug ?? null),
          }}
          filters={{ favorites: favoritesOnly }}
          onToggle={() => setParam('favorites', favoritesOnly ? null : '1')}
        />
      }
      filtered={typeFilter !== undefined || favoritesOnly}
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
          <Box
            sx={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 1,
              mt: 2.5,
            }}
          >
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              {entries.length} von {total} angezeigt
            </Typography>
            <Button variant="outlined" onClick={() => void loadMore()} disabled={isLoading}>
              Weitere laden
            </Button>
          </Box>
        ) : undefined
      }
    />
  )
}
