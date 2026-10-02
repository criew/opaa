import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import type { CatalogEntryResponse } from '../types/api'
import { CATALOG_QUERY_MAX_LENGTH, useCatalogStore } from '../stores/catalogStore'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import {
  ASSET_TYPES,
  assetTypeDefinition,
  creatableAssetTypes,
  type AssetTypeDefinition,
} from '../components/assets/assetTypeRegistry'
import OverviewPage, { OverviewCard } from '../components/overview/OverviewPage'
import UseInSpaceButton from '../components/assets/UseInSpaceButton'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import { CATALOG_NEW_ROUTE } from '../routes'

/** Long enough to let a word be typed out before the server is asked. */
const SEARCH_DELAY_MS = 300

const ALL_TYPES = 'all'

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

function CatalogCard({
  entry,
  definition,
}: {
  entry: CatalogEntryResponse
  definition: AssetTypeDefinition
}) {
  return (
    <OverviewCard
      to={definition.detailRoute(entry.assetId)}
      action={
        <UseInSpaceButton
          assetType={entry.assetType}
          assetId={entry.assetId}
          name={entry.name}
          size="small"
        />
      }
    >
      <TypeBadge definition={definition} />
      <Typography component="span" sx={{ fontSize: 16.5, fontWeight: 600 }}>
        {entry.name}
      </Typography>
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
      <SuccessionStateNote succession={entry.succession} variant="badge" />
    </OverviewCard>
  )
}

/**
 * The one entry for every asset type (ADR-0039, Entscheidung 1): what the person may read, as
 * cards. Type filter and search run on the server; the type filter stands in the address
 * (`?type=`), so `/libraries` and `/prompts` can lead here narrowed to their type.
 */
export default function CatalogPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const typeFilter = ASSET_TYPES.find((definition) => definition.slug === searchParams.get('type'))
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
    void load({ type: filterType, q: appliedQuery })
  }, [load, filterType, appliedQuery])

  function changeType(slug: string | null) {
    if (!slug) return
    setSearchParams(slug === ALL_TYPES ? {} : { type: slug }, { replace: true })
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
        <ToggleButtonGroup
          size="small"
          exclusive
          value={typeFilter?.slug ?? ALL_TYPES}
          onChange={(_event, next: string | null) => changeType(next)}
          aria-label="Typ"
        >
          <ToggleButton value={ALL_TYPES} sx={{ px: 1.5 }}>
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
      }
      filtered={typeFilter !== undefined}
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
      footNote="Der Katalog zeigt nur, was Sie lesen dürfen."
    />
  )
}
