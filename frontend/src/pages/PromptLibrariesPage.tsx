import { useCallback, useEffect } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import TableCell from '@mui/material/TableCell'
import Typography from '@mui/material/Typography'
import type { PromptLibraryResponse } from '../types/api'
import { usePromptLibraryStore } from '../stores/promptLibraryStore'
import { fontFamily } from '../theme/tokens'
import { assetReachLabel, assetRoleLabel } from '../utils/labels'
import MetaBadge from '../components/MetaBadge'
import OverviewPage, { OverviewCard, OverviewRowLink } from '../components/overview/OverviewPage'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import { promptLibraryRoute } from '../routes'

function ownerSummary(library: PromptLibraryResponse): string {
  if (library.ownerName) return library.ownerName
  return library.ownerType === 'GROUP' ? 'Gruppen-Bibliothek' : 'eigene'
}

function promptCountLabel(count: number): string {
  return count === 1 ? '1 Prompt' : `${count} Prompts`
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

function PromptLibraryCard({ library }: { library: PromptLibraryResponse }) {
  return (
    <OverviewCard to={promptLibraryRoute(library.id)}>
      <Typography component="span" sx={{ fontSize: 16.5, fontWeight: 600 }}>
        {library.name}
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
        {library.description ?? ''}
      </Typography>
      <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
        {[ownerSummary(library), promptCountLabel(library.promptCount)].join(' · ')}
      </Typography>
      <SuccessionStateNote succession={library.succession} variant="badge" />
      <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 0.75 }}>
        <MetaBadge>Prompts</MetaBadge>
        <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
        {/* #1931: die Reichweite ist abgeleitet, keine gespeicherte Stufe. */}
        <MetaBadge>{assetReachLabel(library.reach)}</MetaBadge>
      </Box>
    </OverviewCard>
  )
}

function PromptLibraryRow({ library }: { library: PromptLibraryResponse }) {
  return (
    <>
      <TableCell>
        <OverviewRowLink to={promptLibraryRoute(library.id)}>{library.name}</OverviewRowLink>
        <Typography component="div" sx={{ fontSize: 11.5, color: 'text.disabled' }}>
          {[library.description, ownerSummary(library)].filter(Boolean).join(' · ')}
        </Typography>
        <SuccessionStateNote succession={library.succession} variant="badge" />
      </TableCell>
      <TableCell sx={{ fontFamily: fontFamily.mono, fontSize: '12.5px !important' }}>
        {library.promptCount}
      </TableCell>
      <TableCell sx={{ fontSize: '12px !important', color: 'text.secondary' }}>
        {assetReachLabel(library.reach)}
      </TableCell>
      <TableCell>
        <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
      </TableCell>
      <TableCell sx={{ fontSize: '12px !important', color: 'text.secondary' }}>
        {formatDate(library.updatedAt)}
      </TableCell>
    </>
  )
}

const columns = [
  { key: 'name', label: 'Name' },
  { key: 'prompts', label: 'Prompts' },
  { key: 'reach', label: 'Reichweite' },
  { key: 'role', label: 'Ihre Rolle' },
  { key: 'updated', label: 'Zuletzt geändert' },
]

/** The prompt libraries the person may read, under the rail entry "Prompts" of the same name. */
export default function PromptLibrariesPage() {
  const navigate = useNavigate()
  const libraries = usePromptLibraryStore((s) => s.libraries)
  const isLoading = usePromptLibraryStore((s) => s.isLoading)
  const error = usePromptLibraryStore((s) => s.error)
  const loadLibraries = usePromptLibraryStore((s) => s.loadLibraries)

  useEffect(() => {
    void loadLibraries()
  }, [loadLibraries])

  const searchText = useCallback(
    (library: PromptLibraryResponse) => `${library.name} ${library.description ?? ''}`,
    [],
  )

  return (
    <OverviewPage<PromptLibraryResponse>
      title="Prompts"
      countLabel={(count) => (count === 1 ? '1 Prompt-Bibliothek' : `${count} Prompt-Bibliotheken`)}
      createLabel="Neue Prompt-Bibliothek"
      onCreate={() => navigate('/prompts/new')}
      storageKey="prompt-libraries"
      defaultView="cards"
      items={libraries}
      itemKey={(library) => library.id}
      searchText={searchText}
      isLoading={isLoading}
      error={error}
      columns={columns}
      renderCard={(library) => <PromptLibraryCard library={library} />}
      renderRow={(library) => <PromptLibraryRow library={library} />}
      emptyState={
        <Typography sx={{ color: 'text.secondary' }}>
          Es sind noch keine Prompt-Bibliotheken vorhanden. Eine Prompt-Bibliothek sammelt
          wiederkehrende Formulierungshilfen — benannt, beschrieben und mit Platzhaltern für das,
          was sich von Fall zu Fall ändert.
        </Typography>
      }
      footNote="Prompt-Bibliotheken ohne Leserecht erscheinen hier nicht."
    />
  )
}
