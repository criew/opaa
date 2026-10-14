import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Skeleton from '@mui/material/Skeleton'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import type { ExternalAccessLibraryResponse } from '@/shared/api/types'
import MetaBadge from '@/shared/ui/MetaBadge'
import PageSection from '@/shared/ui/PageSection'
import { formatDate } from './tokenLabels'
import { ListEmptyState } from '@/shared/ui/list/AdminList'
import { adminTableSx, definitionListSx, listCardSx } from '@/shared/ui/list/adminListStyles'
import { releaseEndingLabel } from './externalToolsState'

interface ReleasedLibrariesSectionProps {
  /** null while loading. */
  libraries: ExternalAccessLibraryResponse[] | null
  error: string | null
  onRetry: () => void
}

function libraryLink(entry: ExternalAccessLibraryResponse) {
  return (
    <Link
      component={RouterLink}
      to={`/libraries/${entry.libraryId}?tab=freigaben`}
      sx={{ fontWeight: 600, overflowWrap: 'anywhere' }}
    >
      {entry.libraryName}
    </Link>
  )
}

function Until({ entry }: { entry: ExternalAccessLibraryResponse }) {
  const ending = releaseEndingLabel(entry.externalAccess.expiresAt)
  return (
    <Box component="span" sx={{ display: 'inline-flex', gap: 1, flexWrap: 'wrap' }}>
      <span>{formatDate(entry.externalAccess.expiresAt)}</span>
      {ending && <MetaBadge>{ending}</MetaBadge>}
    </Box>
  )
}

function setBy(entry: ExternalAccessLibraryResponse): string {
  return `${entry.externalAccess.setByDisplayName ?? 'unbekannt'}, ${formatDate(entry.externalAccess.setAt)}`
}

/**
 * Die derzeit für externe KI-Werkzeuge freigegebenen Bibliotheken (#1731). Bewusst nur die
 * gültigen: Die Liste macht den Bestand prüfbar, und eine Liste aller je freigegebenen Bibliotheken
 * begrübe genau das. Sie nennt keine Person, die ein Zugangstoken hält - nur die Stelle, die
 * freigegeben hat, und die Zahl der Tokens.
 */
export default function ReleasedLibrariesSection({
  libraries,
  error,
  onRetry,
}: ReleasedLibrariesSectionProps) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))

  return (
    <PageSection
      title={
        libraries ? `Freigegebene Bibliotheken (${libraries.length})` : 'Freigegebene Bibliotheken'
      }
      description="Jede Freigabe ist befristet und erlischt von selbst; setzen und erneuern kann sie nur, wer die Bibliothek verwaltet - in der Bibliothek unter „Freigaben“."
    >
      {error ? (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={onRetry}>
              Erneut laden
            </Button>
          }
        >
          {error}
        </Alert>
      ) : libraries === null ? (
        <Skeleton variant="rounded" height={96} />
      ) : libraries.length === 0 ? (
        <ListEmptyState
          title="Derzeit ist keine Bibliothek freigegeben."
          hint="Externe KI-Werkzeuge erreichen erst Bibliotheken, die deren Verwaltende freigegeben haben."
        />
      ) : isDesktop ? (
        <TableContainer>
          <Table
            size="small"
            aria-label="Freigegebene Bibliotheken"
            sx={{ ...adminTableSx, minWidth: 680 }}
          >
            <TableHead>
              <TableRow>
                <TableCell>Bibliothek</TableCell>
                <TableCell sx={{ width: '26%' }}>Freigegeben bis</TableCell>
                <TableCell sx={{ width: '26%' }}>Gesetzt von</TableCell>
                <TableCell align="right" sx={{ width: 128 }}>
                  Zugangstokens
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {libraries.map((entry) => (
                <TableRow key={entry.libraryId}>
                  <TableCell>{libraryLink(entry)}</TableCell>
                  <TableCell>
                    <Until entry={entry} />
                  </TableCell>
                  <TableCell>{setBy(entry)}</TableCell>
                  <TableCell align="right">{entry.externalAccess.tokenCount}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      ) : (
        <Box
          component="ul"
          aria-label="Freigegebene Bibliotheken"
          sx={{ listStyle: 'none', p: 0, m: 0, borderTop: 1, borderColor: 'divider' }}
        >
          {libraries.map((entry) => (
            <Box component="li" key={entry.libraryId} sx={listCardSx}>
              <Typography component="div" sx={{ fontSize: 13.5 }}>
                {libraryLink(entry)}
              </Typography>
              <Box component="dl" sx={definitionListSx}>
                <dt>Freigegeben bis</dt>
                <dd>
                  <Until entry={entry} />
                </dd>
                <dt>Gesetzt von</dt>
                <dd>{setBy(entry)}</dd>
                <dt>Zugangstokens</dt>
                <dd>{entry.externalAccess.tokenCount}</dd>
              </Box>
            </Box>
          ))}
        </Box>
      )}
    </PageSection>
  )
}
