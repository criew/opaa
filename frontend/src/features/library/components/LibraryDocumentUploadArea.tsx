import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { EmptyLine, TAB_CONTENT_WIDTH } from '@/shared/ui/TabSection'
import useMediaQuery from '@mui/material/useMediaQuery'
import type { Theme } from '@mui/material/styles'
import { ACCEPTED_FILE_EXTENSIONS } from '../hooks/useDocumentUpload'
import type { DocumentUpload } from '../hooks/useDocumentUpload'
import { directoryPathFromWebkitRelativePath, filterAcceptedFiles } from '../utils/directoryEntries'

interface LibraryDocumentUploadAreaProps {
  upload: DocumentUpload
  canManageFolders: boolean
  onNewFolder: () => void
  /** The library holds nothing yet: one sentence with „Dateien hochladen“ is the whole tab. */
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
  // below 600 px the head's main action sits in „⋯“: the toolbar shows it as its first button
  const compact = useMediaQuery((theme: Theme) => theme.breakpoints.down('sm'))
  return (
    <Stack
      direction="row"
      spacing={2}
      useFlexGap
      sx={{ alignItems: 'center', flexWrap: 'wrap', '& .MuiButton-text': { px: 0, minWidth: 0 } }}
    >
      {compact && (
        <Button
          size="small"
          onClick={() => upload.fileInputRef.current?.click()}
          disabled={upload.isUploading}
        >
          Dateien hochladen
        </Button>
      )}
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
 * The upload affordances of an UPLOAD library: the hidden file and folder pickers always, the
 * empty state while nothing is there - afterwards the list itself takes the drop. Folders are
 * picked from the toolbar or, while the library is empty, from the head's „⋯“.
 */
export default function LibraryDocumentUploadArea({
  upload,
  empty,
}: Pick<LibraryDocumentUploadAreaProps, 'upload' | 'empty'>) {
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
      {/* empty: one sentence with the one action, the same pattern as every tab; files can still
          be dropped on it, but nothing asks for a drag a touch screen cannot do */}
      {empty && (
        <Box
          onDragOver={(e) => {
            e.preventDefault()
            setIsDragActive(true)
          }}
          onDragLeave={() => setIsDragActive(false)}
          onDrop={handleDrop}
          sx={{
            maxWidth: TAB_CONTENT_WIDTH,
            outline: isDragActive ? '2px dashed' : 'none',
            outlineColor: 'primary.main',
          }}
        >
          <EmptyLine
            text="Noch keine Dokumente."
            action={
              <Button size="small" onClick={() => fileInputRef.current?.click()}>
                Dateien hochladen
              </Button>
            }
          />
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
      {/* a running upload is text in fg-2, no bar across the width (G 5.5); the band says „Läuft“ */}
      {isUploading && (
        <Typography role="status" sx={{ fontSize: 13, color: 'text.secondary' }}>
          Wird hochgeladen …
        </Typography>
      )}
    </Stack>
  )
}
