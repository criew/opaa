import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import TextField from '@mui/material/TextField'
import type { LibraryFolderListItem } from '@/shared/api/types'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

interface RenameFolderDialogProps {
  open: boolean
  folder: LibraryFolderListItem | null
  onClose: () => void
  onRename: (name: string) => Promise<void>
}

// Renames a folder; the field starts with its current name, a failed rename shows in the dialog.
export default function RenameFolderDialog({
  open,
  folder,
  onClose,
  onRename,
}: RenameFolderDialogProps) {
  const [name, setName] = useState(folder?.name ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit() {
    if (!name.trim()) return
    setSaving(true)
    setError(null)
    try {
      await onRename(name.trim())
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ordner konnte nicht umbenannt werden')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Ordner umbenennen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Box sx={{ pt: 0.5 }}>
          <FieldLabel htmlFor="rename-folder-name">Ordnername</FieldLabel>
          <TextField
            id="rename-folder-name"
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
          {saving ? 'Wird umbenannt …' : 'Umbenennen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
