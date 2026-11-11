import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import CableOutlinedIcon from '@mui/icons-material/CableOutlined'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import AdminList from '@/shared/ui/list/AdminList'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { useAuthStore } from '@/shared/session/authStore'
import { listConnectionLog, listConnectionLogProfiles } from '../api/connectionLogApi'
import type {
  ConnectionLogEventType,
  ConnectionLogPage as LogPage,
  ConnectionLogProfile,
} from '@/shared/api/types'
import {
  CONNECTION_LOG_EVENT_TYPES,
  actorLabel,
  endCauseLabel,
  eventTypeLabel,
  ownerKindLabel,
  ownerLabel,
} from '../components/connectionLogLabels'
import { contentWidth, formWidth } from '@/shared/theme/tokens'
import { formatProtocolTime } from '@/shared/utils/formatDate'

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
  // Read from the log itself, so a deleted profile stays choosable and the audit needs no read
  // access to the administration; without the list the filter offers „Alle Zugänge“ only.
  const [loggedProfiles, setLoggedProfiles] = useState<ConnectionLogProfile[]>([])
  const [result, setResult] = useState<LogPage | null>(null)
  const [applied, setApplied] = useState<AppliedQuery | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)

  const canSubmit = from !== '' && to !== '' && reason.trim() !== ''

  useEffect(() => {
    if (!isAuditor) return
    let cancelled = false
    listConnectionLogProfiles()
      .then((profiles) => {
        if (!cancelled) setLoggedProfiles(profiles)
      })
      .catch(() => {
        if (!cancelled) setLoggedProfiles([])
      })
    return () => {
      cancelled = true
    }
  }, [isAuditor])

  async function load(query: AppliedQuery, page: number): Promise<void> {
    setIsLoading(true)
    try {
      const loaded = await listConnectionLog({ ...query, page })
      setResult(loaded)
      setApplied(query)
      setError(null)
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
      <Box sx={{ maxWidth: contentWidth.list }}>
        <AreaPageHeader
          icon={CableOutlinedIcon}
          title="Verbindungsprotokoll"
          description="Wann Konten mit einem Zugang verbunden oder getrennt wurden und Verbindungen endeten. Personen erscheinen nur als Pseudonym, Zugangsdaten nie; Zeitraum höchstens 92 Tage, jeder Abruf wird protokolliert."
        />

        <Stack spacing={2} sx={{ mb: 3, maxWidth: formWidth.column }}>
          <FormField
            label="Von"
            type="datetime-local"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
          <FormField
            label="Bis"
            type="datetime-local"
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
          <FormField
            select
            label="Ereignis"
            optional
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
          </FormField>
          <FormField
            select
            label="Zugang"
            optional
            value={profileId}
            onChange={(event) => setProfileId(event.target.value)}
            helperText="Zur Auswahl stehen alle Zugänge mit Einträgen im Protokoll, auch gelöschte."
          >
            <MenuItem value={ALL}>Alle Zugänge</MenuItem>
            {loggedProfiles.map((profile) => (
              <MenuItem key={profile.profileId} value={profile.profileId}>
                {profile.deleted ? `${profile.name} (gelöscht)` : profile.name}
              </MenuItem>
            ))}
          </FormField>
          <FormField
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
              <AdminList
                label="Einträge des Verbindungsprotokolls"
                rows={result.entries}
                rowKey={(entry) => entry.eventId}
                rowLabel={(entry) =>
                  `${eventTypeLabel(entry.eventType)}, ${formatProtocolTime(entry.recordedAt)}`
                }
                minWidth={920}
                columns={[
                  {
                    id: 'time',
                    head: 'Zeit',
                    width: 168,
                    card: 'line',
                    cell: (entry) => formatProtocolTime(entry.recordedAt),
                  },
                  {
                    id: 'event',
                    head: 'Ereignis',
                    card: 'title',
                    cell: (entry) => eventTypeLabel(entry.eventType),
                  },
                  {
                    id: 'cause',
                    head: 'Endgrund',
                    cell: (entry) => (entry.cause ? endCauseLabel(entry.cause) : '—'),
                  },
                  {
                    id: 'ownerKind',
                    head: 'Besitzart',
                    width: '11%',
                    card: 'hidden',
                    cell: (entry) => ownerKindLabel(entry.ownerKind),
                  },
                  { id: 'owner', head: 'Besitzer', cell: (entry) => ownerLabel(entry) },
                  { id: 'profile', head: 'Zugang', cell: (entry) => entry.profileName },
                  {
                    id: 'actor',
                    head: 'Ausgelöst von',
                    cell: (entry) => actorLabel(entry.actor),
                  },
                ]}
                pager={{
                  page: result.page,
                  hasMore: result.hasMore,
                  disabled: isLoading,
                  onPage: (page) => void load(applied, page),
                }}
              />
            )}
          </>
        )}
      </Box>
    </Box>
  )
}
