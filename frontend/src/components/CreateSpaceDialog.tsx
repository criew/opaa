import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormHelperText from '@mui/material/FormHelperText'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import type { SpaceVisibility } from '../types/api'
import {
  spaceVisibilities,
  spaceVisibilityDescription,
  spaceVisibilityLabel,
} from '../utils/labels'

interface CreateSpaceDialogProps {
  open: boolean
  onClose: () => void
  onCreated: (spaceId: string) => void
}

export default function CreateSpaceDialog({ open, onClose, onCreated }: CreateSpaceDialogProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [visibility, setVisibility] = useState<SpaceVisibility>('PRIVATE')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleClose() {
    if (submitting) return
    setName('')
    setDescription('')
    setVisibility('PRIVATE')
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
      const { useSpaceStore } = await import('../stores/spaceStore')
      const spaceId = await useSpaceStore
        .getState()
        .createNewSpace(trimmedName, description.trim(), visibility)
      setName('')
      setDescription('')
      setVisibility('PRIVATE')
      onCreated(spaceId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Space konnte nicht erstellt werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Space erstellen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <TextField
          // Focus moves to the first field of a dialog the user just opened (WAI-ARIA APG dialog
          // pattern); re-verified with a screenreader in the closing audit, see #598.
          // eslint-disable-next-line jsx-a11y-x/no-autofocus
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
        <FormControl fullWidth sx={{ mt: 2 }}>
          <InputLabel id="create-space-visibility-label">Sichtbarkeit</InputLabel>
          <Select
            labelId="create-space-visibility-label"
            label="Sichtbarkeit"
            value={visibility}
            onChange={(e) => setVisibility(e.target.value as SpaceVisibility)}
            aria-describedby="create-space-visibility-helper"
          >
            {spaceVisibilities.map((option) => (
              <MenuItem key={option} value={option}>
                {spaceVisibilityLabel(option)}
              </MenuItem>
            ))}
          </Select>
          <FormHelperText id="create-space-visibility-helper">
            {spaceVisibilityDescription(visibility)}
          </FormHelperText>
        </FormControl>
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
