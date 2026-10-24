import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { LibraryFolderListItem } from '@/shared/api/types'
import { useDocumentStore } from '../stores/documentStore'

interface LibraryDocumentsNoticesProps {
  libraryId: string
  newFolderDialogOpen: boolean
  renameFolderTarget: LibraryFolderListItem | null
}

/** An upload error names its file in a trailing „(Datei: …)“; the reason before it is shared. */
const FILE_SUFFIX = /^(.*) \(Datei: (.*)\)$/

/** The upload errors with each reason once and the files it hit below it. */
function groupUploadErrors(messages: string[]): Array<{ reason: string; files: string[] }> {
  const groups: Array<{ reason: string; files: string[] }> = []
  for (const message of messages) {
    const match = FILE_SUFFIX.exec(message)
    const reason = match ? match[1] : message
    const file = match ? match[2] : null
    const group = groups.find((entry) => entry.reason === reason)
    if (group) {
      if (file) group.files.push(file)
    } else {
      groups.push({ reason, files: file ? [file] : [] })
    }
  }
  return groups
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
  const uploadErrors = useDocumentStore((s) => s.uploadErrors)
  const deleteError = useDocumentStore((s) => s.deleteError)
  const folderError = useDocumentStore((s) => s.folderError)
  const folderNotFoundMessage = useDocumentStore((s) => s.folderNotFoundMessage)
  const pollingStoppedMessage = useDocumentStore((s) => s.pollingStoppedMessage)
  const loadDocuments = useDocumentStore((s) => s.loadDocuments)
  const clearUploadErrors = useDocumentStore((s) => s.clearUploadErrors)
  const clearDeleteError = useDocumentStore((s) => s.clearDeleteError)
  const clearFolderError = useDocumentStore((s) => s.clearFolderError)
  const clearFolderNotFoundMessage = useDocumentStore((s) => s.clearFolderNotFoundMessage)

  return (
    <>
      {uploadErrors.length > 0 && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={clearUploadErrors}>
          <Stack spacing={1}>
            {groupUploadErrors(uploadErrors).map((group) => (
              <Box key={group.reason}>
                <Typography variant="body2">{group.reason}</Typography>
                {group.files.length > 0 && (
                  <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
                    {group.files.map((file) => (
                      <Typography component="li" key={file} variant="body2">
                        {file}
                      </Typography>
                    ))}
                  </Box>
                )}
              </Box>
            ))}
          </Stack>
        </Alert>
      )}
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
