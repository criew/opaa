import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Typography from '@mui/material/Typography'
import type { LibraryMetadataFieldResponse } from '@/shared/api/types'
import ChangeImpactNotice from './ChangeImpactNotice'

interface DeleteFieldDialogProps {
  libraryId: string
  /** The field to delete; the dialog is open while it is set. */
  deleteField: LibraryMetadataFieldResponse | null
  /** Documents carrying a value of the field, null while it is still being determined. */
  deleteUsage: number | null
  onConfirm: () => void
  onClose: () => void
}

/** „Feld löschen": the field goes together with its values, once the Folgekosten stand. */
export default function DeleteFieldDialog({
  libraryId,
  deleteField,
  deleteUsage,
  onConfirm,
  onClose,
}: DeleteFieldDialogProps) {
  return (
    <Dialog open={deleteField != null} onClose={onClose}>
      <DialogTitle>Feld löschen</DialogTitle>
      <DialogContent>
        <Typography variant="body2">
          {deleteUsage == null
            ? 'Betroffene Dokumente werden ermittelt …'
            : `${deleteUsage} Dokument(e) tragen einen Wert für „${deleteField?.label}“. Mit dem Feld werden diese Werte und seine Werteliste entfernt.`}
        </Typography>
        {deleteField && (
          <ChangeImpactNotice
            libraryId={libraryId}
            fieldKey={deleteField.fieldKey}
            change="FIELD_REMOVED"
          />
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button
          color="error"
          variant="contained"
          disabled={deleteUsage == null}
          onClick={onConfirm}
        >
          Endgültig löschen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
