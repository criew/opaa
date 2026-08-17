import { useEffect, useRef, useState } from 'react'
import type { DragEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import IconButton from '@mui/material/IconButton'
import InputLabel from '@mui/material/InputLabel'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type { AssetRole, LibraryDocumentResponse, LibraryListResponse } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useDocumentStore } from '../stores/documentStore'
import { documentSourceTypeLabel, documentStatusLabel, formatFileSize } from '../utils/labels'

// Mirrors SupportedDocumentFormats#EXTENSIONS (backend/src/main/java/io/opaa/indexing) - only a
// client-side hint for the file picker; the backend remains the authority on what is accepted.
const ACCEPTED_FILE_EXTENSIONS = '.doc,.docx,.md,.pdf,.pptx,.txt'

function canManageDocuments(role: AssetRole | undefined): boolean {
  return role === 'EDITOR' || role === 'MANAGER' || role === 'OWNER'
}

function libraryLabel(library: LibraryListResponse): string {
  return library.personal ? `${library.name} (persönlich)` : library.name
}

function formatIndexedAt(indexedAt: string | null | undefined): string {
  if (!indexedAt) return '—'
  return new Date(indexedAt).toLocaleString('de-DE', {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

function statusChipColor(
  status: LibraryDocumentResponse['status'],
): 'success' | 'warning' | 'error' {
  if (status === 'INDEXED') return 'success'
  if (status === 'FAILED') return 'error'
  return 'warning'
}

export default function DocumentsPage() {
  const libraries = useLibraryStore((s) => s.libraries)
  const librariesLoading = useLibraryStore((s) => s.isLoading)
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  const documentsByLibrary = useDocumentStore((s) => s.documentsByLibrary)
  const isLoading = useDocumentStore((s) => s.isLoading)
  const error = useDocumentStore((s) => s.error)
  const uploadErrors = useDocumentStore((s) => s.uploadErrors)
  const deleteError = useDocumentStore((s) => s.deleteError)
  const isUploading = useDocumentStore((s) => s.isUploading)
  const loadDocuments = useDocumentStore((s) => s.loadDocuments)
  const uploadNewDocument = useDocumentStore((s) => s.uploadNewDocument)
  const removeDocument = useDocumentStore((s) => s.removeDocument)
  const clearUploadErrors = useDocumentStore((s) => s.clearUploadErrors)
  const clearDeleteError = useDocumentStore((s) => s.clearDeleteError)
  const stopPolling = useDocumentStore((s) => s.stopPolling)
  const reset = useDocumentStore((s) => s.reset)

  // The library a person explicitly picked, if any - null until they pick one, or once their pick
  // no longer refers to a library the account can currently see (e.g. access was revoked). Falls
  // back to a computed default below rather than being written back into state from an effect, so
  // switching libraries never needs a second render to settle.
  const [explicitLibraryId, setExplicitLibraryId] = useState<string | null>(null)
  const [isDragActive, setIsDragActive] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    void loadLibraries()
  }, [loadLibraries])

  const selectedLibraryId =
    explicitLibraryId && libraries.some((l) => l.id === explicitLibraryId)
      ? explicitLibraryId
      : (libraries.find((l) => l.personal)?.id ?? libraries[0]?.id ?? null)

  useEffect(() => {
    if (!selectedLibraryId) return
    void loadDocuments(selectedLibraryId)
    return () => stopPolling(selectedLibraryId)
  }, [selectedLibraryId, loadDocuments, stopPolling])

  // Stops every still-running poll interval on unmount, not just the one for the currently
  // displayed library - an upload can still be PENDING for a library that was briefly selected
  // and then left again, and its interval must not keep firing after this page is gone.
  useEffect(() => () => reset(), [reset])

  const selectedLibrary = libraries.find((l) => l.id === selectedLibraryId)
  const canManageSelected = canManageDocuments(selectedLibrary?.myRole) || isSystemAdmin
  const documents = (selectedLibraryId && documentsByLibrary[selectedLibraryId]) || []

  async function handleFiles(files: FileList | File[]) {
    if (!selectedLibraryId || !canManageSelected) return
    clearUploadErrors()
    for (const file of Array.from(files)) {
      try {
        await uploadNewDocument(selectedLibraryId, file)
      } catch {
        // Der Fehler landet bereits gesammelt in documentStore.uploadErrors und wird unten
        // angezeigt; die Schleife läuft weiter, damit ein Fehler bei einer Datei nicht die
        // übrigen blockiert.
      }
    }
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragActive(false)
    if (event.dataTransfer.files.length > 0) {
      void handleFiles(event.dataTransfer.files)
    }
  }

  async function handleDelete(document: LibraryDocumentResponse) {
    if (!selectedLibraryId) return
    if (
      !window.confirm(
        `Dokument "${document.fileName}" löschen? Diese Aktion kann nicht rückgängig gemacht werden.`,
      )
    ) {
      return
    }
    try {
      await removeDocument(selectedLibraryId, document.id)
    } catch {
      // Fehlermeldung wird bereits über documentStore.deleteError angezeigt.
    }
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      <Typography variant="h6" sx={{ mb: 2 }}>
        Dokumente
      </Typography>

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ mb: 2, alignItems: 'center' }}
      >
        <FormControl size="small" sx={{ minWidth: 260 }} disabled={librariesLoading}>
          <InputLabel id="documents-library-label">Bibliothek</InputLabel>
          <Select
            labelId="documents-library-label"
            label="Bibliothek"
            value={selectedLibraryId ?? ''}
            onChange={(e) => setExplicitLibraryId(e.target.value)}
          >
            {libraries.map((library) => (
              <MenuItem key={library.id} value={library.id}>
                {libraryLabel(library)}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
      </Stack>

      <Divider sx={{ mb: 2 }} />

      {selectedLibrary && !canManageSelected && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Sie haben in dieser Bibliothek nur Leserechte.
        </Alert>
      )}

      {canManageSelected && (
        <Stack spacing={1.5} sx={{ mb: 3 }}>
          <Box
            role="button"
            tabIndex={0}
            aria-label="Dateien hierher ziehen zum Hochladen"
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
            <Typography>Dateien hierher ziehen</Typography>
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
                void handleFiles(e.target.files)
              }
              e.target.value = ''
            }}
          />
          <Box>
            <Button
              variant="contained"
              startIcon={<UploadFileIcon />}
              onClick={() => fileInputRef.current?.click()}
              disabled={isUploading}
            >
              Dateien hochladen
            </Button>
          </Box>
          {isUploading && <LinearProgress aria-label="Hochladen läuft" />}
        </Stack>
      )}

      {uploadErrors.length > 0 && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={clearUploadErrors}>
          <Stack spacing={0.5}>
            {uploadErrors.map((message) => (
              <Typography key={message} variant="body2">
                {message}
              </Typography>
            ))}
          </Stack>
        </Alert>
      )}
      {deleteError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={clearDeleteError}>
          {deleteError}
        </Alert>
      )}
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {isLoading ? (
        <Typography color="text.secondary">Dokumente werden geladen …</Typography>
      ) : documents.length === 0 ? (
        <Typography color="text.secondary">Es sind noch keine Dokumente vorhanden.</Typography>
      ) : (
        <Stack spacing={1}>
          {documents.map((document) => (
            <Box
              key={document.id}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 2,
                p: 1.5,
                border: '1px solid',
                borderColor: 'divider',
                borderRadius: 1,
              }}
            >
              <Stack spacing={0.25} sx={{ minWidth: 0, flexGrow: 1 }}>
                <Typography sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
                  {document.fileName}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {documentSourceTypeLabel(document.sourceType)} ·{' '}
                  {formatFileSize(document.fileSize)} · {document.chunkCount}{' '}
                  {document.chunkCount === 1 ? 'Abschnitt' : 'Abschnitte'} ·{' '}
                  {formatIndexedAt(document.indexedAt)}
                </Typography>
              </Stack>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexShrink: 0 }}>
                <Chip
                  label={documentStatusLabel(document.status)}
                  size="small"
                  color={statusChipColor(document.status)}
                  variant="outlined"
                />
                {canManageSelected && (
                  <IconButton
                    aria-label={`Dokument ${document.fileName} löschen`}
                    size="small"
                    onClick={() => void handleDelete(document)}
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                )}
              </Stack>
            </Box>
          ))}
        </Stack>
      )}
    </Box>
  )
}
