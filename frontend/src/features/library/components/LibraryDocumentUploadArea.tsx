import { useState } from 'react'
import Box from '@mui/material/Box'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import { ACCEPTED_FILE_EXTENSIONS } from '../hooks/useDocumentUpload'
import type { DocumentUpload } from '../hooks/useDocumentUpload'
import { directoryPathFromWebkitRelativePath, filterAcceptedFiles } from '../utils/directoryEntries'

interface LibraryDocumentUploadAreaProps {
  upload: DocumentUpload
  canManageFolders: boolean
  onNewFolder: () => void
  /**
   * Once the library holds something: no drop zone, only „Hochladen“ (files or a folder) and
   * „Neuer Ordner“ for the list's toolbar; files are then dropped on the list itself.
   */
  compact?: boolean
  /** The storage quota is used up: the menu says so instead of offering a futile upload. */
  quotaFull?: boolean
}

/**
 * The upload affordances of an UPLOAD library above the document list: the drop zone, the hidden
 * file and folder pickers, the upload buttons and "Neuer Ordner".
 */
export default function LibraryDocumentUploadArea({
  upload,
  canManageFolders,
  onNewFolder,
  compact = false,
  quotaFull = false,
}: LibraryDocumentUploadAreaProps) {
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
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

  const inputs = (
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

  if (compact) {
    return (
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexShrink: 0 }}>
        {inputs}
        {/* the busy state stands in the button itself (P39), it is not greyed out */}
        <Button
          variant="outlined"
          size="small"
          startIcon={<UploadFileIcon />}
          aria-haspopup="menu"
          aria-busy={isUploading || undefined}
          onClick={(event) => setMenuAnchor(event.currentTarget)}
          sx={{ display: { xs: 'none', sm: 'inline-flex' } }}
        >
          {isUploading ? 'Lädt hoch …' : 'Hochladen'}
        </Button>
        <Tooltip title="Hochladen">
          <IconButton
            aria-label="Hochladen"
            aria-haspopup="menu"
            aria-busy={isUploading || undefined}
            onClick={(event) => setMenuAnchor(event.currentTarget)}
            sx={{ display: { sm: 'none' } }}
          >
            <UploadFileIcon />
          </IconButton>
        </Tooltip>
        <Menu anchorEl={menuAnchor} open={menuAnchor !== null} onClose={() => setMenuAnchor(null)}>
          {quotaFull && (
            <MenuItem disabled sx={{ '&.Mui-disabled': { opacity: 1 } }}>
              <Typography sx={{ fontSize: 13, color: 'warning.main' }}>
                Speicherkontingent voll
              </Typography>
            </MenuItem>
          )}
          <MenuItem
            disabled={quotaFull}
            onClick={() => {
              setMenuAnchor(null)
              fileInputRef.current?.click()
            }}
          >
            Dateien …
          </MenuItem>
          <MenuItem
            disabled={quotaFull}
            onClick={() => {
              setMenuAnchor(null)
              folderInputRef.current?.click()
            }}
          >
            Ordner …
          </MenuItem>
          {canManageFolders && (
            <MenuItem
              onClick={() => {
                setMenuAnchor(null)
                onNewFolder()
              }}
            >
              Neuer Ordner
            </MenuItem>
          )}
        </Menu>
      </Stack>
    )
  }

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
        <Typography>Dateien oder Ordner hierher ziehen oder auswählen</Typography>
      </Box>
      {inputs}

      {isUploading && <LinearProgress aria-label="Hochladen läuft" sx={{ height: 2 }} />}
    </Stack>
  )
}
