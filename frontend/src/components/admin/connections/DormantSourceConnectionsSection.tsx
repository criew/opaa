import { useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import type { DormantSourceConnection } from '../../../types/api'
import { listDormantSourceConnections } from '../../../services/connectionProfileApi'
import PageSection from '../../PageSection'
import {
  formatConsentTime,
  responsibleLabel,
  sourceBlockReasonLabel,
  sourceConnectionEndLabel,
} from '../../library/sourceConnectionLabels'

/**
 * The shared libraries whose own source connection ("Quelle verbinden") rests - expired,
 * disconnected, locked or without profile. Private libraries never appear. Shows nothing while
 * none rests, so an installation without such a profile sees no change.
 */
export default function DormantSourceConnectionsSection() {
  const [entries, setEntries] = useState<DormantSourceConnection[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    listDormantSourceConnections()
      .then((loaded) => {
        if (active) setEntries(loaded)
      })
      .catch((err: unknown) => {
        if (active) {
          setError(
            err instanceof Error
              ? err.message
              : 'Die ruhenden Quellverbindungen konnten nicht geladen werden.',
          )
        }
      })
    return () => {
      active = false
    }
  }, [])

  if (error) {
    return (
      <Alert severity="error" role="alert">
        {error}
      </Alert>
    )
  }
  if (!entries || entries.length === 0) return null

  return (
    <PageSection
      title="Ruhende Quellverbindungen"
      description="Bibliotheken, deren Quelle über eine eigene Zustimmung beim Anbieter verbunden ist und gerade nicht erreicht wird. Neu verbinden können die Verwaltenden der Bibliothek; private Bibliotheken stehen hier nie."
    >
      <Table size="small" aria-label="Ruhende Quellverbindungen">
        <TableHead>
          <TableRow>
            <TableCell>Bibliothek</TableCell>
            <TableCell>Zugang</TableCell>
            <TableCell>Konto</TableCell>
            <TableCell>Zustand</TableCell>
            <TableCell>Beendet</TableCell>
            <TableCell>Verantwortlich</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {entries.map((entry) => (
            <TableRow key={entry.libraryId}>
              <TableCell>
                <Link component={RouterLink} to={`/libraries/${entry.libraryId}?tab=quelle`}>
                  {entry.libraryName}
                </Link>
              </TableCell>
              <TableCell>{entry.profileName ?? 'entfernt'}</TableCell>
              <TableCell>{entry.accountLabel ?? '—'}</TableCell>
              <TableCell>{sourceBlockReasonLabel(entry.reason)}</TableCell>
              <TableCell>
                {entry.endedCause
                  ? `${sourceConnectionEndLabel(entry.endedCause)}${entry.endedAt ? `, ${formatConsentTime(entry.endedAt)}` : ''}`
                  : '—'}
              </TableCell>
              <TableCell>{entry.responsible ? responsibleLabel(entry.responsible) : '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </PageSection>
  )
}
