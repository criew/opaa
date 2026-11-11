import Box from '@mui/material/Box'
import type { ExternalAccessLibraryResponse } from '@/shared/api/types'
import MetaBadge from '@/shared/ui/MetaBadge'
import PageSection from '@/shared/ui/PageSection'
import { formatDate } from './tokenLabels'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import { releaseEndingLabel } from './externalToolsState'
import EmptyState from '@/shared/ui/states/EmptyState'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

interface ReleasedLibrariesSectionProps {
  /** null while loading. */
  libraries: ExternalAccessLibraryResponse[] | null
  error: string | null
  onRetry: () => void
}

function Until({ entry }: { entry: ExternalAccessLibraryResponse }) {
  const ending = releaseEndingLabel(entry.externalAccess.expiresAt)
  return (
    <Box component="span" sx={{ display: 'inline-flex', gap: 1, alignItems: 'center' }}>
      <span>{formatDate(entry.externalAccess.expiresAt)}</span>
      {ending && <MetaBadge>{ending}</MetaBadge>}
    </Box>
  )
}

function setBy(entry: ExternalAccessLibraryResponse): string {
  return `${entry.externalAccess.setByDisplayName ?? 'unbekannt'}, ${formatDate(entry.externalAccess.setAt)}`
}

/**
 * The libraries currently released for external AI tools. Deliberately only the
 * valid ones: the list makes the inventory checkable, and a list of all libraries ever released
 * would bury exactly that. It names no person holding an access token - only the party that
 * released, and the number of tokens.
 */
export default function ReleasedLibrariesSection({
  libraries,
  error,
  onRetry,
}: ReleasedLibrariesSectionProps) {
  return (
    <PageSection
      title={
        libraries ? `Freigegebene Bibliotheken (${libraries.length})` : 'Freigegebene Bibliotheken'
      }
      description="Jede Freigabe ist befristet und erlischt von selbst; setzen und erneuern kann sie nur, wer die Bibliothek verwaltet - in der Bibliothek unter „Freigaben“."
    >
      {error ? (
        <LoadError
          message="Die freigegebenen Bibliotheken konnten nicht geladen werden."
          detail={error}
          onRetry={onRetry}
        />
      ) : libraries === null ? (
        <LoadingState count={2} />
      ) : libraries.length === 0 ? (
        <EmptyState
          framed
          message="Noch keine freigegebenen Bibliotheken. Externe KI-Werkzeuge erreichen erst Bibliotheken, die deren Verwaltende freigegeben haben."
        />
      ) : (
        <AdminList
          label="Freigegebene Bibliotheken"
          rows={libraries}
          rowKey={(entry) => entry.libraryId}
          rowLabel={(entry) => entry.libraryName}
          minWidth={680}
          rowTarget={(entry) => ({ to: `/libraries/${entry.libraryId}?tab=freigaben` })}
          columns={[
            {
              id: 'library',
              head: 'Bibliothek',
              cell: (entry) => <ListText strong>{entry.libraryName}</ListText>,
            },
            {
              id: 'until',
              head: 'Freigegeben bis',
              width: '26%',
              cell: (entry) => <Until entry={entry} />,
            },
            { id: 'setBy', head: 'Gesetzt von', width: '26%', cell: (entry) => setBy(entry) },
            {
              id: 'tokens',
              head: 'Zugangstokens',
              width: 128,
              align: 'right',
              cell: (entry) => entry.externalAccess.tokenCount,
            },
          ]}
        />
      )}
    </PageSection>
  )
}
