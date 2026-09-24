import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TableCell from '@mui/material/TableCell'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import type { AssetOrigin, AssetType, CatalogEntryResponse } from '../types/api'
import { useCatalogStore } from '../stores/catalogStore'
import { assetTypeTitle } from '../utils/labels'
import MetaBadge from '../components/MetaBadge'
import OverviewPage, { OverviewCard, OverviewRowLink } from '../components/overview/OverviewPage'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import { promptLibraryRoute } from '../routes'

/** Long enough to let a word be typed out before the server is asked. */
const SEARCH_DELAY_MS = 300

type TypeFilter = AssetType | 'ALL'

const TYPE_FILTERS: { value: TypeFilter; label: string }[] = [
  { value: 'ALL', label: 'Alle' },
  { value: 'KNOWLEDGE_LIBRARY', label: 'Wissen' },
  { value: 'PROMPT_LIBRARY', label: 'Prompts' },
]

const originLabels: Record<AssetOrigin, string> = {
  LOCAL: 'lokal angelegt',
  BUILT_IN: 'mitgeliefert',
}

function detailRoute(entry: CatalogEntryResponse): string {
  return entry.assetType === 'PROMPT_LIBRARY'
    ? promptLibraryRoute(entry.assetId)
    : `/libraries/${entry.assetId}`
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

function NoAccessNote({ entry }: { entry: CatalogEntryResponse }) {
  return (
    <Typography component="span" sx={{ fontSize: 12, color: 'text.secondary' }}>
      Gelistet, kein Zugriff — zuständig: {responsibleLabel(entry)}
    </Typography>
  )
}

function CardContent({ entry }: { entry: CatalogEntryResponse }) {
  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <MetaBadge accent>{assetTypeTitle(entry.assetType)}</MetaBadge>
        {entry.origin === 'BUILT_IN' && <MetaBadge>{originLabels.BUILT_IN}</MetaBadge>}
      </Box>
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
      {entry.accessible ? (
        <>
          <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
            zuständig: {responsibleLabel(entry)}
          </Typography>
          <SuccessionStateNote succession={entry.succession} variant="badge" />
        </>
      ) : (
        <NoAccessNote entry={entry} />
      )}
    </>
  )
}

/**
 * A listed entry without access is shown, but leads nowhere: its detail view would answer 404 or
 * 403. It therefore renders as a plain article instead of a link.
 */
function CatalogCard({ entry }: { entry: CatalogEntryResponse }) {
  if (entry.accessible) {
    return (
      <OverviewCard to={detailRoute(entry)}>
        <CardContent entry={entry} />
      </OverviewCard>
    )
  }
  return (
    <Box
      component="article"
      aria-label={entry.name}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
        p: 2.5,
        border: 1,
        borderStyle: 'dashed',
        borderColor: 'divider',
        borderRadius: '16px',
        bgcolor: 'background.paper',
      }}
    >
      <CardContent entry={entry} />
    </Box>
  )
}

function CatalogRow({ entry }: { entry: CatalogEntryResponse }) {
  return (
    <>
      <TableCell>
        {entry.accessible ? (
          <OverviewRowLink to={detailRoute(entry)}>{entry.name}</OverviewRowLink>
        ) : (
          <Typography component="span" sx={{ fontSize: 13.5, fontWeight: 500 }}>
            {entry.name}
          </Typography>
        )}
        {entry.description && (
          <Typography component="div" sx={{ fontSize: 11.5, color: 'text.disabled' }}>
            {entry.description}
          </Typography>
        )}
      </TableCell>
      <TableCell>
        <MetaBadge accent>{assetTypeTitle(entry.assetType)}</MetaBadge>
      </TableCell>
      <TableCell>{responsibleLabel(entry)}</TableCell>
      <TableCell sx={{ fontSize: '12px !important', color: 'text.secondary' }}>
        {originLabels[entry.origin]}
      </TableCell>
      <TableCell>
        {entry.accessible ? (
          <SuccessionStateNote succession={entry.succession} variant="badge" />
        ) : (
          <NoAccessNote entry={entry} />
        )}
      </TableCell>
    </>
  )
}

const columns = [
  { key: 'name', label: 'Name' },
  { key: 'type', label: 'Typ' },
  { key: 'owner', label: 'Zuständig' },
  { key: 'origin', label: 'Herkunft' },
  { key: 'access', label: 'Zugang' },
]

/**
 * The catalog across every asset type (docs/features/spaces-and-assets.md#der-katalog): what the
 * person may use, and what is listed for the whole organization without a right to use it. The
 * overviews under "Wissen" and "Prompts" stay the places where one's own assets are managed.
 */
export default function CatalogPage() {
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('ALL')
  const [query, setQuery] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')
  const entries = useCatalogStore((s) => s.entries)
  const page = useCatalogStore((s) => s.page)
  const totalPages = useCatalogStore((s) => s.totalPages)
  const total = useCatalogStore((s) => s.totalElements)
  const isLoading = useCatalogStore((s) => s.isLoading)
  const error = useCatalogStore((s) => s.error)
  const load = useCatalogStore((s) => s.load)
  const loadMore = useCatalogStore((s) => s.loadMore)

  useEffect(() => {
    const timer = window.setTimeout(() => setAppliedQuery(query), SEARCH_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [query])

  useEffect(() => {
    void load({ type: typeFilter === 'ALL' ? undefined : typeFilter, q: appliedQuery })
  }, [load, typeFilter, appliedQuery])

  return (
    <OverviewPage<CatalogEntryResponse>
      title="Katalog"
      countLabel={(count) => (count === 1 ? '1 Eintrag' : `${count} Einträge`)}
      storageKey="catalog"
      defaultView="cards"
      items={entries}
      itemKey={(entry) => `${entry.assetType}:${entry.assetId}`}
      search={{ value: query, onChange: setQuery }}
      filters={
        <ToggleButtonGroup
          size="small"
          exclusive
          value={typeFilter}
          onChange={(_event, next: TypeFilter | null) => next && setTypeFilter(next)}
          aria-label="Typ"
        >
          {TYPE_FILTERS.map((filter) => (
            <ToggleButton key={filter.value} value={filter.value} sx={{ px: 1.5 }}>
              {filter.label}
            </ToggleButton>
          ))}
        </ToggleButtonGroup>
      }
      filtered={typeFilter !== 'ALL'}
      total={total}
      isLoading={isLoading}
      error={error}
      columns={columns}
      renderCard={(entry) => <CatalogCard entry={entry} />}
      renderRow={(entry) => <CatalogRow entry={entry} />}
      emptyState={
        <Typography sx={{ color: 'text.secondary' }}>
          Der Katalog ist noch leer. Er zeigt alle Wissens- und Prompt-Bibliotheken, die Sie nutzen
          dürfen, und alle, die für die Organisation auffindbar gemacht wurden.
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
      footNote="Einträge ohne Zugriff sind auffindbar gemacht worden; nutzen kann sie nur, wer eine Berechtigung erhält."
    />
  )
}
