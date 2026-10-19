import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import CreateNewFolderIcon from '@mui/icons-material/CreateNewFolder'
import DriveFolderUploadIcon from '@mui/icons-material/DriveFolderUpload'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import { ACCEPTED_FILE_EXTENSIONS } from '../hooks/useDocumentUpload'
import type { DocumentUpload } from '../hooks/useDocumentUpload'
import { directoryPathFromWebkitRelativePath, filterAcceptedFiles } from '../utils/directoryEntries'

interface LibraryDocumentUploadAreaProps {
  upload: DocumentUpload
  canManageFolders: boolean
  onNewFolder: () => void
}

/**
 * The upload affordances of an UPLOAD library above the document list: the drop zone, the hidden
 * file and folder pickers, the upload buttons and "Neuer Ordner".
 */
export default function LibraryDocumentUploadArea({
  upload,
  canManageFolders,
  onNewFolder,
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
    <Stack spacing={1.5} sx={{ mb: 3 }}>
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
        <UploadFileIcon sx={{ fontSize: 32, mb: 1 }} />
        <Typography>Dateien oder Ordner hierher ziehen</Typography>
      </Box>
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
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <Button
          variant="contained"
          startIcon={<UploadFileIcon />}
          onClick={() => fileInputRef.current?.click()}
          disabled={isUploading}
        >
          Dateien hochladen
        </Button>
        <Button
          variant="outlined"
          startIcon={<DriveFolderUploadIcon />}
          onClick={() => folderInputRef.current?.click()}
          disabled={isUploading}
        >
          Ordner hochladen
        </Button>
        {canManageFolders && (
          <Button variant="outlined" startIcon={<CreateNewFolderIcon />} onClick={onNewFolder}>
            Neuer Ordner
          </Button>
        )}
      </Stack>
      {isUploading && <LinearProgress aria-label="Hochladen läuft" />}
    </Stack>
  )
}
