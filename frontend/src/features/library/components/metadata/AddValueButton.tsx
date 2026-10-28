import { useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { TextAction } from '@/shared/ui/DetailSection'
import { addLibraryMetadataFieldValue } from '../../api/libraryMetadataApi'

/** „Wert ergänzen": adds one value to the list of a SELECT field. */
export default function AddValueButton({
  libraryId,
  fieldKey,
  onAdded,
  onError,
}: {
  libraryId: string
  fieldKey: string
  onAdded: () => void
  onError: (message: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [label, setLabel] = useState('')

  async function submit() {
    try {
      await addLibraryMetadataFieldValue(libraryId, fieldKey, { code, label })
      setOpen(false)
      setCode('')
      setLabel('')
      onAdded()
    } catch (err) {
      onError(err instanceof Error ? err.message : 'Der Wert konnte nicht ergänzt werden')
    }
  }

  return (
    <>
      <TextAction onClick={() => setOpen(true)}>Wert ergänzen</TextAction>
      <Dialog open={open} onClose={() => setOpen(false)}>
        <DialogTitle>Wert ergänzen</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              size="small"
              label="Code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
            <TextField
              size="small"
              label="Bezeichnung"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Abbrechen</Button>
          <Button variant="contained" onClick={() => void submit()}>
            Ergänzen
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
