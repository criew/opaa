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
import type { FullSyncRhythmResponse } from '@/shared/api/types'
import { listFullSyncRhythms, updateFullSyncRhythm } from '../api/fullSyncRhythmApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'

const MIN_DAYS = 1
const MAX_DAYS = 365
const RANGE_ERROR = `Bitte eine ganze Zahl von ${MIN_DAYS} bis ${MAX_DAYS} eingeben.`

/** Source types whose libraries have no rhythm of their own: the value holds for all of them. */
const WITHOUT_OWN_RHYTHM = new Set(['NEXTCLOUD'])

/** Which libraries a change reaches, in words. */
function scopeOf(rhythm: FullSyncRhythmResponse): string {
  return WITHOUT_OWN_RHYTHM.has(rhythm.sourceType)
    ? `alle ${rhythm.displayName}-Bibliotheken`
    : `alle ${rhythm.displayName}-Bibliotheken ohne eigenen Rhythmus`
}

/** Days in words: „1 Tag“, „7 Tage“. */
function daysLabel(days: number): string {
  return days === 1 ? '1 Tag' : `${days} Tage`
}

/** Typed days, whole numbers from 1 to 365 only; anything else is refused before sending. */
function parseDays(draft: string): number | null {
  const text = draft.trim()
  if (!/^\d+$/.test(text)) return null
  const days = Number(text)
  return days >= MIN_DAYS && days <= MAX_DAYS ? days : null
}

interface RhythmRowProps {
  rhythm: FullSyncRhythmResponse
  onSaved: (saved: FullSyncRhythmResponse) => void
}

/** One source type; a failed save is told at its own field and kept until the next edit. */
function RhythmRow({ rhythm, onSaved }: RhythmRowProps) {
  const [draft, setDraft] = useState(String(rhythm.intervalDays))
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const name = rhythm.displayName
  const days = parseDays(draft)
  const changed = days !== null && days !== rhythm.intervalDays

  async function save(next: number | null) {
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateFullSyncRhythm(rhythm.sourceType, next)
      onSaved(saved)
      notify(
        `Der Vollabgleich-Rhythmus von ${name} ist jetzt ${daysLabel(saved.intervalDays)}.`,
        'success',
      )
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : 'Der Rhythmus konnte nicht gespeichert werden.'
      setSaveError(message)
      notify(`Rhythmus von ${name} nicht gespeichert: ${message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !changed || days === null) return
    const confirmed = await confirmAction({
      question: `Vollabgleich-Rhythmus von ${name} auf ${daysLabel(days)} ändern?`,
      consequence: `Gilt ab dem nächsten Lauf für ${scopeOf(rhythm)}. Ein kürzerer Rhythmus entfernt früher, was in der Quelle gelöscht wurde, und stellt mehr Anfragen an die Quelle. Die Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Rhythmus ändern',
      tone: 'caution',
    })
    if (!confirmed) return
    await save(days)
  }

  async function handleReset() {
    if (busy) return
    const confirmed = await confirmAction({
      question: `Vollabgleich-Rhythmus von ${name} auf die Werkseinstellung von ${daysLabel(rhythm.defaultIntervalDays)} zurücksetzen?`,
      consequence: `Gilt ab dem nächsten Lauf für ${scopeOf(rhythm)}. Die Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Werkseinstellung wiederherstellen',
      tone: 'caution',
    })
    if (!confirmed) return
    await save(null)
  }

  return (
    <TableRow>
      <TableCell component="th" scope="row">
        {name}
      </TableCell>
      <TableCell>
        <Box
          component="form"
          onSubmit={(event) => void handleSubmit(event)}
          noValidate
          sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', flexWrap: 'wrap' }}
        >
          <TextField
            label="Tage"
            size="small"
            value={draft}
            onChange={(event) => {
              setDraft(event.target.value)
              setSaveError(null)
            }}
            error={days === null || saveError !== null}
            helperText={days === null ? RANGE_ERROR : (saveError ?? undefined)}
            slotProps={{
              htmlInput: {
                inputMode: 'numeric',
                'aria-label': `Vollabgleich von ${name} alle … Tage`,
              },
            }}
            sx={{ width: 120 }}
          />
          <BusyButton
            type="submit"
            variant="outlined"
            size="small"
            busy={busy}
            busyAnnouncement="Rhythmus wird gespeichert"
            disabled={!changed && !busy}
            aria-label={`Rhythmus von ${name} speichern`}
            sx={{ mt: 0.25 }}
          >
            Speichern
          </BusyButton>
        </Box>
      </TableCell>
      <TableCell>
        <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
          <Typography variant="body2">
            {rhythm.overridden
              ? `Eigener Wert (Werkseinstellung: ${daysLabel(rhythm.defaultIntervalDays)})`
              : 'Werkseinstellung'}
          </Typography>
          {rhythm.overridden && (
            <Button
              variant="text"
              size="small"
              disabled={busy}
              onClick={() => void handleReset()}
              aria-label={`Rhythmus von ${name} auf die Werkseinstellung zurücksetzen`}
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
 * The installation-wide rhythm of the full sync per source type. A library without a rhythm of its
 * own follows it; the server answers the value in force and the factory default, none is derived.
 */
export default function FullSyncRhythmSection() {
  const [rhythms, setRhythms] = useState<FullSyncRhythmResponse[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const load = useCallback(
    () =>
      listFullSyncRhythms()
        .then((loaded) => {
          setRhythms(loaded)
          setLoadError(null)
        })
        .catch((err: unknown) => {
          setLoadError(
            err instanceof Error
              ? err.message
              : 'Der Vollabgleich-Rhythmus konnte nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  function replace(saved: FullSyncRhythmResponse) {
    setRhythms((current) =>
      current === null
        ? current
        : current.map((rhythm) => (rhythm.sourceType === saved.sourceType ? saved : rhythm)),
    )
  }

  return (
    <PageSection
      title="Vollabgleich-Rhythmus"
      description="Nach wie vielen Tagen eine Bibliothek ohne eigenen Rhythmus ihre Quelle wieder vollständig abgleicht; erst dann entfällt, was die Quelle nicht einzeln als gelöscht meldet."
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
      ) : rhythms === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Vollabgleich-Rhythmus wird geladen …
        </Typography>
      ) : (
        <Table size="small" aria-label="Vollabgleich-Rhythmus je Quellart" sx={{ maxWidth: 760 }}>
          <TableHead>
            <TableRow>
              <TableCell>Quellart</TableCell>
              <TableCell>Vollabgleich alle … Tage</TableCell>
              <TableCell>Gilt</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rhythms.map((rhythm) => (
              <RhythmRow
                key={`${rhythm.sourceType}-${rhythm.intervalDays}-${rhythm.overridden}`}
                rhythm={rhythm}
                onSaved={replace}
              />
            ))}
          </TableBody>
        </Table>
      )}
    </PageSection>
  )
}
