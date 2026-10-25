import Box from '@mui/material/Box'
import { TextAction } from '@/shared/ui/DetailSection'
import type { DocumentUpload } from '../hooks/useDocumentUpload'

/**
 * The empty state of an UPLOAD library: one drop zone with the one action that fills it. Once the
 * library holds documents, „Hochladen“ in the head and dropping onto the list take over.
 */
export default function LibraryDocumentUploadArea({ upload }: { upload: DocumentUpload }) {
  const { isDragActive, setIsDragActive, fileInputRef, handleDrop } = upload
  return (
    <Box
      onDragOver={(e) => {
        e.preventDefault()
        setIsDragActive(true)
      }}
      onDragLeave={() => setIsDragActive(false)}
      onDrop={handleDrop}
      sx={{
        border: '1px dashed',
        borderColor: isDragActive ? 'primary.main' : 'divider',
        borderRadius: 1,
        py: 4,
        px: 2,
        textAlign: 'center',
        color: 'text.secondary',
        fontSize: 13.5,
      }}
    >
      Noch keine Dokumente. Dateien oder Ordner hierher ziehen oder{' '}
      <TextAction onClick={() => fileInputRef.current?.click()}>Dateien auswählen</TextAction>
    </Box>
  )
}
