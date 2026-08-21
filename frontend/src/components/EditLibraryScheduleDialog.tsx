import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import type {
  LibrarySchedule,
  LibraryVisibility,
  ScheduleFrequency,
  ScheduleWeekday,
} from '../types/api'
import { useLibraryStore } from '../stores/libraryStore'
import {
  scheduleFrequencies,
  scheduleFrequencyLabel,
  scheduleWeekdays,
  scheduleWeekdayLabel,
} from '../utils/labels'

const DEFAULT_TIME = '03:00'

function timeStringToParts(time: string): { hour: number; minute: number } | null {
  const match = /^(\d{2}):(\d{2})$/.exec(time)
  if (!match) return null
  return { hour: Number(match[1]), minute: Number(match[2]) }
}

function partsToTimeString(
  hour: number | null | undefined,
  minute: number | null | undefined,
): string {
  if (hour == null || minute == null) return DEFAULT_TIME
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

interface EditLibraryScheduleDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  schedule: LibrarySchedule | null | undefined
  // KnowledgeLibraryService#updateLibrary overwrites name/description/visibility/listed
  // unconditionally when present in the request (see EditLibrarySourceDialog's identical
  // reasoning) - this dialog only touches the schedule, so the current values must be resent
  // unchanged rather than omitted.
  library: {
    name: string
    description?: string | null
    visibility: LibraryVisibility
    listed: boolean
  }
}

// #485: Zeitplan-Bearbeitung für Konnektorbibliotheken - feste Intervallstufen (aus/stündlich/
// täglich/wöchentlich), intern vom Backend als Cron-Ausdruck gespeichert (LibraryScheduleCodec).
// Freie Cron-Eingabe ist bewusst nicht Teil dieser Oberfläche.
export default function EditLibraryScheduleDialog({
  open,
  onClose,
  libraryId,
  schedule,
  library,
}: EditLibraryScheduleDialogProps) {
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)

  const [frequency, setFrequency] = useState<ScheduleFrequency>(schedule?.frequency ?? 'DISABLED')
  const [time, setTime] = useState(partsToTimeString(schedule?.hour, schedule?.minute))
  const [weekday, setWeekday] = useState<ScheduleWeekday>(schedule?.weekday ?? 'MONDAY')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleClose() {
    if (submitting) return
    onClose()
  }

  async function handleSave() {
    setError(null)
    const parts = frequency === 'DAILY' || frequency === 'WEEKLY' ? timeStringToParts(time) : null
    if ((frequency === 'DAILY' || frequency === 'WEEKLY') && !parts) {
      setError('Bitte eine gültige Uhrzeit angeben.')
      return
    }
    setSubmitting(true)
    try {
      await updateExistingLibrary(libraryId, {
        name: library.name,
        description: library.description ?? undefined,
        visibility: library.visibility,
        listed: library.listed,
        // Bewusst kein Quellkonfigurationsfeld gesetzt - mirrors LibraryDetailPage's own
        // Stammdaten-Formular: das Backend lässt die gespeicherte Quellkonfiguration
        // unverändert, solange keines ihrer Felder in der Anfrage vorhanden ist. Dieser Dialog
        // rührt nur den Zeitplan an.
        sourceInsecureSsl: null,
        schedule: {
          frequency,
          hour: parts?.hour ?? null,
          minute: parts?.minute ?? null,
          weekday: frequency === 'WEEKLY' ? weekday : undefined,
        },
      })
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Zeitplan konnte nicht gespeichert werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Zeitplan bearbeiten</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <FormControl size="small" fullWidth>
            <InputLabel id="library-schedule-frequency-label">Zeitplan</InputLabel>
            <Select
              labelId="library-schedule-frequency-label"
              label="Zeitplan"
              value={frequency}
              onChange={(e) => setFrequency(e.target.value as ScheduleFrequency)}
            >
              {scheduleFrequencies.map((option) => (
                <MenuItem key={option} value={option}>
                  {scheduleFrequencyLabel(option)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          {(frequency === 'DAILY' || frequency === 'WEEKLY') && (
            <TextField
              label="Uhrzeit"
              type="time"
              value={time}
              onChange={(e) => setTime(e.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
              size="small"
            />
          )}

          {frequency === 'WEEKLY' && (
            <FormControl size="small" fullWidth>
              <InputLabel id="library-schedule-weekday-label">Wochentag</InputLabel>
              <Select
                labelId="library-schedule-weekday-label"
                label="Wochentag"
                value={weekday}
                onChange={(e) => setWeekday(e.target.value as ScheduleWeekday)}
              >
                {scheduleWeekdays.map((option) => (
                  <MenuItem key={option} value={option}>
                    {scheduleWeekdayLabel(option)}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button onClick={() => void handleSave()} variant="contained" disabled={submitting}>
          {submitting ? 'Wird gespeichert …' : 'Speichern'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
