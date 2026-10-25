import { ACCEPTED_FILE_EXTENSIONS } from '../hooks/useDocumentUpload'
import type { DocumentUpload } from '../hooks/useDocumentUpload'
import { directoryPathFromWebkitRelativePath, filterAcceptedFiles } from '../utils/directoryEntries'

/**
 * The hidden file and folder pickers of an UPLOAD library. They live once on the page; the head's
 * „Hochladen“, the empty state and drag and drop all lead to them.
 */
export default function LibraryUploadInputs({ upload }: { upload: DocumentUpload }) {
  const { fileInputRef, folderInputRef, clearUploadErrors, handleFiles, reportSkippedFiles } =
    upload
  return (
    <>
      <input
        ref={fileInputRef}
        type="file"
        multiple
        hidden
        accept={ACCEPTED_FILE_EXTENSIONS}
        aria-label="Dateien auswählen"
        onChange={(e) => {
          if (e.target.files && e.target.files.length > 0) {
            void handleFiles(Array.from(e.target.files).map((file) => ({ file })))
          }
          e.target.value = ''
        }}
      />
      {/* webkitdirectory is set imperatively on this node (see useDocumentUpload). */}
      <input
        ref={folderInputRef}
        type="file"
        multiple
        hidden
        // Browsers do not reliably enforce `accept` for a
        // `webkitdirectory` selection - filterAcceptedFiles below is the check that holds.
        accept={ACCEPTED_FILE_EXTENSIONS}
        aria-label="Ordner auswählen"
        onChange={(e) => {
          if (e.target.files && e.target.files.length > 0) {
            clearUploadErrors()
            const entries = Array.from(e.target.files).map((file) => ({
              file,
              relativePath: directoryPathFromWebkitRelativePath(file.webkitRelativePath),
            }))
            const { accepted, skippedCount } = filterAcceptedFiles(
              entries,
              ACCEPTED_FILE_EXTENSIONS,
            )
            reportSkippedFiles(skippedCount, 0)
            if (accepted.length > 0) {
              void handleFiles(accepted, { skipClear: true })
            }
          }
          e.target.value = ''
        }}
      />
    </>
  )
}
