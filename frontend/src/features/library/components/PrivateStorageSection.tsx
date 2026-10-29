import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  MaskedNumber,
  PrivateStorageQuotaResponse,
  PrivateStorageSummaryResponse,
} from '@/shared/api/types'
import {
  getPrivateStorageQuota,
  getPrivateStorageSummary,
  updatePrivateStorageQuota,
} from '../api/privateStorageApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { formatFileSize } from '@/shared/utils/labels'
import { limitDraft, parseLimit } from '../utils/storageLimit'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'

/** A size in words; 0 is unlimited. */
function sizeOrUnlimited(bytes: number): string {
  return bytes === 0 ? 'unbegrenzt' : formatFileSize(bytes)
}

/** A limit in words; 0 is unlimited. */
function limitLabel(bytes: number): string {
  return bytes === 0 ? 'unbegrenzt' : `${formatFileSize(bytes)} je Person`
}

/** The run categories of a private library in words; an unknown one stays as the server names it. */
const RUN_CATEGORY_LABELS: Record<string, string> = {
  QUOTA_EXHAUSTED: 'Speicherkontingent erschöpft',
  CREDENTIALS_REJECTED: 'Anmeldung abgelehnt',
  NOT_CONNECTED: 'Verbindung getrennt',
  EXPIRED: 'Anmeldung abgelaufen',
  DORMANT: 'Ruhend',
  OWNER_DEACTIVATED: 'Konto deaktiviert',
  TARGET_OUTSIDE_PROFILE: 'Ziel weicht ab',
  ACCESS_REMOVED: 'Zugang nicht mehr nutzbar',
  TYPE_LOCKED: 'Quellart gesperrt',
  PROFILE_LOCKED: 'Zugang gesperrt',
  PROFILE_REQUIRED: 'Zugang erforderlich',
}

/**
 * A masked count as the server tells it: the value, „weniger als N", or „nicht ausgewiesen".
 * Nothing is derived from other numbers of the answer.
 */
function maskedCount(number: MaskedNumber): string {
  if (number.value != null) return number.value.toLocaleString('de-DE')
  if (number.fewerThanPersons != null) return `weniger als ${number.fewerThanPersons}`
  return 'nicht ausgewiesen'
}

/** A masked byte sum: below the minimum group size there is no amount, only the reason. */
function maskedBytes(number: MaskedNumber): string {
  if (number.value != null) return formatFileSize(number.value)
  if (number.fewerThanPersons != null) {
    return `nicht ausgewiesen (weniger als ${number.fewerThanPersons} Personen)`
  }
  return 'nicht ausgewiesen'
}

function QuotaForm({
  current,
  onSaved,
}: {
  current: PrivateStorageQuotaResponse
  onSaved: (saved: PrivateStorageQuotaResponse) => void
}) {
  const [unlimited, setUnlimited] = useState(current.quotaBytes === 0)
  const [draft, setDraft] = useState(current.quotaBytes === 0 ? '' : limitDraft(current.quotaBytes))
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const parsed = unlimited ? { bytes: 0, error: null } : parseLimit(draft)
  const valid = parsed.error === null
  const quotaBytes = parsed.bytes
  const changed = valid && quotaBytes !== current.quotaBytes

  async function save(next: number | null) {
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updatePrivateStorageQuota(next)
      onSaved(saved)
      notify(
        `Das Speicherkontingent privater Bibliotheken ist jetzt ${limitLabel(saved.quotaBytes)}.`,
        'success',
      )
    } catch (err: unknown) {
      setSaveError(
        err instanceof Error ? err.message : 'Die Grenze konnte nicht gespeichert werden.',
      )
    } finally {
      setBusy(false)
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !changed) return
    const confirmed = await confirmAction({
      question: `Speicherkontingent privater Bibliotheken auf ${limitLabel(quotaBytes)} ändern?`,
      consequence:
        quotaBytes === 0
          ? 'Private Bibliotheken nehmen dann ohne Grenze auf, ab dem nächsten aufgenommenen Dokument; nur das Kontingent je Bibliothek gilt weiter. Die Änderung steht im Revisionsprotokoll.'
          : 'Die Grenze gilt für alle privaten Bibliotheken einer Person zusammen, ab dem nächsten aufgenommenen Dokument. Bereits Gespeichertes bleibt; wer über der Grenze liegt, nimmt nichts mehr auf. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Grenze ändern',
      tone: 'caution',
    })
    if (!confirmed) return
    await save(quotaBytes)
  }

  async function handleReset() {
    if (busy) return
    const confirmed = await confirmAction({
      question: `Speicherkontingent privater Bibliotheken zur Werkseinstellung von ${sizeOrUnlimited(current.defaultQuotaBytes)} zurückkehren?`,
      consequence:
        'Es gilt dann wieder die Werkseinstellung. Bereits Gespeichertes bleibt. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Werkseinstellung wiederherstellen',
      tone: 'caution',
    })
    if (!confirmed) return
    await save(null)
  }

  return (
    <Box component="form" onSubmit={(event) => void handleSubmit(event)} noValidate>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ alignItems: { sm: 'flex-start' } }}
      >
        <TextField
          label="Grenze je Person (GB)"
          size="small"
          value={draft}
          disabled={unlimited}
          onChange={(event) => {
            setDraft(event.target.value)
            setSaveError(null)
          }}
          error={!valid}
          helperText={parsed.error ?? 'Gilt über alle privaten Bibliotheken einer Person.'}
          slotProps={{ htmlInput: { inputMode: 'decimal' } }}
          sx={{ maxWidth: 240 }}
        />
        <FormControlLabel
          control={
            <Checkbox
              checked={unlimited}
              onChange={(event) => {
                setUnlimited(event.target.checked)
                setSaveError(null)
              }}
            />
          }
          label="Unbegrenzt"
        />
        <BusyButton
          type="submit"
          variant="outlined"
          busy={busy}
          busyAnnouncement="Grenze wird gespeichert"
          disabled={!changed && !busy}
          sx={{ mt: { sm: 0.25 } }}
        >
          Grenze speichern
        </BusyButton>
        {current.overridden && (
          <Button
            variant="text"
            disabled={busy}
            onClick={() => void handleReset()}
            sx={{ mt: { sm: 0.25 } }}
          >
            Werkseinstellung wiederherstellen
          </Button>
        )}
      </Stack>
      {saveError && (
        <Alert severity="error" role="alert" sx={{ mt: 2 }}>
          {saveError}
        </Alert>
      )}
    </Box>
  )
}

function Summary({ summary }: { summary: PrivateStorageSummaryResponse }) {
  return (
    <Box data-testid="private-storage-summary" sx={{ mt: 3 }}>
      <Typography variant="subtitle2" component="h3" sx={{ mb: 1 }}>
        Übersicht (nur Summen, ohne Namen)
      </Typography>
      <Stack spacing={0.5} sx={{ mb: 2 }}>
        <Typography variant="body2">
          Besitzerinnen privater Bibliotheken:{' '}
          <span data-testid="private-storage-owners">{maskedCount(summary.owners)}</span>
        </Typography>
        <Typography variant="body2">
          Belegter Speicher insgesamt:{' '}
          <span data-testid="private-storage-used">{maskedBytes(summary.usedBytes)}</span>
        </Typography>
      </Stack>

      {summary.profiles.length > 0 && (
        <Table size="small" aria-label="Belegter Speicher je Zugang" sx={{ mb: 2, maxWidth: 560 }}>
          <TableHead>
            <TableRow>
              <TableCell>Zugang</TableCell>
              <TableCell align="right">Belegter Speicher</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {summary.profiles.map((profile) => (
              <TableRow key={profile.profileId}>
                <TableCell>{profile.name}</TableCell>
                <TableCell align="right">{maskedBytes(profile.usedBytes)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {summary.runEnds.length > 0 && (
        <Table
          size="small"
          aria-label={`Laufabbrüche der letzten ${summary.runWindowDays} Tage je Ursache`}
          sx={{ mb: 2, maxWidth: 560 }}
        >
          <TableHead>
            <TableRow>
              <TableCell>Ursache</TableCell>
              <TableCell align="right">Läufe</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {summary.runEnds.map((entry) => (
              <TableRow key={entry.category}>
                <TableCell>{RUN_CATEGORY_LABELS[entry.category] ?? entry.category}</TableCell>
                <TableCell align="right">{maskedCount(entry.runs)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
        Kleine Zahlen erscheinen nur als „weniger als …“ oder „nicht ausgewiesen“, damit keine Zahl
        auf einzelne Personen schließen lässt. Den Verbrauch einer einzelnen Person zeigt OPAA der
        Verwaltung nicht.
      </Typography>
    </Box>
  )
}

/**
 * The house-wide storage quota across all private libraries of a person and the masked sums over
 * them. Every number of the overview is shown as the server tells it; none is derived here.
 */
export default function PrivateStorageSection() {
  const [quota, setQuota] = useState<PrivateStorageQuotaResponse | null>(null)
  const [summary, setSummary] = useState<PrivateStorageSummaryResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const load = useCallback(
    () =>
      Promise.all([getPrivateStorageQuota(), getPrivateStorageSummary()])
        .then(([loadedQuota, loadedSummary]) => {
          setQuota(loadedQuota)
          setSummary(loadedSummary)
          setLoadError(null)
        })
        .catch((err: unknown) => {
          setLoadError(
            err instanceof Error
              ? err.message
              : 'Das Speicherkontingent privater Bibliotheken konnte nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  return (
    <PageSection
      title="Speicher privater Bibliotheken"
      description="Die hausweite Grenze, die sich alle privaten Bibliotheken einer Person teilen, und wie viel sie insgesamt belegen."
    >
      {loadError ? (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => void load()}>
              Erneut laden
            </Button>
          }
        >
          {loadError}
        </Alert>
      ) : quota === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Speicherkontingent wird geladen …
        </Typography>
      ) : (
        <>
          <Typography variant="body2" sx={{ mb: 2 }} data-testid="private-storage-quota-current">
            {quota.overridden
              ? `Es gilt eine eigene Grenze: ${limitLabel(quota.quotaBytes)} (Werkseinstellung: ${sizeOrUnlimited(quota.defaultQuotaBytes)}).`
              : `Es gilt die Werkseinstellung: ${limitLabel(quota.quotaBytes)}.`}
          </Typography>
          <QuotaForm
            key={`${quota.quotaBytes}-${quota.overridden}`}
            current={quota}
            onSaved={setQuota}
          />
          {summary && <Summary summary={summary} />}
        </>
      )}
    </PageSection>
  )
}
