import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CableOutlinedIcon from '@mui/icons-material/CableOutlined'
import AreaPageHeader from '../components/AreaPageHeader'
import BusyButton from '../components/a11y/BusyButton'
import { useAuthStore } from '../stores/authStore'
import { listConnectionLog } from '../services/connectionLogApi'
import type { ConnectionLogEventType, ConnectionLogPage as LogPage } from '../types/api'
import {
  CONNECTION_LOG_EVENT_TYPES,
  actorLabel,
  endCauseLabel,
  eventTypeLabel,
  ownerKindLabel,
  ownerLabel,
} from '../components/audit/connectionLogLabels'
import { contentWidth } from '../theme/tokens'

const ALL = ''

function toInstant(localValue: string): string {
  return new Date(localValue).toISOString()
}

/** The filter a shown page was read with, so paging keeps it even after the fields change. */
interface AppliedQuery {
  from: string
  to: string
  reason: string
  eventType?: ConnectionLogEventType
  profileId?: string
}

/**
 * The connection log for the audit (AUDITOR only): who connected, reconnected or disconnected an
 * account on which profile, and when a connection expired, was cut off or deleted. Window and
 * reason are mandatory and every read is recorded; there is deliberately no filter by person.
 */
export default function ConnectionLogPage() {
  const isAuditor = useAuthStore((s) => s.user?.systemRole === 'AUDITOR')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [reason, setReason] = useState('')
  const [eventType, setEventType] = useState<ConnectionLogEventType | typeof ALL>(ALL)
  const [profileId, setProfileId] = useState(ALL)
  // The profiles seen in earlier answers - the audit has no list of profiles of its own.
  const [knownProfiles, setKnownProfiles] = useState<Map<string, string>>(new Map())
  const [result, setResult] = useState<LogPage | null>(null)
  const [applied, setApplied] = useState<AppliedQuery | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)

  const canSubmit = from !== '' && to !== '' && reason.trim() !== ''

  async function load(query: AppliedQuery, page: number): Promise<void> {
    setIsLoading(true)
    try {
      const loaded = await listConnectionLog({ ...query, page })
      setResult(loaded)
      setApplied(query)
      setError(null)
      setKnownProfiles((previous) => {
        const next = new Map(previous)
        for (const entry of loaded.entries) next.set(entry.profileId, entry.profileName)
        return next
      })
    } catch (err: unknown) {
      setResult(null)
      setError(
        err instanceof Error ? err.message : 'Das Verbindungsprotokoll konnte nicht gelesen werden',
      )
    } finally {
      setIsLoading(false)
    }
  }

  function submit() {
    if (!canSubmit || isLoading) return
    void load(
      {
        from: toInstant(from),
        to: toInstant(to),
        reason: reason.trim(),
        eventType: eventType === ALL ? undefined : eventType,
        profileId: profileId === ALL ? undefined : profileId,
      },
      0,
    )
  }

  if (!isAuditor) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
        <Alert severity="info">Das Verbindungsprotokoll ist der Revision vorbehalten.</Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={CableOutlinedIcon}
          title="Verbindungsprotokoll"
          description="Wann ein Konto mit einem Zugang verbunden, neu verbunden oder getrennt wurde und wann eine Verbindung ablief oder abgeschaltet wurde. Personen erscheinen nur als Pseudonym, kein Eintrag enthält Zugangsdaten. Zeitraum (höchstens 92 Tage) und Anlass sind Pflicht; jeder Abruf wird protokolliert."
        />

        <Stack spacing={2} sx={{ mb: 3 }}>
          <TextField
            label="Von"
            type="datetime-local"
            value={from}
            slotProps={{ inputLabel: { shrink: true } }}
            onChange={(event) => setFrom(event.target.value)}
          />
          <TextField
            label="Bis"
            type="datetime-local"
            value={to}
            slotProps={{ inputLabel: { shrink: true } }}
            onChange={(event) => setTo(event.target.value)}
          />
          <TextField
            select
            label="Ereignis (optional)"
            value={eventType}
            onChange={(event) =>
              setEventType(event.target.value as ConnectionLogEventType | typeof ALL)
            }
          >
            <MenuItem value={ALL}>Alle Ereignisse</MenuItem>
            {CONNECTION_LOG_EVENT_TYPES.map((type) => (
              <MenuItem key={type} value={type}>
                {eventTypeLabel(type)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Zugang (optional)"
            value={profileId}
            onChange={(event) => setProfileId(event.target.value)}
            helperText="Zur Auswahl stehen die Zugänge, die in bisherigen Abfragen vorkamen."
          >
            <MenuItem value={ALL}>Alle Zugänge</MenuItem>
            {[...knownProfiles.entries()].map(([id, name]) => (
              <MenuItem key={id} value={id}>
                {name}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Anlass"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            helperText="Pflichtangabe. Der Anlass steht im Protokolleintrag dieses Abrufs."
          />
          <Box>
            <BusyButton
              variant="contained"
              busy={isLoading}
              busyAnnouncement="Verbindungsprotokoll wird gelesen"
              disabled={!canSubmit && !isLoading}
              onClick={submit}
            >
              Protokoll anzeigen
            </BusyButton>
          </Box>
        </Stack>

        {error && (
          <Alert severity="error" role="alert" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {result && applied && (
          <>
            {result.entries.length === 0 ? (
              <Typography sx={{ color: 'text.secondary' }}>
                Für diesen Zeitraum und diese Auswahl ist kein Eintrag verzeichnet. Einträge
                entstehen erst, wenn Personen ein Konto mit einem Zugang verbinden, neu verbinden
                oder trennen oder eine solche Verbindung abläuft oder beendet wird; solange niemand
                ein Konto verbunden hat, bleibt das Protokoll leer. Einträge, die älter als die
                Aufbewahrungsfrist sind, sind gelöscht.
              </Typography>
            ) : (
              <TableContainer>
                <Table size="small" aria-label="Einträge des Verbindungsprotokolls">
                  <TableHead>
                    <TableRow>
                      <TableCell>Zeit</TableCell>
                      <TableCell>Ereignis</TableCell>
                      <TableCell>Anlass</TableCell>
                      <TableCell>Besitzart</TableCell>
                      <TableCell>Person bzw. Bibliothek</TableCell>
                      <TableCell>Zugang</TableCell>
                      <TableCell>Ausgelöst von</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {result.entries.map((entry) => (
                      <TableRow key={entry.eventId}>
                        <TableCell>{new Date(entry.recordedAt).toLocaleString('de-DE')}</TableCell>
                        <TableCell>{eventTypeLabel(entry.eventType)}</TableCell>
                        <TableCell>{entry.cause ? endCauseLabel(entry.cause) : '—'}</TableCell>
                        <TableCell>{ownerKindLabel(entry.ownerKind)}</TableCell>
                        <TableCell sx={{ wordBreak: 'break-all' }}>{ownerLabel(entry)}</TableCell>
                        <TableCell>{entry.profileName}</TableCell>
                        <TableCell>{actorLabel(entry.actorRef)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
            {(result.page > 0 || result.hasMore) && (
              <Stack direction="row" spacing={2} sx={{ mt: 2, alignItems: 'center' }}>
                <Button
                  disabled={result.page === 0 || isLoading}
                  onClick={() => void load(applied, result.page - 1)}
                >
                  Zurück
                </Button>
                <Typography>Seite {result.page + 1}</Typography>
                <Button
                  disabled={!result.hasMore || isLoading}
                  onClick={() => void load(applied, result.page + 1)}
                >
                  Weiter
                </Button>
              </Stack>
            )}
          </>
        )}
      </Box>
    </Box>
  )
}
