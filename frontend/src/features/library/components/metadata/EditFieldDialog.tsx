import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { updateLibraryMetadataField } from '../../api/libraryMetadataApi'
import type { LibraryMetadataFieldResponse } from '@/shared/api/types'
import ChangeImpactNotice from './ChangeImpactNotice'

/**
 * Label and Wirkstellen of an existing field. Type, key and value list stay -
 * the type is what every stored value was checked against, and the value list has its own
 * operations with the confirmed mapping.
 */
export default function EditFieldDialog({
  libraryId,
  field,
  onClose,
  onSaved,
  onError,
  onDelete,
}: {
  libraryId: string
  field: LibraryMetadataFieldResponse
  onClose: () => void
  onSaved: () => void
  onError: (message: string) => void
  /** Removing the field starts here, so the row keeps only „Ändern“ at its edge. */
  onDelete?: () => void
}) {
  // Seeded from the field once; the caller remounts this dialog per field (key), so there is no
  // effect that copies props into state.
  const [label, setLabel] = useState(field.label)
  const [filter, setFilter] = useState(field.filter)
  const [contextPrefix, setContextPrefix] = useState(field.contextPrefix)

  const noEffect = !filter && !contextPrefix

  async function submit() {
    try {
      await updateLibraryMetadataField(libraryId, field.fieldKey, {
        label,
        filter,
        contextPrefix,
      })
      onSaved()
    } catch (err) {
      onError(err instanceof Error ? err.message : 'Das Feld konnte nicht geändert werden')
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Metadatenfeld bearbeiten</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            size="small"
            label="Feldname"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
          />
          <FormControlLabel
            control={<Checkbox checked={filter} onChange={(e) => setFilter(e.target.checked)} />}
            label="Wirkt im Filter"
          />
          <FormControlLabel
            control={
              <Checkbox
                checked={contextPrefix}
                onChange={(e) => setContextPrefix(e.target.checked)}
              />
            }
            label="Wirkt im Kontextpräfix"
          />
          {noEffect && (
            <Alert severity="warning">
              Jedes Feld muss mindestens im Filter oder im Kontextpräfix wirken.
            </Alert>
          )}
          {contextPrefix !== field.contextPrefix && (
            <ChangeImpactNotice
              libraryId={libraryId}
              fieldKey={field.fieldKey}
              change={contextPrefix ? 'CONTEXT_PREFIX_ENABLED' : 'CONTEXT_PREFIX_DISABLED'}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        {onDelete && (
          <Button color="error" onClick={onDelete} sx={{ mr: 'auto' }}>
            Feld löschen
          </Button>
        )}
        <Button onClick={onClose}>Abbrechen</Button>
        <Button variant="contained" disabled={noEffect} onClick={() => void submit()}>
          Speichern
        </Button>
      </DialogActions>
    </Dialog>
  )
}
