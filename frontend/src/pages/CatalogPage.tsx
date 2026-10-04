import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Typography from '@mui/material/Typography'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import WorkspacesOutlinedIcon from '@mui/icons-material/WorkspacesOutlined'
import type { CatalogEntryResponse } from '../types/api'
import { CATALOG_QUERY_MAX_LENGTH, useCatalogStore } from '../stores/catalogStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import {
  ASSET_TYPES,
  assetTypeDefinition,
  creatableAssetTypes,
} from '../components/assets/assetTypeRegistry'
import OverviewPage from '../components/overview/OverviewPage'
import AssetTile from '../components/assets/AssetTile'
import { tileFromCatalogEntry } from '../components/assets/assetTileData'
import { UseInSpaceDialog } from '../components/assets/UseInSpaceButton'
import AssetFilterBar from '../components/assets/AssetFilterBar'
import { CATALOG_NEW_ROUTE } from '../routes'

/** Long enough to let a word be typed out before the server is asked. */
const SEARCH_DELAY_MS = 300

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
  to,
  privateLibraryIds,
}: {
  entry: CatalogEntryResponse
  to: string
  privateLibraryIds: ReadonlySet<string>
}) {
  const setFavorite = useCatalogStore((s) => s.setFavorite)
  return (
    <AssetTile
      tile={tileFromCatalogEntry(entry, privateLibraryIds)}
      mode={{ kind: 'link', to }}
      onFavoriteChange={(favorite) => setFavorite(entry, favorite)}
      actions={<MoreActions entry={entry} />}
    />
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
  // The catalog entry does not say which library is private; the caller's library list does.
  const libraries = useLibraryStore((s) => s.libraries)
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)
  useEffect(() => {
    void loadLibraries()
  }, [loadLibraries])
  const privateLibraryIds = useMemo(
    () => new Set(libraries.filter((l) => l.privateLibrary).map((l) => l.id)),
    [libraries],
  )

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
        return definition ? (
          <CatalogCard
            entry={entry}
            to={definition.detailRoute(entry.assetId)}
            privateLibraryIds={privateLibraryIds}
          />
        ) : null
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
