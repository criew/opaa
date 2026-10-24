import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import type { LibraryFolderListItem } from '@/shared/api/types'
import { useDocumentStore } from '../stores/documentStore'

interface LibraryDocumentsNoticesProps {
  libraryId: string
  newFolderDialogOpen: boolean
  renameFolderTarget: LibraryFolderListItem | null
}

/**
 * The documentStore's messages above the document list: failed uploads, deletes and folder
 * actions, a folder that no longer exists, a failed load and stopped status polling.
 */
export default function LibraryDocumentsNotices({
  libraryId,
  newFolderDialogOpen,
  renameFolderTarget,
}: LibraryDocumentsNoticesProps) {
  const error = useDocumentStore((s) => s.error)
  const deleteError = useDocumentStore((s) => s.deleteError)
  const folderError = useDocumentStore((s) => s.folderError)
  const folderNotFoundMessage = useDocumentStore((s) => s.folderNotFoundMessage)
  const pollingStoppedMessage = useDocumentStore((s) => s.pollingStoppedMessage)
  const loadDocuments = useDocumentStore((s) => s.loadDocuments)
  const clearDeleteError = useDocumentStore((s) => s.clearDeleteError)
  const clearFolderError = useDocumentStore((s) => s.clearFolderError)
  const clearFolderNotFoundMessage = useDocumentStore((s) => s.clearFolderNotFoundMessage)

  return (
    <>
      {deleteError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={clearDeleteError}>
          {deleteError}
        </Alert>
      )}
      {/* Hidden while either folder dialog is open - both already show the same
          documentStore.folderError locally. */}
      {folderError && !newFolderDialogOpen && !renameFolderTarget && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={clearFolderError}>
          {folderError}
        </Alert>
      )}
      {folderNotFoundMessage && (
        <Alert severity="info" sx={{ mb: 2 }} onClose={clearFolderNotFoundMessage}>
          {folderNotFoundMessage}
        </Alert>
      )}
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {pollingStoppedMessage && (
        // Polite rather than assertive: the list itself still shows its last loaded state.
        <Alert
          severity="warning"
          role="status"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => void loadDocuments(libraryId, {})}>
              Aktualisieren
            </Button>
          }
        >
          {pollingStoppedMessage}
        </Alert>
      )}
    </>
  )
}
