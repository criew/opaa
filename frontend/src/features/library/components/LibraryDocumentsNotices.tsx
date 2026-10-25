import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import { TextAction } from '@/shared/ui/DetailSection'
import Typography from '@mui/material/Typography'
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
        <UploadRejections messages={uploadErrors} onClose={clearUploadErrors} />
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

/** „<reason> (Datei: <name>)“ as the store collects it, grouped by reason. */
function groupByReason(messages: string[]): Array<{ reason: string; files: string[] }> {
  const groups = new Map<string, string[]>()
  for (const message of messages) {
    const match = /^(.*) \(Datei: (.*)\)$/.exec(message)
    const reason = (match ? match[1] : message).replace(/\.$/, '')
    groups.set(reason, [...(groups.get(reason) ?? []), ...(match ? [match[2]] : [])])
  }
  return [...groups].map(([reason, files]) => ({ reason, files }))
}

/**
 * Files an upload turned away before a document existed: each reason once, the files after it.
 * It stays until it is closed or the next upload starts; one edge in the signal colour, no tint.
 */
function UploadRejections({ messages, onClose }: { messages: string[]; onClose: () => void }) {
  return (
    <Box
      role="alert"
      sx={{ mb: 2, pl: 1.5, borderLeft: 3, borderColor: 'error.main', display: 'flex', gap: 2 }}
    >
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        {groupByReason(messages).map(({ reason, files }) => (
          <Typography key={reason} sx={{ fontSize: 13, lineHeight: '22px' }}>
            <Box component="span" sx={{ fontWeight: 600 }}>
              {reason}
            </Box>
            {files.length > 0 && (
              <Box component="span" sx={{ color: 'text.secondary' }}>
                {' '}
                · nicht hochgeladen: {files.join(', ')}
              </Box>
            )}
          </Typography>
        ))}
      </Box>
      <Box sx={{ flex: 'none' }}>
        <TextAction onClick={onClose}>Schließen</TextAction>
      </Box>
    </Box>
  )
}
