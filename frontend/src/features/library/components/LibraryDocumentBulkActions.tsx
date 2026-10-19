import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { MAX_BULK_DELETE } from '../hooks/useBulkDocumentActions'
import type { BulkDocumentActions } from '../hooks/useBulkDocumentActions'

interface LibraryDocumentBulkActionsProps {
  bulk: BulkDocumentActions
  selectedDocumentIds: string[]
  metadataEnabled: boolean
  canDelete: boolean
}

/** The buttons of the selection toolbar: "Feld setzen" and "Löschen" with its ceiling. */
export default function LibraryDocumentBulkActions({
  bulk,
  selectedDocumentIds,
  metadataEnabled,
  canDelete,
}: LibraryDocumentBulkActionsProps) {
  const { setBulkDialogOpen, tooManySelectedToDelete, handleBulkDelete } = bulk

  return (
    <>
      {metadataEnabled && (
        <Button
          size="small"
          variant="outlined"
          disabled={selectedDocumentIds.length === 0}
          onClick={() => setBulkDialogOpen(true)}
        >
          Feld setzen
        </Button>
      )}
      {canDelete && (
        <>
          <Button
            size="small"
            variant="outlined"
            color="error"
            disabled={selectedDocumentIds.length === 0 || tooManySelectedToDelete}
            onClick={() => void handleBulkDelete()}
          >
            Löschen
          </Button>
          {/* The ceiling is named rather than applied silently: cutting the selection down
              to 200 would delete a different set than the one the person chose. */}
          {tooManySelectedToDelete && (
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              Höchstens {MAX_BULK_DELETE} Dokumente auf einmal löschen — bitte die Auswahl
              verkleinern.
            </Typography>
          )}
        </>
      )}
    </>
  )
}
