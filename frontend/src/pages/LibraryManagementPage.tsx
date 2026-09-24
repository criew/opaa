import { useCallback, useEffect } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import TableCell from '@mui/material/TableCell'
import Typography from '@mui/material/Typography'
import type { LibraryListResponse } from '../types/api'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import { useLibraryStore } from '../stores/libraryStore'
import { fontFamily } from '../theme/tokens'
import {
  assetReachLabel,
  assetRoleLabel,
  documentCountLabel,
  documentSourceTypeLabel,
} from '../utils/labels'
import MetaBadge from '../components/MetaBadge'
import OverviewPage, { OverviewCard, OverviewRowLink } from '../components/overview/OverviewPage'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'

function ownerTypeSummary(library: LibraryListResponse): string {
  if (library.ownerName) return library.ownerName
  if (library.ownerType === 'GROUP') return 'Gruppen-Bibliothek'
  return 'eigene'
}

function lastUpdateDate(library: LibraryListResponse): string {
  if (!library.lastIndexedAt) return '–'
  return new Date(library.lastIndexedAt).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/**
 * The "Letzte Aktualisierung" cell (#1916): the date of the last successful run, a live run with
 * its progress, and a failed last run named as such. An upload library has no run at all - its
 * cell stays empty rather than claiming a missing update.
 */
function LastUpdateCell({ library }: { library: LibraryListResponse }) {
  const run = useIndexingStore((s) => s.runsByLibrary[library.id]) ?? IDLE_RUN_STATE

  if (run.status === 'RUNNING') {
    const percent =
      run.totalDocuments > 0 ? Math.round((run.documentCount / run.totalDocuments) * 100) : 0
    return (
      <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.875 }}>
        <Box
          component="span"
          aria-hidden="true"
          sx={{
            width: 80,
            height: 4,
            bgcolor: 'divider',
            borderRadius: '2px',
            overflow: 'hidden',
            display: 'inline-block',
          }}
        >
          <Box
            component="span"
            sx={{ display: 'block', width: `${percent}%`, height: '100%', bgcolor: 'primary.main' }}
          />
        </Box>
        Lauf läuft · {percent} %
      </Box>
    )
  }

  if (run.status === 'FAILED') {
    return (
      <Box component="span" sx={{ color: 'error.main' }}>
        Lauf fehlgeschlagen
      </Box>
    )
  }

  if (library.sourceType === 'UPLOAD') return null

  return <>{lastUpdateDate(library)}</>
}

function LibraryCard({ library }: { library: LibraryListResponse }) {
  return (
    <OverviewCard to={`/libraries/${library.id}`}>
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
        {[
          ownerTypeSummary(library),
          documentSourceTypeLabel(library.sourceType),
          `${documentCountLabel(library.documentCount)} ${library.documentCount === 1 ? 'Dokument' : 'Dokumente'}`,
        ].join(' · ')}
      </Typography>
      <SuccessionStateNote succession={library.succession} variant="badge" />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
        {/* #1931: die Reichweite ist abgeleitet, keine gespeicherte Stufe. */}
        <MetaBadge>{assetReachLabel(library.reach)}</MetaBadge>
      </Box>
    </OverviewCard>
  )
}

function LibraryRow({ library }: { library: LibraryListResponse }) {
  return (
    <>
      <TableCell>
        <OverviewRowLink to={`/libraries/${library.id}`}>{library.name}</OverviewRowLink>
        <Typography component="div" sx={{ fontSize: 11.5, color: 'text.disabled' }}>
          {[library.description, ownerTypeSummary(library)].filter(Boolean).join(' · ')}
        </Typography>
        {/* ADR-0036, Entscheidung 6: Zustand und Adressat, ohne Datum, früheren Eigentümer
            oder Grund - die Übersicht zeigt ihn, ohne die Bibliothek zu öffnen. */}
        <SuccessionStateNote succession={library.succession} variant="badge" />
      </TableCell>
      <TableCell>{documentSourceTypeLabel(library.sourceType)}</TableCell>
      <TableCell sx={{ fontFamily: fontFamily.mono, fontSize: '12.5px !important' }}>
        {documentCountLabel(library.documentCount)}
      </TableCell>
      <TableCell sx={{ fontSize: '12px !important', color: 'text.secondary' }}>
        {assetReachLabel(library.reach)}
      </TableCell>
      <TableCell>
        <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
      </TableCell>
      <TableCell sx={{ fontSize: '12px !important', color: 'text.secondary' }}>
        <LastUpdateCell library={library} />
      </TableCell>
    </>
  )
}

const columns = [
  { key: 'name', label: 'Name' },
  { key: 'source', label: 'Herkunft' },
  { key: 'documents', label: 'Dokumente' },
  { key: 'reach', label: 'Reichweite' },
  { key: 'role', label: 'Ihre Rolle' },
  { key: 'lastUpdate', label: 'Letzte Aktualisierung' },
]

export default function LibraryManagementPage() {
  const navigate = useNavigate()
  const libraries = useLibraryStore((s) => s.libraries)
  const isLoading = useLibraryStore((s) => s.isLoading)
  const error = useLibraryStore((s) => s.error)
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)

  useEffect(() => {
    void loadLibraries()
  }, [loadLibraries])

  const searchText = useCallback(
    (library: LibraryListResponse) => `${library.name} ${library.description ?? ''}`,
    [],
  )

  // Die Seite heißt wie ihr Menüpunkt (#1915); die Anzahl steht in der Zeile daneben.
  return (
    <OverviewPage<LibraryListResponse>
      title="Wissen"
      countLabel={(count) => (count === 1 ? '1 Bibliothek' : `${count} Bibliotheken`)}
      createLabel="Neue Bibliothek"
      onCreate={() => navigate('/libraries/new')}
      storageKey="libraries"
      defaultView="table"
      items={libraries}
      itemKey={(library) => library.id}
      searchText={searchText}
      isLoading={isLoading}
      error={error}
      columns={columns}
      renderCard={(library) => <LibraryCard library={library} />}
      renderRow={(library) => <LibraryRow library={library} />}
      emptyState={
        <Typography sx={{ color: 'text.secondary' }}>
          Es sind noch keine Bibliotheken vorhanden.
        </Typography>
      }
      footNote="Bestände ohne Leserecht erscheinen hier nicht. Laufende Vorgänge bleiben über einen Seitenwechsel hinweg sichtbar."
    />
  )
}
