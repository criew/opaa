import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormField from '@/shared/ui/form/FormField'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
  const [nameMissing, setNameMissing] = useState(false)

  async function handleSubmit() {
    if (!name.trim()) {
      setNameMissing(true)
      return
    }
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
    <FormDialog
      open={open}
      title="Neuer Ordner"
      submitLabel="Anlegen"
      onSubmit={() => void handleSubmit()}
      onClose={onClose}
      busy={saving}
      busyAnnouncement="Der Ordner wird angelegt"
    >
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Box sx={{ pt: 0.5 }}>
        <FormField
          label="Ordnername"
          id="new-folder-name"
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
