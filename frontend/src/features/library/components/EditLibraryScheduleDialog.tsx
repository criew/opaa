import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import type { LibrarySchedule } from '@/shared/api/types'
import { useLibraryStore } from '../stores/libraryStore'
import LibraryScheduleForm from './LibraryScheduleForm'
import {
  scheduleUpdateFrom,
  scheduleValuesFrom,
  validateScheduleValues,
} from '../utils/librarySchedule'

interface EditLibraryScheduleDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  schedule: LibrarySchedule | null | undefined
  // KnowledgeLibraryService#updateLibrary overwrites name/description unconditionally when
  // present in the request - this dialog only touches the schedule, so the current values must
  // be resent unchanged rather than omitted.
  library: {
    name: string
    description?: string | null
  }
}

/**
 * The Bearbeiten-Weg of the Zeitplan. The fields themselves live in {@link
 * LibraryScheduleForm}, which the Anlage-Assistent uses in its own Betriebsart; this dialog only
 * adds the save.
 */
export default function EditLibraryScheduleDialog({
  open,
  onClose,
  libraryId,
  schedule,
  library,
}: EditLibraryScheduleDialogProps) {
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)

  const [values, setValues] = useState(() => scheduleValuesFrom(schedule))
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleClose() {
    if (submitting) return
    onClose()
  }

  async function handleSave() {
    const validationError = validateScheduleValues(values)
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
        // Deliberately no source configuration field set - mirrors LibraryDetailPage's own
        // master data form: the backend leaves the stored source configuration unchanged
        // as long as none of its fields is present in the request.
        ...scheduleUpdateFrom(values),
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
