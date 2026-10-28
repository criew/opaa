import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  ConnectionLifecycleSettingKey,
  ConnectionLifecycleSettingResponse,
} from '@/shared/api/types'
import {
  getConnectionLifecycleSettings,
  updateConnectionLifecycleSetting,
} from '../api/connectionLifecycleApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import RetentionPauseNotice from '@/shared/ui/feedback/RetentionPauseNotice'

interface SettingText {
  name: string
  fieldLabel: string
  unit: string
  effect: string
}

/** What the administration reads about each setting; the values come from the server. */
const TEXTS: Record<ConnectionLifecycleSettingKey, SettingText> = {
  INACTIVITY_THRESHOLD_DAYS: {
    name: 'Inaktivitätsschwelle',
    fieldLabel: 'Inaktivitätsschwelle in Tagen',
    unit: ' Tage',
    effect:
      'Wer sich so lange nicht angemeldet hat, dessen verbundene Konten ruhen; gelöscht wird nichts. Eine längere Schwelle hält die Zugangsdaten abwesender Personen länger nutzbar.',
  },
  PRIVATE_LIBRARY_DELETION_DAYS: {
    name: 'Löschfrist privater Bibliotheken',
    fieldLabel: 'Löschfrist privater Bibliotheken in Tagen',
    unit: ' Tage',
    effect:
      'So lange bleiben die privaten Bibliotheken einer deaktivierten Person, bevor der tägliche Löschlauf sie entfernt. Eine Änderung gilt auch für Fristen, die schon laufen; eine kürzere löscht früher.',
  },
  EXPIRED_CONNECTION_WARNING_THRESHOLD: {
    name: 'Warnschwelle abgelaufener Konten',
    fieldLabel: 'Warnschwelle abgelaufener Konten',
    unit: '',
    effect:
      'Ab so vielen abgelaufenen verbundenen Konten auf einem Zugang zeigt die Übersicht „Viele Konten abgelaufen“.',
  },
}

/** Typed value, whole numbers within the bounds only; anything else is refused before sending. */
function parseValue(draft: string, entry: ConnectionLifecycleSettingResponse): number | null {
  const text = draft.trim()
  if (!/^\d+$/.test(text)) return null
  const value = Number(text)
  return value >= entry.minValue && value <= entry.maxValue ? value : null
}

interface SettingRowProps {
  entry: ConnectionLifecycleSettingResponse
  minimumGroupSize: number
  onSaved: (saved: ConnectionLifecycleSettingResponse) => void
}

/** One setting; a failed save is told at its own field and kept until the next edit. */
function SettingRow({ entry, minimumGroupSize, onSaved }: SettingRowProps) {
  const text = TEXTS[entry.setting]
  const [draft, setDraft] = useState(String(entry.value))
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const value = parseValue(draft, entry)
  const changed = value !== null && value !== entry.value
  const rangeError = `Bitte eine ganze Zahl von ${entry.minValue} bis ${entry.maxValue} eingeben.`

  async function save(next: number | null) {
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateConnectionLifecycleSetting(entry.setting, next)
      onSaved(saved)
      notify(`${text.name}: jetzt ${saved.value}${text.unit}.`, 'success')
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : 'Der Wert konnte nicht gespeichert werden.'
      setSaveError(message)
      notify(`${text.name} nicht gespeichert: ${message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  const paused = entry.pausedBy.length > 0
  const endsPause = paused
    ? 'Damit endet die Pause des Löschens: Danach gilt der angezeigte Wert als eigener Wert der Verwaltung, und der nächste tägliche Lauf löscht private Bibliotheken, deren Frist danach abgelaufen ist. '
    : ''

  /** A shorter deletion period erases private libraries earlier, running periods included. */
  function earlierErasure(next: number): string {
    return entry.setting === 'PRIVATE_LIBRARY_DELETION_DAYS' && next < entry.value
      ? 'Private Bibliotheken deaktivierter Personen werden früher als bisher gelöscht, auch bei schon laufenden Fristen. '
      : ''
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !changed || value === null) return
    const confirmed = await confirmAction({
      question: `${text.name} auf ${value} ändern?`,
      consequence: `${endsPause}${earlierErasure(value)}${text.effect} Die Änderung gilt sofort auf allen Instanzen und steht im Revisionsprotokoll.`,
      confirmLabel: 'Wert ändern',
      tone: paused || earlierErasure(value) ? 'danger' : 'caution',
    })
    if (!confirmed) return
    await save(value)
  }

  async function handleConfirmShown() {
    if (busy) return
    const confirmed = await confirmAction({
      question: `${text.name} auf ${entry.value} festlegen?`,
      consequence: `${endsPause}Die Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Wert festlegen',
      tone: 'danger',
    })
    if (!confirmed) return
    await save(entry.value)
  }

  async function handleReset() {
    if (busy) return
    const confirmed = await confirmAction({
      question: `${text.name} auf die Werkseinstellung von ${entry.defaultValue} zurücksetzen?`,
      consequence: `${earlierErasure(entry.defaultValue)}${text.effect} Die Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Werkseinstellung wiederherstellen',
      tone: earlierErasure(entry.defaultValue) ? 'danger' : 'caution',
    })
    if (!confirmed) return
    await save(null)
  }

  const belowGroupSize =
    entry.setting === 'EXPIRED_CONNECTION_WARNING_THRESHOLD' && entry.value < minimumGroupSize

  return (
    <TableRow>
      <TableCell component="th" scope="row">
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {text.name}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
          {`${entry.minValue} bis ${entry.maxValue}${text.unit}`}
        </Typography>
      </TableCell>
      <TableCell>
        <Box
          component="form"
          onSubmit={(event) => void handleSubmit(event)}
          noValidate
          sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', flexWrap: 'wrap' }}
        >
          <TextField
            size="small"
            value={draft}
            onChange={(event) => {
              setDraft(event.target.value)
              setSaveError(null)
            }}
            error={value === null || saveError !== null}
            helperText={value === null ? rangeError : (saveError ?? undefined)}
            slotProps={{ htmlInput: { inputMode: 'numeric', 'aria-label': text.fieldLabel } }}
            sx={{ width: 120 }}
          />
          <BusyButton
            type="submit"
            variant="outlined"
            size="small"
            busy={busy}
            busyAnnouncement="Wert wird gespeichert"
            disabled={!changed && !busy}
            aria-label={`${text.name} speichern`}
            sx={{ mt: 0.25 }}
          >
            Speichern
          </BusyButton>
        </Box>
        <Box sx={{ mt: 1, maxWidth: 520 }}>
          <RetentionPauseNotice
            pauses={entry.pausedBy}
            confirmLabel="Angezeigten Wert festlegen"
            onConfirm={() => void handleConfirmShown()}
          />
        </Box>
        {belowGroupSize && (
          <Alert severity="warning" sx={{ mt: 1, maxWidth: 420 }}>
            {`Der Wert liegt unter der Mindestgruppengröße von ${minimumGroupSize}: Jeder Zugang für Personen zeigt dann schon bei „weniger als ${minimumGroupSize}“ die Warnung, auch ein leerer.`}
          </Alert>
        )}
      </TableCell>
      <TableCell>
        <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
          <Typography variant="body2">
            {entry.overridden
              ? `Eigener Wert (Werkseinstellung: ${entry.defaultValue})`
              : 'Werkseinstellung'}
          </Typography>
          {entry.overridden && (
            <Button
              variant="text"
              size="small"
              disabled={busy}
              onClick={() => void handleReset()}
              aria-label={`${text.name} auf die Werkseinstellung zurücksetzen`}
            >
              Werkseinstellung wiederherstellen
            </Button>
          )}
        </Stack>
      </TableCell>
    </TableRow>
  )
}

/**
 * The thresholds and periods of the connections: when a person's connections rest, when the
 * private libraries of a deactivated person are erased and from how many expired accounts a
 * profile warns. The server answers the values, defaults and bounds; none is derived here.
 */
export default function ConnectionLifecycleSection() {
  const [entries, setEntries] = useState<ConnectionLifecycleSettingResponse[] | null>(null)
  const [minimumGroupSize, setMinimumGroupSize] = useState(0)
  const [loadError, setLoadError] = useState<string | null>(null)
  const load = useCallback(
    () =>
      getConnectionLifecycleSettings()
        .then((loaded) => {
          setEntries(loaded.settings)
          setMinimumGroupSize(loaded.minimumGroupSize)
          setLoadError(null)
        })
        .catch((err: unknown) => {
          setLoadError(
            err instanceof Error
              ? err.message
              : 'Die Schwellen und Fristen der Verbindungen konnten nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  function replace(saved: ConnectionLifecycleSettingResponse) {
    setEntries((current) =>
      current === null
        ? current
        : current.map((entry) => (entry.setting === saved.setting ? saved : entry)),
    )
  }

  return (
    <PageSection
      title="Schwellen und Fristen der Verbindungen"
      description="Wann verbundene Konten abwesender Personen ruhen, wie lange private Bibliotheken deaktivierter Personen bleiben und ab wann die Übersicht vor abgelaufenen Konten warnt."
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
      ) : entries === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Schwellen und Fristen werden geladen …
        </Typography>
      ) : (
        <Table
          size="small"
          aria-label="Schwellen und Fristen der Verbindungen"
          sx={{ maxWidth: 860 }}
        >
          <TableHead>
            <TableRow>
              <TableCell>Einstellung</TableCell>
              <TableCell>Wert</TableCell>
              <TableCell>Gilt</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {entries.map((entry) => (
              <SettingRow
                key={`${entry.setting}-${entry.value}-${entry.overridden}`}
                entry={entry}
                minimumGroupSize={minimumGroupSize}
                onSaved={replace}
              />
            ))}
          </TableBody>
        </Table>
      )}
    </PageSection>
  )
}
