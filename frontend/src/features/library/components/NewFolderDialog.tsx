import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import TextField from '@mui/material/TextField'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

interface NewFolderDialogProps {
  open: boolean
  onClose: () => void
  onCreate: (name: string) => Promise<void>
}

// Creates a folder directly under the folder currently being browsed.
export default function NewFolderDialog({ open, onClose, onCreate }: NewFolderDialogProps) {
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit() {
    if (!name.trim()) return
    setSaving(true)
    setError(null)
    try {
      await onCreate(name.trim())
      onClose()
    } catch (err) {
      // Surfaces a 409 name conflict directly in the dialog, where the name is still there
      // to correct, rather than as a page-level alert.
      setError(err instanceof Error ? err.message : 'Ordner konnte nicht angelegt werden')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Neuer Ordner</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Box sx={{ pt: 0.5 }}>
          <FieldLabel htmlFor="new-folder-name">Ordnername</FieldLabel>
          <TextField
            id="new-folder-name"
            fullWidth
            size="small"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void handleSubmit()
            }}
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={saving || !name.trim()}
        >
          {saving ? 'Wird angelegt …' : 'Anlegen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
