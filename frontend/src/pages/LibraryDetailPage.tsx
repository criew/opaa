import { useEffect, useRef, useState } from 'react'
import type { DragEvent } from 'react'
import { Link as RouterLink, useNavigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import InputLabel from '@mui/material/InputLabel'
import LinearProgress from '@mui/material/LinearProgress'
import Link from '@mui/material/Link'
import MenuItem from '@mui/material/MenuItem'
import Pagination from '@mui/material/Pagination'
import Paper from '@mui/material/Paper'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import DeleteIcon from '@mui/icons-material/Delete'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type {
  AssetRole,
  DocumentSourceType,
  LibraryDocumentResponse,
  LibraryVisibility,
} from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { DEFAULT_PAGE_SIZE, useDocumentStore } from '../stores/documentStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import {
  assetRoleLabel,
  documentSourceTypeConfigKind,
  documentSourceTypeLabel,
  documentStatusLabel,
  formatFileSize,
  libraryVisibilityLabel,
} from '../utils/labels'
import LibraryGrantsDialog from '../components/LibraryGrantsDialog'

// Mirrors SupportedDocumentFormats#EXTENSIONS (backend/src/main/java/io/opaa/indexing) - only a
// client-side hint for the file picker; the backend remains the authority on what is accepted.
const ACCEPTED_FILE_EXTENSIONS = '.doc,.docx,.md,.pdf,.pptx,.txt'

const allVisibilities: LibraryVisibility[] = ['PRIVATE', 'SHARED', 'ORGANIZATION']
const personalLibraryVisibilities: LibraryVisibility[] = ['PRIVATE', 'SHARED']

function canEditLibrary(role: AssetRole | undefined): boolean {
  return role === 'MANAGER' || role === 'OWNER'
}

function canDeleteLibrary(role: AssetRole | undefined): boolean {
  return role === 'OWNER'
}

// ADR-0018, Entscheidung 2: auslösen darf, wer an der Bibliothek mindestens EDITOR ist - dieselbe
// Schwelle wie beim Hoch- und Löschen von Dokumenten.
function canManageDocuments(role: AssetRole | undefined): boolean {
  return role === 'EDITOR' || role === 'MANAGER' || role === 'OWNER'
}

function formatIndexedAt(indexedAt: string | null | undefined): string {
  if (!indexedAt) return '—'
  return new Date(indexedAt).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

function statusChipColor(
  status: LibraryDocumentResponse['status'],
): 'success' | 'warning' | 'error' {
  if (status === 'INDEXED') return 'success'
  if (status === 'FAILED') return 'error'
  return 'warning'
}

export default function LibraryDetailPage() {
  const { libraryId } = useParams()
  const navigate = useNavigate()
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  const listEntry = useLibraryStore((s) => s.libraries.find((l) => l.id === libraryId))
  const details = useLibraryStore((s) => (libraryId ? s.libraryDetails[libraryId] : undefined))
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)
  const loadLibraryDetails = useLibraryStore((s) => s.loadLibraryDetails)
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
  const deleteExistingLibrary = useLibraryStore((s) => s.deleteExistingLibrary)
  const storeError = useLibraryStore((s) => s.error)

  const [grantsDialogOpen, setGrantsDialogOpen] = useState(false)
  const [draft, setDraft] = useState<{
    name: string
    description: string
    visibility: LibraryVisibility
    listed: boolean
  } | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    // The list entry (myRole, documentCount, sourceType) may not be loaded yet if this page was
    // opened directly (e.g. a bookmark) rather than via a link from the overview.
    if (!listEntry) {
      void loadLibraries()
    }
  }, [listEntry, loadLibraries])

  useEffect(() => {
    if (!libraryId) return
    void loadLibraryDetails(libraryId)
  }, [libraryId, loadLibraryDetails])

  const library = details ?? listEntry
  const roleGrantsEdit = canEditLibrary(library?.myRole)
  const roleGrantsDelete = canDeleteLibrary(library?.myRole)
  const canEdit = roleGrantsEdit || isSystemAdmin
  const canDelete = (roleGrantsDelete || isSystemAdmin) && !library?.personal
  const canManageGrants = canEdit && !library?.personal
  const isAdministrativeOverride = isSystemAdmin && !roleGrantsEdit
  const editableVisibilities = library?.personal ? personalLibraryVisibilities : allVisibilities

  const name = draft ? draft.name : (library?.name ?? '')
  const description = draft ? draft.description : (library?.description ?? '')
  const visibility = draft ? draft.visibility : (library?.visibility ?? 'PRIVATE')
  const listed = draft ? draft.listed : (library?.listed ?? false)

  async function handleSave() {
    if (!libraryId) return
    setLocalError(null)
    setSaving(true)
    try {
      await updateExistingLibrary(libraryId, {
        name: name.trim(),
        description: description.trim() || undefined,
        visibility,
        listed,
        // Bewusst kein Quellkonfigurationsfeld gesetzt: das Backend lässt die gespeicherte
        // Konfiguration unverändert, solange keines der sourcePath/sourceUrl/sourceProxy/
        // sourceCredentials/sourceInsecureSsl-Felder in der Anfrage vorhanden ist (ADR-0018). Eine
        // Oberfläche zum Bearbeiten der Quellkonfiguration ist nicht Teil dieses Tickets.
        sourceInsecureSsl: null,
      })
      setDraft(null)
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Aktualisierung fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (!libraryId || !library) return
    // ADR-0018, Entscheidung 5: das Löschen einer Konnektorbibliothek entfernt auch ihren
    // gesamten indizierten Bestand - eine stärkere Wirkung als bei einer UPLOAD-Bibliothek, deren
    // Löschung blockiert bleibt, solange sie noch Dokumente enthält.
    const isConnectorLibrary = details != null && details.sourceType !== 'UPLOAD'
    const confirmMessage = isConnectorLibrary
      ? `Bibliothek "${library.name}" löschen? Das entfernt auch alle indizierten Dokumente dieser Bibliothek. Diese Aktion kann nicht rückgängig gemacht werden.`
      : `Bibliothek "${library.name}" löschen? Diese Aktion kann nicht rückgängig gemacht werden.`
    if (!window.confirm(confirmMessage)) return
    setLocalError(null)
    try {
      await deleteExistingLibrary(libraryId)
      navigate('/libraries')
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  if (!libraryId) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 } }}>
        <Alert severity="error">Keine Bibliothek angegeben.</Alert>
      </Box>
    )
  }

  if (!library) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 } }}>
        {storeError ? (
          <Alert severity="error">{storeError}</Alert>
        ) : (
          <Typography color="text.secondary">Bibliothek wird geladen …</Typography>
        )}
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      <Link
        component={RouterLink}
        to="/libraries"
        underline="hover"
        sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, mb: 2 }}
      >
        <ArrowBackIcon fontSize="small" />
        Zurück zur Übersicht
      </Link>

      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1 }}>
        <Typography variant="h6">{library.name}</Typography>
        {details && (
          <Chip
            label={documentSourceTypeLabel(details.sourceType)}
            size="small"
            variant="outlined"
          />
        )}
        <Chip label={assetRoleLabel(library.myRole)} size="small" variant="outlined" />
        {isAdministrativeOverride && (
          <Chip label="administrativ" size="small" color="info" variant="outlined" />
        )}
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {(library.documentCount ?? 0).toLocaleString('de-DE')}{' '}
        {(library.documentCount ?? 0) === 1 ? 'Dokument' : 'Dokumente'}
      </Typography>

      <Divider sx={{ mb: 2 }} />

      {localError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setLocalError(null)}>
          {localError}
        </Alert>
      )}
      {/* #506 review, finding 3: without this, a failed GET /libraries/{id} while the list entry
          is already cached silently drops both typed sections below with no explanation - details
          stays undefined, so neither the UPLOAD nor the connector section's sourceType check
          matches. */}
      {!details && storeError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {storeError}
        </Alert>
      )}
      {!canEdit && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Sie können diese Bibliothek einsehen, aber nicht bearbeiten.
        </Alert>
      )}
      {isAdministrativeOverride && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Sie bearbeiten diese Bibliothek als System-Administrator, nicht über eine eigene
          Berechtigung.
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
        <Typography variant="subtitle1" sx={{ mb: 1.5 }}>
          Stammdaten
        </Typography>
        <Stack spacing={1.5}>
          {details && (
            <Typography variant="caption" color="text.secondary">
              Quellentyp: {documentSourceTypeLabel(details.sourceType)} — kann nach der Anlage nicht
              geändert werden.
            </Typography>
          )}
          {/* #506 review, finding 7: disabled removes a read-only viewer's fields from the tab
              order and screen readers entirely; readOnly keeps them focusable and readable while
              still blocking edits. Select supports the same prop directly - Checkbox does not (no
              native HTML readonly for checkboxes), so it stays on disabled. */}
          <TextField
            label="Name der Bibliothek"
            value={name}
            onChange={(e) => setDraft({ name: e.target.value, description, visibility, listed })}
            slotProps={{ input: { readOnly: !canEdit } }}
            size="small"
          />
          <TextField
            label="Beschreibung"
            value={description}
            onChange={(e) => setDraft({ name, description: e.target.value, visibility, listed })}
            multiline
            minRows={2}
            slotProps={{ input: { readOnly: !canEdit } }}
            size="small"
          />
          <FormControl size="small">
            <InputLabel id="library-detail-visibility-label">Sichtbarkeit</InputLabel>
            <Select
              labelId="library-detail-visibility-label"
              label="Sichtbarkeit"
              value={visibility}
              readOnly={!canEdit}
              onChange={(e) =>
                setDraft({
                  name,
                  description,
                  visibility: e.target.value as LibraryVisibility,
                  listed,
                })
              }
            >
              {editableVisibilities.map((option) => (
                <MenuItem key={option} value={option}>
                  {libraryVisibilityLabel(option)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          {library.personal && (
            <Typography variant="caption" color="text.secondary">
              Die persönliche Bibliothek kann nicht organisationsweit sichtbar sein.
            </Typography>
          )}
          <FormControlLabel
            control={
              <Checkbox
                checked={listed}
                disabled={!canEdit}
                onChange={(e) =>
                  setDraft({ name, description, visibility, listed: e.target.checked })
                }
              />
            }
            label="Im Katalog auffindbar"
          />

          {canEdit && (
            <Stack direction="row" spacing={1}>
              <Button
                variant="contained"
                size="small"
                onClick={() => void handleSave()}
                disabled={saving || !name.trim()}
              >
                {saving ? 'Wird gespeichert …' : 'Speichern'}
              </Button>
              {canManageGrants && (
                <Button variant="outlined" size="small" onClick={() => setGrantsDialogOpen(true)}>
                  Rechte verwalten
                </Button>
              )}
              {canDelete && (
                <Button
                  color="error"
                  variant="outlined"
                  size="small"
                  onClick={() => void handleDelete()}
                  sx={{ ml: 'auto' }}
                >
                  Bibliothek löschen
                </Button>
              )}
            </Stack>
          )}
        </Stack>
      </Paper>

      {details && details.sourceType !== 'UPLOAD' && (
        <LibraryIndexingSection
          libraryId={libraryId}
          library={details}
          canTrigger={canManageDocuments(library.myRole) || isSystemAdmin}
        />
      )}
      {details && (
        <LibraryDocumentsSection
          // Forces a remount on library change (rather than resetting local state like
          // searchInput from within an effect, which react-hooks/set-state-in-effect flags as a
          // cascading-render risk): a fresh component instance starts every piece of local state
          // at its initial value for free.
          key={libraryId}
          libraryId={libraryId}
          sourceType={details.sourceType}
          canManage={canManageDocuments(library.myRole) || isSystemAdmin}
          // #506 review, finding 7: the document count in the header comes from the library
          // itself, not from documentStore - without this it stays on whatever value was loaded
          // on mount even after an upload or delete changes it.
          onDocumentsChanged={() => void loadLibraryDetails(libraryId)}
        />
      )}

      {canManageGrants && (
        <LibraryGrantsDialog
          open={grantsDialogOpen}
          library={{ id: libraryId, name: library.name }}
          onClose={() => setGrantsDialogOpen(false)}
        />
      )}
    </Box>
  )
}

interface LibraryDocumentsSectionProps {
  libraryId: string
  sourceType: DocumentSourceType
  canManage: boolean
  onDocumentsChanged: () => void
}

function LibraryDocumentsSection({
  libraryId,
  sourceType,
  canManage,
  onDocumentsChanged,
}: LibraryDocumentsSectionProps) {
  const documentsByLibrary = useDocumentStore((s) => s.documentsByLibrary)
  const pageStateByLibrary = useDocumentStore((s) => s.pageStateByLibrary)
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

  const [isDragActive, setIsDragActive] = useState(false)
  const [searchInput, setSearchInput] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)
  const searchDebounceRef = useRef<ReturnType<typeof setTimeout>>(undefined)

  const isUploadLibrary = sourceType === 'UPLOAD'
  // ADR-0018/#443: a FILESYSTEM or HTTP_DIRECTORY document only ever leaves the index because its
  // source file did too - deleting the row here does not touch that file, so the next indexing run
  // finds it still present and re-adds it right back, with no visible error to explain why. Rather
  // than offer a delete that silently undoes itself, the action is hidden entirely for connector
  // libraries; removing content stays possible at the source location (or, for the whole library
  // at once, from the source itself via re-run or via deleting the library, ADR-0018 Entscheidung
  // 5). Scoped to canDelete rather than reusing canManage as-is, so the upload/manage affordances
  // for UPLOAD libraries are unaffected.
  const canDelete = isUploadLibrary && canManage

  useEffect(() => {
    // #506 review, finding 2: uploadErrors/deleteError/error are not keyed by library - without
    // this reset, an upload or delete failure left over from a previously viewed library would
    // keep showing on a different library's section after switching.
    reset()
    void loadDocuments(libraryId, { page: 0, size: DEFAULT_PAGE_SIZE, q: '' })
    return () => {
      stopPolling(libraryId)
      // #517 code review, nit 1: without this, typing into the search field and switching
      // libraries within the 300ms debounce window (the section remounts via key={libraryId}, see
      // LibraryDetailPage) still fires the old instance's timer, which calls loadDocuments for the
      // *previous* libraryId - isLoading/error are global on documentStore, so that would flash an
      // unrelated loading/error state into the newly mounted section.
      if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    }
  }, [libraryId, loadDocuments, stopPolling, reset])

  const documents = documentsByLibrary[libraryId] ?? []
  const pageState = pageStateByLibrary[libraryId]
  const pageCount = pageState ? Math.max(1, Math.ceil(pageState.totalElements / pageState.size)) : 1

  async function handleFiles(files: FileList | File[]) {
    if (!canManage) return
    clearUploadErrors()
    for (const file of Array.from(files)) {
      try {
        await uploadNewDocument(libraryId, file)
        onDocumentsChanged()
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
    if (
      !window.confirm(
        `Dokument "${document.fileName}" löschen? Diese Aktion kann nicht rückgängig gemacht werden.`,
      )
    ) {
      return
    }
    try {
      await removeDocument(libraryId, document.id)
      onDocumentsChanged()
    } catch {
      // Fehlermeldung wird bereits über documentStore.deleteError angezeigt.
    }
  }

  function handleSearchChange(value: string) {
    setSearchInput(value)
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    searchDebounceRef.current = setTimeout(() => {
      void loadDocuments(libraryId, { page: 0, q: value })
    }, 300)
  }

  function handlePageChange(_event: unknown, newPage: number) {
    void loadDocuments(libraryId, { page: newPage - 1 })
  }

  return (
    <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
      <Typography variant="subtitle1" sx={{ mb: 1.5 }}>
        Dokumente
      </Typography>

      {/* #517 code review, nit 4: scoped to !canManage alone (not additionally isUploadLibrary,
          as an earlier version had it) - a VIEWER on a connector library lost this hint entirely
          otherwise, even though it is just as true there as for a read-only UPLOAD library. */}
      {!canManage && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Sie haben in dieser Bibliothek nur Leserechte.
        </Alert>
      )}
      {!isUploadLibrary && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Diese Liste zeigt den zuletzt indizierten Bestand dieser Konnektorbibliothek. Einzelne
          Dokumente lassen sich hier nicht löschen — ein gelöschtes Dokument käme mit dem nächsten
          Indizierungslauf zurück, solange seine Quelle unverändert ist.
        </Alert>
      )}

      {isUploadLibrary && canManage && (
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

      <TextField
        label="Dokumente durchsuchen"
        size="small"
        fullWidth
        sx={{ mb: 2 }}
        value={searchInput}
        onChange={(e) => handleSearchChange(e.target.value)}
        placeholder="Dateiname enthält …"
      />

      {isLoading ? (
        <Typography color="text.secondary">Dokumente werden geladen …</Typography>
      ) : documents.length === 0 ? (
        <Typography color="text.secondary">
          {searchInput
            ? 'Kein Dokument entspricht dieser Suche.'
            : 'Es sind noch keine Dokumente vorhanden.'}
        </Typography>
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
                {canDelete && (
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

      {pageCount > 1 && (
        <Stack direction="row" sx={{ mt: 2, justifyContent: 'center' }}>
          <Pagination
            count={pageCount}
            page={(pageState?.page ?? 0) + 1}
            onChange={handlePageChange}
          />
        </Stack>
      )}
    </Paper>
  )
}

interface LibraryIndexingSectionProps {
  libraryId: string
  library: {
    sourceType: 'FILESYSTEM' | 'HTTP_DIRECTORY' | 'RSS_FEED' | 'UPLOAD'
    sourcePath?: string | null
    sourceUrl?: string | null
    sourceProxy?: string | null
    sourceInsecureSsl?: boolean | null
  }
  canTrigger: boolean
}

function LibraryIndexingSection({ libraryId, library, canTrigger }: LibraryIndexingSectionProps) {
  const run = useIndexingStore((s) => s.runsByLibrary[libraryId] ?? IDLE_RUN_STATE)
  const {
    status,
    documentCount,
    totalDocuments,
    documentsSkipped,
    documentsFailed,
    documentsIndexedTotal,
    timestamp,
  } = run
  // #518 review, finding 1: which wording a run uses (feed entries vs. plain document count) is
  // decided by the library's own, unchanging sourceType - never by comparing documentCount and
  // documentsIndexedTotal, which happens to coincide for an RSS_FEED run whose entries carried no
  // attachments and would otherwise make the same library's label flicker from run to run.
  const isRssFeed = library.sourceType === 'RSS_FEED'
  const failedSuffix = documentsFailed > 0 ? `, davon ${documentsFailed} fehlgeschlagen` : ''
  const trigger = useIndexingStore((s) => s.triggerIndexing)
  const loadStatus = useIndexingStore((s) => s.loadStatus)
  const stopPolling = useIndexingStore((s) => s.stopPolling)

  useEffect(() => {
    void loadStatus(libraryId, library.sourceType)
    return () => stopPolling(libraryId)
  }, [libraryId, library.sourceType, loadStatus, stopPolling])

  const isRunning = status === 'RUNNING'
  const progressPercent =
    totalDocuments > 0 ? Math.round(((documentCount + documentsSkipped) / totalDocuments) * 100) : 0
  const configKind = documentSourceTypeConfigKind[library.sourceType]

  return (
    <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
      <Typography variant="subtitle1" sx={{ mb: 1.5 }}>
        Quellkonfiguration
      </Typography>

      <Stack spacing={0.75} sx={{ mb: 2 }}>
        {configKind === 'path' && (
          <Typography variant="body2">
            <strong>Verzeichnispfad:</strong> {library.sourcePath ?? '—'}
          </Typography>
        )}
        {configKind === 'url' && (
          <Typography variant="body2">
            <strong>Adresse (URL):</strong> {library.sourceUrl ?? '—'}
          </Typography>
        )}
        {configKind === 'url' && (
          <Typography variant="body2">
            <strong>Proxy:</strong> {library.sourceProxy ?? 'nicht konfiguriert'}
          </Typography>
        )}
        {configKind === 'url' && (
          <Typography variant="body2">
            <strong>Zertifikatsprüfung aussetzen:</strong>{' '}
            {library.sourceInsecureSsl ? 'ja' : 'nein'}
          </Typography>
        )}
        {configKind === 'url' && (
          <Typography variant="caption" color="text.secondary">
            Zugangsdaten sind aus Sicherheitsgründen nie Teil einer API-Antwort - diese Ansicht
            zeigt sie deshalb weder ein noch aus.
          </Typography>
        )}
      </Stack>

      <Divider sx={{ mb: 2 }} />

      {canTrigger ? (
        <>
          <Button
            variant="contained"
            startIcon={<PlayArrowIcon />}
            onClick={() => void trigger(libraryId, library.sourceType)}
            disabled={isRunning}
            sx={{ mb: 2 }}
          >
            {isRunning ? 'Indizierung läuft …' : 'Jetzt indizieren'}
          </Button>

          {isRunning && (
            <Box sx={{ mb: 2 }}>
              <LinearProgress
                variant={totalDocuments > 0 ? 'determinate' : 'indeterminate'}
                value={progressPercent}
                sx={{ mb: 1 }}
              />
              <Typography variant="body2" color="text.secondary">
                {totalDocuments > 0
                  ? isRssFeed
                    ? `${documentCount + documentsSkipped} von ${totalDocuments} Feed-Einträgen verarbeitet (${documentsIndexedTotal} Dokumente indiziert)`
                    : `${documentCount + documentsSkipped} von ${totalDocuments} Dokumenten verarbeitet`
                  : isRssFeed
                    ? 'Feed-Einträge werden ermittelt …'
                    : 'Dokumente werden ermittelt …'}
              </Typography>
            </Box>
          )}

          {status !== 'IDLE' && !isRunning && (
            <Box>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Letzter Lauf: {status === 'COMPLETED' ? 'Abgeschlossen' : 'Fehlgeschlagen'}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {isRssFeed
                  ? `${totalDocuments} Feed-Einträge, ${documentsSkipped} übersprungen, ${documentCount} indiziert (${documentsIndexedTotal} Dokumente insgesamt)${failedSuffix}`
                  : `Dokumente: ${documentCount} verarbeitet${documentsSkipped > 0 ? ` (${documentsSkipped} übersprungen)` : ''}${failedSuffix}`}
              </Typography>
              {timestamp && (
                <Typography variant="caption" color="text.secondary">
                  {new Date(timestamp).toLocaleString('de-DE')}
                </Typography>
              )}
            </Box>
          )}
        </>
      ) : (
        <Alert severity="info">
          Sie haben in dieser Bibliothek nur Leserechte und können keine Indizierung anstoßen.
        </Alert>
      )}
    </Paper>
  )
}
