import { useEffect, useRef } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import { notify } from '@/shared/stores/notificationStore'
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
    // the reason's first sentence only: the list of allowed formats is no help after the fact
    const reason = (match ? match[1] : message)
      .split(/\.\s/)[0]
      .replace(/\.$/, '')
      .replace(/^Das Dateiformat wird nicht unterstützt$/, 'Format nicht unterstützt')
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
  const clearDeleteError = useDocumentStore((s) => s.clearDeleteError)
  const clearFolderError = useDocumentStore((s) => s.clearFolderError)
  const clearFolderNotFoundMessage = useDocumentStore((s) => s.clearFolderNotFoundMessage)

  // A rejected upload is a reply to one action: a popup with each reason once and its files
  // (guidelines 5.9), no tinted banner over the list. The messages stay in the store, so the head
  // can still tell an exhausted quota.
  const announced = useRef<string[] | null>(null)
  useEffect(() => {
    if (uploadErrors.length === 0 || announced.current === uploadErrors) return
    announced.current = uploadErrors
    const text = groupUploadErrors(uploadErrors)
      .map((group) =>
        group.files.length > 0
          ? `${group.reason}: ${group.files.slice(0, 3).join(', ')}${
              group.files.length > 3 ? ` und ${group.files.length - 3} weitere` : ''
            }`
          : group.reason,
      )
      .join(' · ')
    notify(text, 'error')
  }, [uploadErrors])

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
