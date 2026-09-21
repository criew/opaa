import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import HistoryOutlinedIcon from '@mui/icons-material/HistoryOutlined'
import AreaPageHeader from '../components/AreaPageHeader'
import { useAuthStore } from '../stores/authStore'
import { getAccessAsOf } from '../services/rightsHistoryApi'
import type {
  AccessAsOfObjectType,
  AccessAsOfPage,
  AccessAsOfSource,
  AccessBasis,
} from '../types/api'
import { contentWidth } from '../theme/tokens'

const BASIS_LABELS: Record<AccessBasis, string> = {
  DIRECT_GRANT: 'Direkte Freigabe',
  GROUP_GRANT: 'Freigabe an eine Gruppe',
  ORGANIZATION_WIDE: 'Organisationsweit freigegeben',
  DIRECT_MEMBERSHIP: 'Eigene Mitgliedschaft',
  GROUP_MEMBERSHIP: 'Mitgliedschaft einer Gruppe',
  OWNERSHIP: 'Eigentum',
  SYSTEM_ADMINISTRATION: 'Systemverwaltung',
}

const SOURCE_LABELS: Record<AccessAsOfSource, string> = {
  SYSTEM_ROLE: 'Systemrolle (eine Systemverwaltung erreicht jede Bibliothek ihrer Organisation)',
  OWNERSHIP: 'Eigentum an Bibliothek oder Space',
  CAPABILITY: 'Anlegerechte',
  ACCOUNT_STATE: 'Kontozustand (aktiv oder gesperrt)',
}

function formatInstant(value: string | null | undefined, fallback: string): string {
  if (!value) return fallback
  return new Date(value).toLocaleString('de-DE')
}

function toInstant(localValue: string): string {
  return new Date(localValue).toISOString()
}

/**
 * Die Stichtagsauskunft aus der Rechtehistorie (#1822): wer durfte genau dieses Objekt in genau
 * diesem Zeitraum lesen. Es gibt hier bewusst keine Suche über mehrere Objekte und keinen Einstieg
 * über eine Person — beides setzte ohne Vollmacht ein Rechteprofil zusammen. Das Zeitfenster ist
 * Pflicht, der Anlass ebenfalls, und jeder Abruf steht anschließend im Protokoll.
 */
export default function RightsHistoryPage() {
  const isAuditor = useAuthStore((s) => s.user?.systemRole === 'AUDITOR')
  const [objectType, setObjectType] = useState<AccessAsOfObjectType>('KNOWLEDGE_LIBRARY')
  const [objectId, setObjectId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [reason, setReason] = useState('')
  const [result, setResult] = useState<AccessAsOfPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)

  const canSubmit =
    objectId.trim() !== '' && from !== '' && to !== '' && reason.trim() !== '' && !isLoading

  async function submit(pageIndex = 0): Promise<void> {
    setIsLoading(true)
    try {
      const page = await getAccessAsOf({
        objectType,
        objectId: objectId.trim(),
        from: toInstant(from),
        to: toInstant(to),
        reason: reason.trim(),
        page: pageIndex,
      })
      setResult(page)
      setError(null)
    } catch (err: unknown) {
      setResult(null)
      setError(err instanceof Error ? err.message : 'Die Auskunft konnte nicht erstellt werden')
    } finally {
      setIsLoading(false)
    }
  }

  if (!isAuditor) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
        <Alert severity="info">Die Stichtagsauskunft ist der Revision vorbehalten.</Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={HistoryOutlinedIcon}
          title="Stichtagsauskunft"
          description="Wer durfte ein bestimmtes Objekt in einem bestimmten Zeitraum lesen. Je Abfrage genau ein benanntes Objekt, höchstens 92 Tage Zeitraum und ein Anlass als Pflichtangabe. Jeder Abruf wird protokolliert."
        />

        <Stack spacing={2} sx={{ mb: 3 }}>
          <TextField
            select
            label="Objektart"
            value={objectType}
            onChange={(event) => setObjectType(event.target.value as AccessAsOfObjectType)}
          >
            <MenuItem value="KNOWLEDGE_LIBRARY">Wissensbibliothek</MenuItem>
            <MenuItem value="SPACE">Space</MenuItem>
          </TextField>
          <TextField
            label="Kennung des Objekts"
            value={objectId}
            onChange={(event) => setObjectId(event.target.value)}
          />
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
            label="Anlass"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            helperText="Pflichtangabe. Der Anlass steht im Protokolleintrag dieses Abrufs."
          />
          <Box>
            <Button variant="contained" disabled={!canSubmit} onClick={() => void submit(0)}>
              Auskunft erstellen
            </Button>
          </Box>
        </Stack>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {result && (
          <>
            <Typography sx={{ mb: 1 }}>
              {result.objectName ?? 'Gelöschtes Objekt'} — {result.totalElements}{' '}
              {result.totalElements === 1 ? 'Zugriffszeitraum' : 'Zugriffszeiträume'}
            </Typography>
            {result.beyondRetention && (
              <Alert severity="warning" sx={{ mb: 2 }}>
                Der angefragte Zeitraum beginnt vor dem Ende der Aufbewahrungsfrist (
                {formatInstant(result.retentionCutoff, 'unbekannt')}). Was dort fehlt, ist nicht
                mehr nachgewiesen — es ist kein Beleg dafür, dass niemand Zugriff hatte.
              </Alert>
            )}
            {result.sourcesNotCovered.length > 0 && (
              <Alert severity="info" sx={{ mb: 2 }}>
                Diese Auskunft deckt noch nicht jede Rechtequelle ab. Nicht nachgewiesen sind:{' '}
                {result.sourcesNotCovered.map((source) => SOURCE_LABELS[source]).join('; ')}. Eine
                leere oder kurze Liste ist deshalb kein Beleg dafür, dass niemand zugreifen konnte.
              </Alert>
            )}
            {result.entries.length === 0 ? (
              <Typography sx={{ color: 'text.secondary' }}>
                Für diesen Zeitraum ist über die nachgewiesenen Rechtequellen kein Zugriff
                verzeichnet. Die oben genannten Quellen sind dabei nicht berücksichtigt.
              </Typography>
            ) : (
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Person</TableCell>
                    <TableCell>Grundlage</TableCell>
                    <TableCell>Gruppe</TableCell>
                    <TableCell>Rolle</TableCell>
                    <TableCell>Von</TableCell>
                    <TableCell>Bis</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {result.entries.map((entry, index) => (
                    <TableRow key={`${entry.userId ?? 'alle'}-${entry.validFrom}-${index}`}>
                      <TableCell>{entry.userName ?? 'Alle Konten der Organisation'}</TableCell>
                      <TableCell>{BASIS_LABELS[entry.basis]}</TableCell>
                      <TableCell>
                        {entry.groupName ?? (entry.groupId ? 'Gelöschte Gruppe' : '—')}
                      </TableCell>
                      <TableCell>{entry.assetRole ?? entry.spaceRole ?? '—'}</TableCell>
                      <TableCell>{formatInstant(entry.validFrom, '—')}</TableCell>
                      <TableCell>{formatInstant(entry.validTo, 'noch wirksam')}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            {result.totalPages > 1 && (
              <Stack direction="row" spacing={2} sx={{ mt: 2, alignItems: 'center' }}>
                <Button
                  disabled={result.page === 0 || isLoading}
                  onClick={() => void submit(result.page - 1)}
                >
                  Zurück
                </Button>
                <Typography>
                  Seite {result.page + 1} von {result.totalPages}
                </Typography>
                <Button
                  disabled={result.page + 1 >= result.totalPages || isLoading}
                  onClick={() => void submit(result.page + 1)}
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
