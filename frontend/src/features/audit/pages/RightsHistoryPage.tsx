import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import HistoryOutlinedIcon from '@mui/icons-material/HistoryOutlined'
import AreaPageCount from '@/shared/ui/AreaPageCount'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import { useAuthStore } from '@/shared/session/authStore'
import { getAccessAsOf } from '../api/rightsHistoryApi'
import type {
  AccessAsOfObjectType,
  AccessAsOfPage,
  AccessAsOfSource,
  AccessBasis,
} from '@/shared/api/types'
import { contentWidth, formWidth } from '@/shared/theme/tokens'
import { formatProtocolTime, MISSING_VALUE } from '@/shared/utils/formatDate'

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
  ACCOUNT_STATE:
    'Kontozustand vor Einführung seines vollständigen Nachweises (bis dahin nur Sperren aus dem Verzeichnisabgleich, nicht Sperre oder Ablauf eines lokalen Kontos oder ein abgeschalteter Anmeldeanbieter)',
}

function formatInstant(value: string | null | undefined, fallback: string): string {
  if (!value) return fallback
  return formatProtocolTime(value)
}

function toInstant(localValue: string): string {
  return new Date(localValue).toISOString()
}

/**
 * The point-in-time report from the rights history: who was allowed to read exactly this object
 * in exactly this period. There is deliberately no search across several objects and no entry
 * via a person — both would assemble a rights profile without authorization. The time window is
 * mandatory, so is the reason, and every request is logged afterwards.
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
      <Box sx={{ maxWidth: contentWidth.list }}>
        <AreaPageHeader
          icon={HistoryOutlinedIcon}
          title="Stichtagsauskunft"
          description="Wer durfte ein bestimmtes Objekt in einem bestimmten Zeitraum lesen. Je Abfrage genau ein benanntes Objekt, höchstens 92 Tage Zeitraum und ein Anlass als Pflichtangabe. Jeder Abruf wird protokolliert."
        />

        <Stack spacing={2} sx={{ mb: 3, maxWidth: formWidth.column }}>
          <FormField
            select
            label="Objektart"
            value={objectType}
            onChange={(event) => setObjectType(event.target.value as AccessAsOfObjectType)}
          >
            <MenuItem value="KNOWLEDGE_LIBRARY">Wissensbibliothek</MenuItem>
            <MenuItem value="SPACE">Space</MenuItem>
          </FormField>
          <FormField
            label="Kennung des Objekts"
            value={objectId}
            onChange={(event) => setObjectId(event.target.value)}
          />
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
            <AreaPageCount
              total={result.totalElements}
              unit={['Zugriffszeitraum', 'Zugriffszeiträume']}
            />
            <Typography sx={{ mb: 1, fontWeight: 600 }}>
              {result.objectName ?? 'Gelöschtes Objekt'}
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
                Diese Auskunft deckt noch nicht jede Rechtequelle ab. Nicht nachgewiesen{' '}
                {result.sourcesNotCovered.length === 1 ? 'ist' : 'sind'}:{' '}
                {result.sourcesNotCovered
                  .map((source) => SOURCE_LABELS[source] ?? source)
                  .join('; ')}
                . Eine leere oder kurze Liste ist deshalb kein Beleg dafür, dass niemand zugreifen
                konnte.
              </Alert>
            )}
            {result.entries.length === 0 ? (
              <Typography sx={{ color: 'text.secondary' }}>
                Für diesen Zeitraum ist über die nachgewiesenen Rechtequellen kein Zugriff
                verzeichnet. Die oben genannten Quellen sind dabei nicht berücksichtigt.
              </Typography>
            ) : (
              <AdminList
                label="Zugriffszeiträume"
                rows={result.entries}
                rowKey={(entry) =>
                  `${entry.userId ?? 'alle'}-${entry.groupId ?? ''}-${entry.basis}-${entry.validFrom}`
                }
                rowLabel={(entry) => entry.userName ?? 'Alle Konten der Organisation'}
                columns={[
                  {
                    id: 'person',
                    head: 'Person',
                    cell: (entry) => (
                      <ListText strong>{entry.userName ?? 'Alle Konten der Organisation'}</ListText>
                    ),
                  },
                  { id: 'basis', head: 'Grundlage', cell: (entry) => BASIS_LABELS[entry.basis] },
                  {
                    id: 'group',
                    head: 'Gruppe',
                    cell: (entry) => entry.groupName ?? (entry.groupId ? 'Gelöschte Gruppe' : '—'),
                  },
                  {
                    id: 'role',
                    head: 'Rolle',
                    width: '12%',
                    cell: (entry) => entry.assetRole ?? entry.spaceRole ?? '—',
                  },
                  {
                    id: 'from',
                    head: 'Von',
                    width: 168,
                    cell: (entry) => formatInstant(entry.validFrom, MISSING_VALUE),
                  },
                  {
                    id: 'to',
                    head: 'Bis',
                    width: 168,
                    cell: (entry) => formatInstant(entry.validTo, 'noch wirksam'),
                  },
                ]}
                pager={{
                  pageCount: result.totalPages,
                  page: result.page,
                  disabled: isLoading,
                  onPage: (page) => void submit(page),
                }}
              />
            )}
          </>
        )}
      </Box>
    </Box>
  )
}
