import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import TextField from '@mui/material/TextField'

interface CreateWorkspaceDialogProps {
  open: boolean
  onClose: () => void
  onCreated: (workspaceId: string) => void
}

export default function CreateWorkspaceDialog({
  open,
  onClose,
  onCreated,
}: CreateWorkspaceDialogProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

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
      const { useWorkspaceStore } = await import('../stores/workspaceStore')
      const workspaceId = await useWorkspaceStore
        .getState()
        .createNewWorkspace(trimmedName, description.trim())
      setName('')
      setDescription('')
      onCreated(workspaceId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Workspace konnte nicht erstellt werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Workspace erstellen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <TextField
          autoFocus
          label="Name"
          fullWidth
          required
          value={name}
          onChange={(e) => setName(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 255 } }}
          sx={{ mt: 1 }}
        />
        <TextField
          label="Beschreibung"
          fullWidth
          multiline
          minRows={2}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 2000 } }}
          sx={{ mt: 2 }}
        />
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
