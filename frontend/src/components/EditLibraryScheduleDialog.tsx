import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import type { LibrarySchedule } from '../types/api'
import { useLibraryStore } from '../stores/libraryStore'
import LibraryScheduleForm from './library/LibraryScheduleForm'
import {
  scheduleUpdateFrom,
  scheduleValuesFrom,
  validateScheduleValues,
  type ConfluenceFullSyncRhythm,
} from '../utils/librarySchedule'

interface EditLibraryScheduleDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  schedule: LibrarySchedule | null | undefined
  /** Present only for a CONFLUENCE library - the dialog then offers the full-sync rhythm (#1200). */
  confluence?: ConfluenceFullSyncRhythm
  // KnowledgeLibraryService#updateLibrary overwrites name/description/listed unconditionally when
  // present in the request (see EditLibrarySourceDialog's identical reasoning) - this dialog only
  // touches the schedule, so the current values must be resent unchanged rather than omitted.
  library: {
    name: string
    description?: string | null
    listed: boolean
  }
}

/**
 * The Bearbeiten-Weg of the Zeitplan (#485). The fields themselves live in {@link
 * LibraryScheduleForm}, which the Anlage-Assistent uses in its own Betriebsart; this dialog only
 * adds the save.
 */
export default function EditLibraryScheduleDialog({
  open,
  onClose,
  libraryId,
  schedule,
  confluence,
  library,
}: EditLibraryScheduleDialogProps) {
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)

  const [values, setValues] = useState(() => scheduleValuesFrom(schedule, confluence))
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleClose() {
    if (submitting) return
    onClose()
  }

  async function handleSave() {
    const validationError = validateScheduleValues(values, confluence)
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await updateExistingLibrary(libraryId, {
        name: library.name,
        description: library.description ?? undefined,
        listed: library.listed,
        // Bewusst kein Quellkonfigurationsfeld gesetzt - mirrors LibraryDetailPage's own
        // Stammdaten-Formular: das Backend lässt die gespeicherte Quellkonfiguration unverändert,
        // solange keines ihrer Felder in der Anfrage vorhanden ist.
        sourceInsecureSsl: null,
        ...scheduleUpdateFrom(values, confluence),
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
          <LibraryScheduleForm
            idPrefix="library-schedule"
            values={values}
            onChange={(patch) => setValues((prev) => ({ ...prev, ...patch }))}
            confluence={confluence}
          />
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
