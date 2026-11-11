import Typography from '@mui/material/Typography'
import type { LibraryMetadataFieldResponse } from '@/shared/api/types'
import ChangeImpactNotice from './ChangeImpactNotice'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
    <FormDialog
      open={deleteField != null}
      width="confirm"
      tone="danger"
      title={`„${deleteField?.label ?? ''}“ löschen?`}
      submitLabel="Löschen"
      onSubmit={() => {
        // the consequence is not known yet: nothing to confirm
        if (deleteUsage != null) onConfirm()
      }}
      onClose={onClose}
    >
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
    </FormDialog>
  )
}
