import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import type { LibraryFolderListItem } from '@/shared/api/types'
import FormField from '@/shared/ui/form/FormField'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
  const [nameMissing, setNameMissing] = useState(false)

  async function handleSubmit() {
    if (!name.trim()) {
      setNameMissing(true)
      return
    }
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
    <FormDialog
      open={open}
      title={folder ? `„${folder.name}“ umbenennen` : 'Ordner umbenennen'}
      submitLabel="Umbenennen"
      onSubmit={() => void handleSubmit()}
      onClose={onClose}
      busy={saving}
      busyAnnouncement="Der Ordner wird umbenannt"
    >
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Box sx={{ pt: 0.5 }}>
        <FormField
          label="Ordnername"
          id="rename-folder-name"
          fullWidth
          size="small"
          value={name}
          onChange={(e) => {
            setName(e.target.value)
            setNameMissing(false)
          }}
          error={nameMissing}
          helperText={nameMissing ? 'Bitte einen Namen angeben.' : undefined}
        />
      </Box>
    </FormDialog>
  )
}
