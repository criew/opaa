import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { TAB_CONTENT_WIDTH } from '@/shared/ui/TabSection'
import { ACCEPTED_FILE_EXTENSIONS } from '../hooks/useDocumentUpload'
import type { DocumentUpload } from '../hooks/useDocumentUpload'
import { directoryPathFromWebkitRelativePath, filterAcceptedFiles } from '../utils/directoryEntries'

interface LibraryDocumentUploadAreaProps {
  upload: DocumentUpload
  canManageFolders: boolean
  onNewFolder: () => void
  /** The library holds nothing yet: the drop zone with its buttons is the whole tab. */
  empty: boolean
}

/**
 * The text buttons of an UPLOAD library in the list's toolbar, once it holds something; they open
 * the pickers {@link LibraryDocumentUploadArea} keeps mounted.
 */
export function UploadToolbarActions({
  upload,
  canManageFolders,
  onNewFolder,
}: Omit<LibraryDocumentUploadAreaProps, 'empty'>) {
  return (
    <Stack
      direction="row"
      spacing={2}
      sx={{ alignItems: 'center', flexWrap: 'wrap', '& .MuiButton-text': { px: 0, minWidth: 0 } }}
    >
      <Button
        size="small"
        onClick={() => upload.folderInputRef.current?.click()}
        disabled={upload.isUploading}
      >
        Ordner hochladen
      </Button>
      {canManageFolders && (
        <Button size="small" onClick={onNewFolder}>
          Neuer Ordner
        </Button>
      )}
    </Stack>
  )
}

/**
 * The upload affordances of an UPLOAD library: the hidden file and folder pickers always, the drop
 * zone only while the library is empty - afterwards the list itself takes the drop. Files are
 * picked from the head's „Dateien hochladen“; folders from here and the toolbar.
 */
export default function LibraryDocumentUploadArea({
  upload,
  canManageFolders,
  onNewFolder,
  empty,
}: LibraryDocumentUploadAreaProps) {
  const {
    isUploading,
    isDragActive,
    setIsDragActive,
    fileInputRef,
    folderInputRef,
    clearUploadErrors,
    handleFiles,
    reportSkippedFiles,
    handleDrop,
  } = upload

  return (
    <Stack spacing={1.5} sx={{ mb: empty || isUploading ? 3 : 0 }}>
      {empty && (
        <Box
          role="button"
          tabIndex={0}
          aria-label="Dateien oder Ordner hierher ziehen zum Hochladen"
          onClick={() => fileInputRef.current?.click()}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              fileInputRef.current?.click()
            }
          }}
          onDragOver={(e) => {
            e.preventDefault()
            setIsDragActive(true)
          }}
          onDragLeave={() => setIsDragActive(false)}
          onDrop={handleDrop}
          sx={{
            maxWidth: TAB_CONTENT_WIDTH,
            border: '2px dashed',
            borderColor: isDragActive ? 'primary.main' : 'divider',
            borderRadius: 1,
            p: 3,
            textAlign: 'center',
            cursor: 'pointer',
            color: 'text.secondary',
            bgcolor: isDragActive ? 'action.hover' : 'transparent',
          }}
        >
          <Typography>Noch keine Dokumente. Dateien hierher ziehen</Typography>
        </Box>
      )}
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
      {/* „Dateien hochladen“ is the head's main action; here only the other two ways in */}
      {empty && (
        <Stack
          direction="row"
          spacing={2}
          sx={{
            alignItems: 'center',
            flexWrap: 'wrap',
            '& .MuiButton-text': { px: 0, minWidth: 0 },
          }}
        >
          <Button
            size="small"
            onClick={() => folderInputRef.current?.click()}
            disabled={isUploading}
          >
            Ordner hochladen
          </Button>
          {canManageFolders && (
            <Button size="small" onClick={onNewFolder}>
              Neuer Ordner
            </Button>
          )}
        </Stack>
      )}
      {/* a running upload is text in fg-2, no bar across the width (G 5.5); the band says „Läuft“ */}
      {isUploading && (
        <Typography role="status" sx={{ fontSize: 13, color: 'text.secondary' }}>
          Wird hochgeladen …
        </Typography>
      )}
    </Stack>
  )
}
