import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import FieldLabel from './wizard/FieldLabel'
import { useGroupStore } from '../stores/groupStore'

interface CreateGroupDialogProps {
  open: boolean
  onClose: () => void
  onCreated: () => void
}

export default function CreateGroupDialog({ open, onClose, onCreated }: CreateGroupDialogProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const createNewGroup = useGroupStore((s) => s.createNewGroup)

  function handleClose() {
    if (submitting) return
    setName('')
    setDescription('')
    setError(null)
    onClose()
  }

  async function handleCreate() {
    const trimmedName = name.trim()
    if (!trimmedName) {
      setError('Name ist erforderlich')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await createNewGroup(trimmedName, description.trim())
      setName('')
      setDescription('')
      onCreated()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Gruppe konnte nicht erstellt werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Gruppe erstellen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Box sx={{ mt: 1 }}>
          <FieldLabel htmlFor="create-group-name">Name</FieldLabel>
          <TextField
            // Focus moves to the first field of a dialog the user just opened (WAI-ARIA APG dialog
            // pattern); re-verified with a screenreader in the closing audit, see #598.
            // eslint-disable-next-line jsx-a11y-x/no-autofocus
            autoFocus
            id="create-group-name"
            size="small"
            fullWidth
            value={name}
            onChange={(e) => setName(e.target.value)}
            slotProps={{ htmlInput: { maxLength: 255 } }}
          />
        </Box>
        <Box sx={{ mt: 2 }}>
          <FieldLabel htmlFor="create-group-description">Beschreibung (optional)</FieldLabel>
          <TextField
            id="create-group-description"
            size="small"
            fullWidth
            multiline
            minRows={2}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button onClick={handleCreate} variant="contained" disabled={submitting || !name.trim()}>
          {submitting ? 'Wird erstellt …' : 'Erstellen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
