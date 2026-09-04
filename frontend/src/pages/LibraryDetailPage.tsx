import { Fragment, useEffect, useRef, useState } from 'react'
import type { DragEvent } from 'react'
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Breadcrumbs from '@mui/material/Breadcrumbs'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Link from '@mui/material/Link'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Pagination from '@mui/material/Pagination'
import Select from '@mui/material/Select'
import Snackbar from '@mui/material/Snackbar'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import AttachFileIcon from '@mui/icons-material/AttachFile'
import CreateNewFolderIcon from '@mui/icons-material/CreateNewFolder'
import DeleteIcon from '@mui/icons-material/Delete'
import DriveFolderUploadIcon from '@mui/icons-material/DriveFolderUpload'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import FolderIcon from '@mui/icons-material/Folder'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import OpenInNewIcon from '@mui/icons-material/OpenInNew'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type {
  AssetRole,
  BulkMetadataValueResponse,
  DocumentSourceType,
  IndexingRunResponse,
  LibraryDocumentResponse,
  LibraryFolderListItem,
  LibrarySchedule,
  LibrarySpaceAssociationResponse,
  LibraryVisibility,
} from '../types/api'
import { detachSpaceLibrary, getLibraryFolder, getLibrarySpaceAssociations } from '../services/api'
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
  indexingRunEventCategoryLabel,
  libraryVisibilityLabel,
  scheduleFrequencyLabel,
} from '../utils/labels'
import { useDocumentPreview } from '../hooks/useDocumentPreview'
import {
  directoryPathFromWebkitRelativePath,
  filterAcceptedFiles,
  resolveDroppedItems,
} from '../utils/directoryEntries'
import LibraryGrantsDialog from '../components/LibraryGrantsDialog'
import EditLibrarySourceDialog from '../components/EditLibrarySourceDialog'
import EditLibraryScheduleDialog from '../components/EditLibraryScheduleDialog'
import DocumentTextPreviewDialog from '../components/DocumentTextPreviewDialog'
import DocumentMetadataPanel from '../components/metadata/DocumentMetadataPanel'
import MetadataMaintenanceAnchor from '../components/metadata/MetadataMaintenanceAnchor'
import { coreMetadataFieldLabel } from '../components/metadata/metadataValues'
import BulkMetadataDialog from '../components/metadata/BulkMetadataDialog'
import PageHeading from '../components/a11y/PageHeading'
import FieldLabel from '../components/wizard/FieldLabel'
import MetaBadge from '../components/MetaBadge'
import SectionHead from '../components/SectionHead'

// Mirrors SupportedDocumentFormats#EXTENSIONS (backend/src/main/java/io/opaa/indexing) - only a
// client-side hint for the file picker; the backend remains the authority on what is accepted.
// Exported so directoryEntries.test.ts exercises filterAcceptedFiles against the same list rather
// than a copy that can silently drift out of sync with it.
export const ACCEPTED_FILE_EXTENSIONS =
  '.csv,.doc,.docx,.eml,.html,.md,.msg,.odp,.ods,.odt,.pdf,.pptx,.txt,.xlsx'

const allVisibilities: LibraryVisibility[] = ['PRIVATE', 'SHARED', 'ORGANIZATION']

// #823: an upload entry carries an optional relativePath (the directory portion within a
// dropped/selected folder tree, e.g. "Protokolle/2026") alongside each File - sequential upload,
// per-file error collection unchanged from before #823.
interface UploadEntry {
  file: File
  relativePath?: string
}

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
  const canDelete = roleGrantsDelete || isSystemAdmin
  const isAdministrativeOverride = isSystemAdmin && !roleGrantsEdit

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
        // sourceCredentials/sourceInsecureSsl-Felder in der Anfrage vorhanden ist (ADR-0018). Das
        // Bearbeiten der Quellkonfiguration selbst laeuft ueber EditLibrarySourceDialog weiter
        // unten in dieser Datei (#516) - dieses Stammdaten-Formular hier ruehrt sie nicht an.
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
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        <Alert severity="error">Keine Bibliothek angegeben.</Alert>
      </Box>
    )
  }

  if (!library) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        {storeError ? (
          <Alert severity="error">{storeError}</Alert>
        ) : (
          <Typography color="text.secondary">Bibliothek wird geladen …</Typography>
        )}
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Link
        component={RouterLink}
        to="/libraries"
        underline="hover"
        sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, mb: 2, fontSize: 13 }}
      >
        <ArrowBackIcon fontSize="small" />
        Zurück zur Übersicht
      </Link>

      <Stack
        direction="row"
        spacing={1.5}
        sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 0.75 }}
      >
        <PageHeading title={library.name} />
        {details && <MetaBadge>{documentSourceTypeLabel(details.sourceType)}</MetaBadge>}
        <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
        {isAdministrativeOverride && <MetaBadge>administrativ</MetaBadge>}
      </Stack>
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 3 }}>
        {(library.documentCount ?? 0).toLocaleString('de-DE')}{' '}
        {(library.documentCount ?? 0) === 1 ? 'Dokument' : 'Dokumente'}
        {/* #119: storageQuotaBytes/storageUsedBytes are only sent to a caller with at least
            MANAGER (see KnowledgeLibraryService#toLibraryResponse) - a VIEWER's library object
            simply carries neither field, mirroring the source configuration fields below. */}
        {details?.storageQuotaBytes != null && details.storageUsedBytes != null && (
          <>
            {' · '}
            {formatFileSize(details.storageUsedBytes)} von{' '}
            {formatFileSize(details.storageQuotaBytes)} Speicherkontingent belegt
          </>
        )}
      </Typography>

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

      <Box sx={{ mb: 5, maxWidth: 760 }}>
        <SectionHead>Stammdaten</SectionHead>
        <Stack spacing={2}>
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
          <Box>
            <FieldLabel htmlFor="library-detail-name">Name der Bibliothek</FieldLabel>
            <TextField
              id="library-detail-name"
              fullWidth
              value={name}
              onChange={(e) => setDraft({ name: e.target.value, description, visibility, listed })}
              slotProps={{ input: { readOnly: !canEdit } }}
              size="small"
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="library-detail-description">Beschreibung</FieldLabel>
            <TextField
              id="library-detail-description"
              fullWidth
              value={description}
              onChange={(e) => setDraft({ name, description: e.target.value, visibility, listed })}
              multiline
              minRows={2}
              slotProps={{ input: { readOnly: !canEdit } }}
              size="small"
            />
          </Box>
          <FormControl size="small" fullWidth>
            <FieldLabel id="library-detail-visibility-label">Verteilungsstufe</FieldLabel>
            <Select
              labelId="library-detail-visibility-label"
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
              {allVisibilities.map((option) => (
                <MenuItem key={option} value={option}>
                  {libraryVisibilityLabel(option)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
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
              <Button variant="outlined" size="small" onClick={() => setGrantsDialogOpen(true)}>
                Rechte verwalten
              </Button>
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
      </Box>

      {canEdit && <LibrarySpacesSection key={libraryId} libraryId={libraryId} />}

      {details && details.sourceType !== 'UPLOAD' && (
        <LibraryIndexingSection
          libraryId={libraryId}
          library={details}
          canTrigger={canManageDocuments(library.myRole) || isSystemAdmin}
          // Same threshold as the Stammdaten edit above (#516) - editing the source configuration
          // is a MANAGER/OWNER-level change, not merely triggering an already-configured run.
          canEditSource={canEdit}
        />
      )}
      {/* #604 review, finding 1: the backend gates GET .../indexing/runs at MANAGER (canManage),
          not the narrower canRead a VIEWER already has - an IndexingRunEvent's reference
          routinely carries the library's own sourcePath/sourceUrl, the exact internal-path leak
          #507 exists to close for the source configuration display itself. canEdit here mirrors
          that same MANAGER/OWNER threshold (see canEditSource above), so the section is never
          rendered - and its GET never even fired - for a caller who could not read it anyway. */}
      {details && details.sourceType !== 'UPLOAD' && canEdit && (
        <LibraryIndexingHistorySection libraryId={libraryId} />
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

      {canEdit && (
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
  const foldersByLibrary = useDocumentStore((s) => s.foldersByLibrary)
  const breadcrumbByLibrary = useDocumentStore((s) => s.breadcrumbByLibrary)
  const isLoading = useDocumentStore((s) => s.isLoading)
  const error = useDocumentStore((s) => s.error)
  const uploadErrors = useDocumentStore((s) => s.uploadErrors)
  const deleteError = useDocumentStore((s) => s.deleteError)
  const folderError = useDocumentStore((s) => s.folderError)
  const folderNotFoundMessage = useDocumentStore((s) => s.folderNotFoundMessage)
  const isUploading = useDocumentStore((s) => s.isUploading)
  const loadDocuments = useDocumentStore((s) => s.loadDocuments)
  const uploadNewDocument = useDocumentStore((s) => s.uploadNewDocument)
  const removeDocument = useDocumentStore((s) => s.removeDocument)
  const createFolder = useDocumentStore((s) => s.createFolder)
  const renameFolder = useDocumentStore((s) => s.renameFolder)
  const removeFolder = useDocumentStore((s) => s.removeFolder)
  const reportUploadError = useDocumentStore((s) => s.reportUploadError)
  const clearUploadErrors = useDocumentStore((s) => s.clearUploadErrors)
  const clearDeleteError = useDocumentStore((s) => s.clearDeleteError)
  const clearFolderError = useDocumentStore((s) => s.clearFolderError)
  const clearFolderNotFoundMessage = useDocumentStore((s) => s.clearFolderNotFoundMessage)
  const stopPolling = useDocumentStore((s) => s.stopPolling)
  const reset = useDocumentStore((s) => s.reset)

  // #822: the currently open folder is URL state (?folder=<id>) - a reload or a bookmarked link
  // lands back in the same folder instead of always resetting to the library's root.
  const [searchParams, setSearchParams] = useSearchParams()
  const folderIdParam = searchParams.get('folder')
  // #1069: the Pflege-Anker's filter is URL state (?missingField=<core field key>) - the anchor's
  // button navigates here, a reload keeps the same worklist open.
  const missingFieldParam = searchParams.get('missingField')

  const [isDragActive, setIsDragActive] = useState(false)
  const [searchInput, setSearchInput] = useState('')
  // #1184 (ADR-0022, Entscheidung 5): per-parent expansion of the attachment group. An absent key
  // falls back to the search default below - during a search every group starts expanded so an
  // attachment hit is immediately visible, while normal browsing starts collapsed to keep the
  // list at its parent-level length.
  const [attachmentGroupExpansion, setAttachmentGroupExpansion] = useState<Record<string, boolean>>(
    {},
  )
  const [newFolderDialogOpen, setNewFolderDialogOpen] = useState(false)
  const [renameFolderTarget, setRenameFolderTarget] = useState<LibraryFolderListItem | null>(null)
  // #1068: per-document metadata panel (expanded on demand), the selection for a Sammelzuweisung
  // and the bulk dialog's outcome. A bulk assignment bumps metadataRefreshToken so every open
  // panel reloads its values.
  const [metadataOpenById, setMetadataOpenById] = useState<Record<string, boolean>>({})
  const [selectedDocumentIds, setSelectedDocumentIds] = useState<string[]>([])
  const [bulkDialogOpen, setBulkDialogOpen] = useState(false)
  const [bulkResultMessage, setBulkResultMessage] = useState<string | null>(null)
  const [metadataRefreshToken, setMetadataRefreshToken] = useState(0)
  // #1069: bumped after every metadata change so the anchor recounts; separate from
  // metadataRefreshToken, which reloads the open per-document panels.
  const [anchorRefreshToken, setAnchorRefreshToken] = useState(0)
  const [folderMenu, setFolderMenu] = useState<{
    anchorEl: HTMLElement
    folder: LibraryFolderListItem
  } | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  // #823: a whole-folder counterpart to fileInputRef - `webkitdirectory` is not part of React's
  // JSX typings for <input>, so it is set imperatively via the effect below instead of as a prop.
  const folderInputRef = useRef<HTMLInputElement>(null)
  const searchDebounceRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  // #738/#780: distinct from documentStore's error/uploadErrors/deleteError - opening the original
  // is a read-only, per-click action that never touches the store, so its failure (404, file
  // missing), Markdown/Text preview and download feedback all get their own local, dismissible
  // state here.
  const {
    error: openOriginalError,
    clearError: clearOpenOriginalError,
    previewDocument,
    closePreview,
    downloadMessage,
    clearDownloadMessage,
    openDocument,
  } = useDocumentPreview()

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
  // #822: folder management (create/rename/delete) is UPLOAD-only, same EDITOR-or-above threshold
  // as document upload/delete (ADR-0020 restricts folder creation to UPLOAD libraries the same way
  // POST .../documents already is).
  const canManageFolders = isUploadLibrary && canManage
  // #1068 (metadata-schema.md, "Manuelle Korrektur ist Teil des ersten Schnitts"): whoever may
  // edit the library's documents may correct their metadata - for every sourceType, since a
  // metadata value hangs at the document row, not at the file.
  const canEditMetadata = canManage

  useEffect(() => {
    // #506 review, finding 2: uploadErrors/deleteError/error are not keyed by library - without
    // this reset, an upload or delete failure left over from a previously viewed library would
    // keep showing on a different library's section after switching.
    reset()
    return () => {
      stopPolling(libraryId)
      // #517 code review, nit 1: without this, typing into the search field and switching
      // libraries within the 300ms debounce window (the section remounts via key={libraryId}, see
      // LibraryDetailPage) still fires the old instance's timer, which calls loadDocuments for the
      // *previous* libraryId - isLoading/error are global on documentStore, so that would flash an
      // unrelated loading/error state into the newly mounted section.
      if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    }
  }, [libraryId, stopPolling, reset])

  useEffect(() => {
    // #822: reacts to the URL's folder param too, not just libraryId - a breadcrumb click or a
    // folder row navigates by changing the URL, and this is what turns that into a fresh load
    // (page 0, no leftover search term) for the newly requested folder.
    void loadDocuments(libraryId, {
      page: 0,
      size: DEFAULT_PAGE_SIZE,
      q: '',
      folderId: folderIdParam,
      missingMetadataField: missingFieldParam,
    })
  }, [libraryId, folderIdParam, missingFieldParam, loadDocuments])

  useEffect(() => {
    // #822: an invalid/foreign folderId (stale bookmark, deleted folder) is caught by
    // loadDocuments, which already falls back to loading the root - this just corrects the URL to
    // match, dropping the dead ?folder= param instead of leaving it pointing at nothing.
    if (folderNotFoundMessage && folderIdParam) {
      const next = new URLSearchParams(searchParams)
      next.delete('folder')
      setSearchParams(next, { replace: true })
    }
  }, [folderNotFoundMessage, folderIdParam, searchParams, setSearchParams])

  useEffect(() => {
    // #823: `webkitdirectory` (plus its older Firefox/legacy aliases) turns this hidden <input
    // type="file"> into a directory picker - not part of React's <input> typings, so it has to be
    // set on the DOM node directly rather than passed as a JSX prop.
    const node = folderInputRef.current
    if (!node) return
    node.setAttribute('webkitdirectory', '')
    node.setAttribute('directory', '')
    node.setAttribute('mozdirectory', '')
  }, [])

  const documents = documentsByLibrary[libraryId] ?? []
  const pageState = pageStateByLibrary[libraryId]
  // #822 review, finding 6a: folders/breadcrumb are not paged - the backend returns the requested
  // folder's *entire* set of direct subfolders on every page (pageCount below only counts
  // documents), so rendering them on every page would repeat the same rows pointlessly. They
  // belong on the first page only, alongside the folder's own breadcrumb.
  const isFirstPage = (pageState?.page ?? 0) === 0
  const folders = isFirstPage ? (foldersByLibrary[libraryId] ?? []) : []
  const breadcrumb = breadcrumbByLibrary[libraryId] ?? []
  const pageCount = pageState ? Math.max(1, Math.ceil(pageState.totalElements / pageState.size)) : 1

  // #1184: the backend guarantees each attachment row follows its (transitive) top-level parent
  // within the same page (see the OpenAPI items description), so grouping is a single sequential
  // pass. A row whose parent is missing from the page (defensive - the contract forbids it) is
  // rendered as its own top-level row rather than dropped.
  const isSearchActive = Boolean(pageState?.q)
  const documentsById = new Map(documents.map((doc) => [doc.id, doc]))
  const documentGroups: {
    parent: LibraryDocumentResponse
    attachments: LibraryDocumentResponse[]
  }[] = []
  // #1069: the Pflege-Anker's list holds exactly the rows without a value - an attachment is a row
  // of its own there and its parent may be absent, so grouping would nest it under an unrelated
  // document. Every row stands for itself while the filter is active.
  const isMissingFilterActive = Boolean(pageState?.missingMetadataField)
  for (const doc of documents) {
    const lastGroup = documentGroups[documentGroups.length - 1]
    if (!isMissingFilterActive && doc.parentDocumentId && lastGroup) {
      lastGroup.attachments.push(doc)
    } else {
      documentGroups.push({ parent: doc, attachments: [] })
    }
  }

  function isGroupExpanded(parentId: string): boolean {
    return attachmentGroupExpansion[parentId] ?? isSearchActive
  }

  function toggleAttachmentGroup(parentId: string) {
    setAttachmentGroupExpansion((previous) => ({
      ...previous,
      [parentId]: !(previous[parentId] ?? isSearchActive),
    }))
  }

  function toggleMetadataPanel(documentId: string) {
    setMetadataOpenById((previous) => ({ ...previous, [documentId]: !previous[documentId] }))
  }

  function toggleSelected(documentId: string) {
    setSelectedDocumentIds((previous) =>
      previous.includes(documentId)
        ? previous.filter((id) => id !== documentId)
        : [...previous, documentId],
    )
  }

  const visibleDocumentIds = documents.map((doc) => doc.id)
  const allVisibleSelected =
    visibleDocumentIds.length > 0 &&
    visibleDocumentIds.every((id) => selectedDocumentIds.includes(id))

  function toggleAllVisible() {
    setSelectedDocumentIds((previous) =>
      allVisibleSelected
        ? previous.filter((id) => !visibleDocumentIds.includes(id))
        : [...previous, ...visibleDocumentIds.filter((id) => !previous.includes(id))],
    )
  }

  function handleBulkDone(result: BulkMetadataValueResponse) {
    const parts = [
      `${result.updatedCount} ${result.updatedCount === 1 ? 'Dokument' : 'Dokumente'} aktualisiert`,
      `${result.unchangedCount} unverändert`,
    ]
    if (result.rejectedDocumentIds.length > 0) {
      parts.push(`${result.rejectedDocumentIds.length} abgewiesen`)
    }
    setBulkResultMessage(`Feld gesetzt: ${parts.join(', ')}.`)
    setSelectedDocumentIds([])
    setMetadataRefreshToken((previous) => previous + 1)
    setAnchorRefreshToken((previous) => previous + 1)
    // A document that just got a value leaves the anchor's worklist - reload the same page.
    void loadDocuments(libraryId, {})
  }

  // #1069: a corrected document leaves the anchor - and, while its worklist is open, the list
  // below too, so the number and the rows keep telling the same story.
  function handleMetadataValueChanged() {
    setAnchorRefreshToken((previous) => previous + 1)
    if (missingFieldParam) {
      void loadDocuments(libraryId, {})
    }
  }

  // #1069: opens (or leaves) the anchor's worklist - the list is bibliotheksweit, so an open
  // folder and a running search are dropped with it, and the selection starts empty.
  function setMissingFieldFilter(fieldKey: string | null) {
    setSearchInput('')
    setSelectedDocumentIds([])
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    const next = new URLSearchParams(searchParams)
    next.delete('folder')
    if (fieldKey) {
      next.set('missingField', fieldKey)
    } else {
      next.delete('missingField')
    }
    setSearchParams(next)
  }

  // #822: navigates into a folder (or back to the root with null) by changing the URL's folder
  // param - the load effect above reacts to that change. Also clears any in-flight search, since a
  // folder is always browsed bibliotheksweit-search-free (a search hit's own folderPath link uses
  // this the same way, see the document row rendering below).
  function navigateToFolder(folderId: string | null) {
    setSearchInput('')
    setSelectedDocumentIds([])
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    // #822 review, finding 1: a search hit's folderPath link (or, in principle, a breadcrumb/folder
    // row for the folder already open) can name the very folder already loaded - the URL then does
    // not change, so the load effect above never fires and the stale, still-bibliotheksweit search
    // results would keep showing despite the now-empty search field. Reloading explicitly in that
    // case is the only way to still land on that folder's contents.
    if (folderId === folderIdParam && !missingFieldParam) {
      void loadDocuments(libraryId, { page: 0, size: DEFAULT_PAGE_SIZE, q: '', folderId })
      return
    }
    const next = new URLSearchParams(searchParams)
    if (folderId) {
      next.set('folder', folderId)
    } else {
      next.delete('folder')
    }
    // #1069: browsing a folder leaves the anchor's (bibliotheksweite) worklist.
    next.delete('missingField')
    setSearchParams(next)
  }

  async function handleCreateFolder(name: string) {
    // #822 review, finding 6c: derives the parent explicitly from the URL's own folder param
    // rather than leaving it to documentStore's pageStateByLibrary fallback - a "Neuer Ordner"
    // click before the deep-linked folder's first load has resolved would otherwise still see the
    // page state's stale (root) folderId and create the new folder in the wrong place.
    await createFolder(libraryId, name, folderIdParam)
  }

  async function handleRenameFolder(folder: LibraryFolderListItem, name: string) {
    await renameFolder(libraryId, folder.id, name)
  }

  async function handleDeleteFolder(folder: LibraryFolderListItem) {
    // #822 review, finding 4: re-fetches the folder right before confirming, so the confirmation
    // names the current recursive document count (per the feature spec) rather than whatever the
    // list happened to show last - it may be stale if another tab/session changed the folder's
    // contents in the meantime. Falls back to the list's own count if the re-fetch itself fails,
    // rather than blocking the deletion on a read that is not strictly required to proceed.
    let documentCount = folder.documentCount
    try {
      const current = await getLibraryFolder(libraryId, folder.id)
      documentCount = current.documentCount
    } catch {
      // Falls back to the (possibly stale) count already shown in the row above.
    }
    const confirmMessage =
      documentCount > 0
        ? `Ordner "${folder.name}" und ${documentCount} ${
            documentCount === 1 ? 'Dokument' : 'Dokumente'
          } löschen? Diese Aktion kann nicht rückgängig gemacht werden.`
        : `Ordner "${folder.name}" löschen? Diese Aktion kann nicht rückgängig gemacht werden.`
    if (!window.confirm(confirmMessage)) return
    try {
      await removeFolder(libraryId, folder.id)
      onDocumentsChanged()
    } catch {
      // Fehlermeldung wird bereits über documentStore.folderError angezeigt.
    }
  }

  async function handleFiles(entries: UploadEntry[], options?: { skipClear?: boolean }) {
    if (!canManage) return
    // #823 review, Befund 2: the folder-upload entry points below already clear uploadErrors
    // themselves, right before adding their own skipped-files summary (see reportSkippedFiles) -
    // clearing again here would erase that summary before it is ever shown.
    if (!options?.skipClear) {
      clearUploadErrors()
    }
    for (const entry of entries) {
      try {
        await uploadNewDocument(libraryId, entry.file, entry.relativePath || undefined)
        onDocumentsChanged()
      } catch {
        // Der Fehler landet bereits gesammelt in documentStore.uploadErrors und wird unten
        // angezeigt; die Schleife läuft weiter, damit ein Fehler bei einer Datei nicht die
        // übrigen blockiert.
      }
    }
  }

  // #823 review, Befund 2: one collective German message for every file skipped client-side
  // before ever reaching the backend - naming three hundred individually rejected files would be
  // worse than naming none. Well-known OS/desktop metadata files (Thumbs.db, .DS_Store, ...) are
  // never counted here at all (see filterAcceptedFiles/isSystemFile in utils/directoryEntries.ts).
  function reportSkippedFiles(skippedCount: number, failedCount: number) {
    const parts: string[] = []
    if (skippedCount > 0) {
      parts.push(
        `${skippedCount} ${skippedCount === 1 ? 'Datei wurde' : 'Dateien wurden'} wegen eines nicht unterstützten Formats übersprungen`,
      )
    }
    if (failedCount > 0) {
      // #823 review, Befund 3: resolveDroppedItems counts entries it could not read (permission
      // error, a file removed/moved between the drop and this read) instead of aborting the whole
      // drop - reported here alongside a format-based skip, in the same one collective message.
      parts.push(
        `${failedCount} ${failedCount === 1 ? 'Datei konnte' : 'Dateien konnten'} nicht gelesen werden`,
      )
    }
    if (parts.length > 0) {
      reportUploadError(`${parts.join('; ')}.`)
    }
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragActive(false)
    clearUploadErrors()
    // #823: DataTransferItemList.webkitGetAsEntry() must be read synchronously, before any await -
    // resolveDroppedItems does that internally, but the items list itself has to be captured here,
    // inside this synchronous handler, not passed into a later .then()/await boundary.
    const items = event.dataTransfer.items
    if (items && items.length > 0) {
      resolveDroppedItems(items)
        .then(({ files, failedCount }) => {
          // #823 review, Befund 2: filtered here, not left to the backend - a dropped OS folder
          // routinely carries files nobody dragged there on purpose, and every rejected upload
          // this filter avoids is also one fewer request the backend has to reject on its own.
          const { accepted, skippedCount } = filterAcceptedFiles(files, ACCEPTED_FILE_EXTENSIONS)
          reportSkippedFiles(skippedCount, failedCount)
          if (accepted.length > 0) {
            void handleFiles(
              accepted.map((r) => ({ file: r.file, relativePath: r.relativePath || undefined })),
              { skipClear: true },
            )
          }
        })
        .catch(() => {
          // #823 review, Befund 3: resolveDroppedItems itself failing outright - not a single
          // unreadable file (already handled above via failedCount), an unexpected rejection at
          // the very top level - must still surface instead of the whole drop doing nothing
          // without any visible feedback.
          reportUploadError('Der abgelegte Ordner konnte nicht gelesen werden.')
        })
      return
    }
    if (event.dataTransfer.files.length > 0) {
      const entries = Array.from(event.dataTransfer.files).map((file) => ({ file }))
      const { accepted, skippedCount } = filterAcceptedFiles(entries, ACCEPTED_FILE_EXTENSIONS)
      reportSkippedFiles(skippedCount, 0)
      if (accepted.length > 0) {
        void handleFiles(accepted, { skipClear: true })
      }
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

  // #738/#747: every sourceType now opens through GET .../content (fetched as a Blob and
  // opened/downloaded client-side, since the endpoint is Bearer-authenticated and a plain
  // <a href> cannot carry that token) - the endpoint proxies HTTP_DIRECTORY/RSS_FEED server-side
  // from their own stored source URL since #747, instead of leaving the client to navigate there
  // directly (broken on the Demo-Instanz, whose corpus containers are only reachable from OPAA's
  // own Docker network, never the caller's browser). sourceEntryUrl/sourceUrl stay visible as
  // secondary information below (see the "Herkunft"/"Quelle" captions further down).
  async function handleOpenOriginal(document: LibraryDocumentResponse) {
    await openDocument(document.id, document.fileName)
  }

  // #1184 (ADR-0022, Entscheidung 5): one visual row of the document list - a top-level document
  // (optionally with the toggle for its attachment group) or an attachment row, indented one
  // level. Attachments of any nesting depth all indent the same single level under the top-level
  // parent; a deeper chain (Mail-in-Mail) names its direct parent via `viaFileName` instead of
  // nesting further, keeping the rows findable without tree navigation.
  function renderDocumentRow(
    document: LibraryDocumentResponse,
    options: { attachments?: LibraryDocumentResponse[]; viaFileName?: string | null } = {},
  ) {
    const isAttachment = Boolean(document.parentDocumentId)
    const attachments = options.attachments ?? []
    const expanded = isGroupExpanded(document.id)
    const metadataOpen = Boolean(metadataOpenById[document.id])
    return (
      <Fragment key={document.id}>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 2,
            p: 1.5,
            ml: isAttachment ? 4 : 0,
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1,
          }}
        >
          {canEditMetadata && (
            <Checkbox
              size="small"
              checked={selectedDocumentIds.includes(document.id)}
              onChange={() => toggleSelected(document.id)}
              slotProps={{ input: { 'aria-label': `Dokument ${document.fileName} auswählen` } }}
              sx={{ p: 0.5 }}
            />
          )}
          <Stack spacing={0.25} sx={{ minWidth: 0, flexGrow: 1 }}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
              {isAttachment && <AttachFileIcon fontSize="small" color="action" />}
              <Typography sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
                {document.fileName}
              </Typography>
              {isAttachment && <Chip label="Anhang" size="small" variant="outlined" />}
            </Stack>
            <Typography variant="caption" color="text.secondary">
              {formatFileSize(document.fileSize)} · {document.chunkCount}{' '}
              {document.chunkCount === 1 ? 'Abschnitt' : 'Abschnitte'} ·{' '}
              {formatIndexedAt(document.indexedAt)}
            </Typography>
            {options.viaFileName && (
              <Typography variant="caption" color="text.secondary">
                Anhang von: {options.viaFileName}
              </Typography>
            )}
            {/* #822: shown whenever a document sits in a folder - most usefully on a search
              hit (bibliotheksweit, ADR-0020), whose result list has no breadcrumb of its
              own to place it in the structure; the link navigates into that folder and
              clears the active search (navigateToFolder). */}
            {document.folderPath && (
              <Typography variant="caption" color="text.secondary">
                Ordner:{' '}
                <Link
                  component="button"
                  underline="hover"
                  onClick={() => navigateToFolder(document.folderId ?? null)}
                >
                  {document.folderPath}
                </Link>
              </Typography>
            )}
            {/* #493: sourceEntryUrl trägt nur eine Anlage, die von einem RSS-Feed-Eintrag
              stammt (#468) - der Link macht sichtbar, aus welchem Eintrag sie gefunden
              wurde, statt sie im Index kontextlos stehen zu lassen. */}
            {document.sourceEntryUrl && (
              <Typography variant="caption" color="text.secondary" sx={{ wordBreak: 'break-word' }}>
                Herkunft:{' '}
                <Link href={document.sourceEntryUrl} target="_blank" rel="noopener noreferrer">
                  {document.sourceEntryUrl}
                </Link>
              </Typography>
            )}
            {/* #747: sourceUrl (HTTP_DIRECTORY's own location, or a plain RSS entry's own
              page) stays visible as secondary information now that "Original öffnen"
              proxies through the content endpoint instead of navigating here directly -
              only shown when sourceEntryUrl above is absent, to avoid the same remote
              address appearing twice for an RSS attachment. */}
            {!document.sourceEntryUrl && document.sourceUrl && (
              <Typography variant="caption" color="text.secondary" sx={{ wordBreak: 'break-word' }}>
                Quelle:{' '}
                <Link href={document.sourceUrl} target="_blank" rel="noopener noreferrer">
                  {document.sourceUrl}
                </Link>
              </Typography>
            )}
            {attachments.length > 0 && (
              <Box>
                <Button
                  size="small"
                  onClick={() => toggleAttachmentGroup(document.id)}
                  aria-expanded={expanded}
                  aria-label={`Anhänge von ${document.fileName} ${expanded ? 'verbergen' : 'anzeigen'}`}
                  startIcon={expanded ? <ExpandLessIcon /> : <ExpandMoreIcon />}
                >
                  {attachments.length} {attachments.length === 1 ? 'Anhang' : 'Anhänge'}
                </Button>
              </Box>
            )}
          </Stack>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexShrink: 0 }}>
            {/* #434: a FAILED document's asynchronous processing failure is only visible to
              the user via this German errorMessage - the status chip alone only says
              something went wrong, not what. */}
            <Tooltip
              title={document.status === 'FAILED' ? (document.errorMessage ?? '') : ''}
              disableHoverListener={document.status !== 'FAILED' || !document.errorMessage}
            >
              <Chip
                label={documentStatusLabel(document.status)}
                size="small"
                color={statusChipColor(document.status)}
                variant="outlined"
              />
            </Tooltip>
            {/* #738/#747: every sourceType now offers the action - the content endpoint
              proxies HTTP_DIRECTORY/RSS_FEED server-side too, and a source that turns out
              unreachable (missing local file, offline remote source) is a 404 handled via
              openOriginalError above, not a reason to hide the button. */}
            <IconButton
              aria-label={`Metadaten von ${document.fileName} ${metadataOpen ? 'verbergen' : 'anzeigen'}`}
              aria-expanded={metadataOpen}
              size="small"
              color={metadataOpen ? 'primary' : 'default'}
              onClick={() => toggleMetadataPanel(document.id)}
            >
              <InfoOutlinedIcon fontSize="small" />
            </IconButton>
            <IconButton
              aria-label={`Original von ${document.fileName} öffnen`}
              size="small"
              onClick={() => void handleOpenOriginal(document)}
            >
              <OpenInNewIcon fontSize="small" />
            </IconButton>
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
        {metadataOpen && (
          <DocumentMetadataPanel
            libraryId={libraryId}
            documentId={document.id}
            fileName={document.fileName}
            canEdit={canEditMetadata}
            refreshToken={metadataRefreshToken}
            onValueChanged={handleMetadataValueChanged}
          />
        )}
      </Fragment>
    )
  }

  // #1068: the selection is bound to the list the person is looking at - a folder, search or
  // page change drops it, so "Feld setzen" never writes onto documents nobody sees.
  function handleSearchChange(value: string) {
    setSearchInput(value)
    setSelectedDocumentIds([])
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    searchDebounceRef.current = setTimeout(() => {
      void loadDocuments(libraryId, { page: 0, q: value })
    }, 300)
  }

  function handlePageChange(_event: unknown, newPage: number) {
    setSelectedDocumentIds([])
    void loadDocuments(libraryId, { page: newPage - 1 })
  }

  return (
    <Box sx={{ mb: 5 }}>
      <SectionHead>Dokumente</SectionHead>

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
          {/* #823: webkitdirectory is set imperatively on this node (see the useEffect above) -
              React's <input> typings do not include it as a JSX prop. */}
          <input
            ref={folderInputRef}
            type="file"
            multiple
            hidden
            // #823 review, Befund 2: browsers do not reliably enforce `accept` for a
            // `webkitdirectory` selection - filterAcceptedFiles below is the check that actually
            // holds, this is only the same client-side hint ACCEPTED_FILE_EXTENSIONS already gives
            // the plain file input above.
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
              <Button
                variant="outlined"
                startIcon={<CreateNewFolderIcon />}
                onClick={() => setNewFolderDialogOpen(true)}
              >
                Neuer Ordner
              </Button>
            )}
          </Stack>
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
      {/* #822: hidden while either folder dialog is open - both already show the same
          documentStore.folderError locally (see NewFolderDialog/RenameFolderDialog below), and
          showing it here too would duplicate the message on the page behind the dialog. Delete
          has no dialog of its own (a window.confirm instead), so this remains its only display. */}
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
      {openOriginalError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={clearOpenOriginalError}>
          {openOriginalError}
        </Alert>
      )}
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* #822: shown once at least one folder has been opened - breadcrumb.length is 0 both at the
          library's root and while a search is active (search is always bibliotheksweit,
          ADR-0020). */}
      {breadcrumb.length > 0 && (
        <Breadcrumbs aria-label="Ordnerpfad" sx={{ mb: 2 }}>
          <Link component="button" underline="hover" onClick={() => navigateToFolder(null)}>
            Wurzel
          </Link>
          {breadcrumb.map((item, index) =>
            index === breadcrumb.length - 1 ? (
              <Typography key={item.id} color="text.primary">
                {item.name}
              </Typography>
            ) : (
              <Link
                key={item.id}
                component="button"
                underline="hover"
                onClick={() => navigateToFolder(item.id)}
              >
                {item.name}
              </Link>
            ),
          )}
        </Breadcrumbs>
      )}

      <MetadataMaintenanceAnchor
        libraryId={libraryId}
        activeFieldKey={missingFieldParam}
        onShowMissing={(fieldKey) => setMissingFieldFilter(fieldKey)}
        onClearFilter={() => setMissingFieldFilter(null)}
        refreshToken={anchorRefreshToken}
      />

      {missingFieldParam && (
        <Alert
          severity="info"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => setMissingFieldFilter(null)}>
              Filter aufheben
            </Button>
          }
        >
          Es werden nur Dokumente ohne Wert für „{coreMetadataFieldLabel(missingFieldParam)}"
          angezeigt — bibliotheksweit, unabhängig vom Ordner.
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

      {bulkResultMessage && (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setBulkResultMessage(null)}>
          {bulkResultMessage}
        </Alert>
      )}
      {/* #1068: Sammelzuweisung - the selection lives in this rights-filtered list, so a person
          can only ever pick documents they see here. */}
      {canEditMetadata && documents.length > 0 && (
        <Stack
          direction="row"
          spacing={1.5}
          sx={{ alignItems: 'center', mb: 1.5, flexWrap: 'wrap' }}
          role="toolbar"
          aria-label="Sammelzuweisung"
        >
          <FormControlLabel
            control={
              <Checkbox
                size="small"
                checked={allVisibleSelected}
                indeterminate={!allVisibleSelected && selectedDocumentIds.length > 0}
                onChange={toggleAllVisible}
              />
            }
            label="Alle auf dieser Seite auswählen"
          />
          <Typography variant="body2" color="text.secondary">
            {selectedDocumentIds.length} ausgewählt
          </Typography>
          <Button
            size="small"
            variant="outlined"
            disabled={selectedDocumentIds.length === 0}
            onClick={() => setBulkDialogOpen(true)}
          >
            Feld setzen
          </Button>
          {selectedDocumentIds.length > 0 && (
            <Button size="small" onClick={() => setSelectedDocumentIds([])}>
              Auswahl aufheben
            </Button>
          )}
        </Stack>
      )}

      {isLoading ? (
        <Typography color="text.secondary">Dokumente werden geladen …</Typography>
      ) : documents.length === 0 && folders.length === 0 ? (
        <Typography color="text.secondary">
          {searchInput
            ? 'Kein Dokument entspricht dieser Suche.'
            : 'Es sind noch keine Dokumente vorhanden.'}
        </Typography>
      ) : (
        <Stack spacing={1}>
          {/* #822: folders are rendered as rows ahead of the documents, mirroring a plain file
              browser - a folder's documentCount already counts its own subtree recursively
              (LibraryFolderListItem), the same number the delete confirmation below uses. */}
          {folders.map((folder) => (
            <Box
              key={folder.id}
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
              <Box
                role="button"
                tabIndex={0}
                aria-label={`Ordner ${folder.name} öffnen`}
                onClick={() => navigateToFolder(folder.id)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    navigateToFolder(folder.id)
                  }
                }}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.5,
                  minWidth: 0,
                  flexGrow: 1,
                  cursor: 'pointer',
                }}
              >
                <FolderIcon color="action" />
                <Typography sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
                  {folder.name}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {folder.documentCount} {folder.documentCount === 1 ? 'Dokument' : 'Dokumente'}
                </Typography>
              </Box>
              {canManageFolders && (
                <IconButton
                  aria-label={`Optionen für Ordner ${folder.name}`}
                  size="small"
                  onClick={(e) => setFolderMenu({ anchorEl: e.currentTarget, folder })}
                >
                  <MoreVertIcon fontSize="small" />
                </IconButton>
              )}
            </Box>
          ))}
          {documentGroups.map(({ parent, attachments }) => (
            <Fragment key={parent.id}>
              {renderDocumentRow(parent, { attachments })}
              {isGroupExpanded(parent.id) &&
                attachments.map((attachment) =>
                  renderDocumentRow(attachment, {
                    // Mail-in-Mail: an attachment whose direct parent is itself an attachment
                    // names that parent, since visually it sits flat under the top-level row.
                    viaFileName:
                      attachment.parentDocumentId && attachment.parentDocumentId !== parent.id
                        ? (documentsById.get(attachment.parentDocumentId)?.fileName ?? null)
                        : null,
                  }),
                )}
            </Fragment>
          ))}
        </Stack>
      )}

      {pageCount > 1 && (
        <Stack direction="row" sx={{ mt: 2, justifyContent: 'center' }}>
          <Pagination
            count={pageCount}
            page={(pageState?.page ?? 0) + 1}
            onChange={handlePageChange}
            // The app shell's own persistent navigation is also exposed as a <nav> element (role
            // "navigation") - without a distinguishing name, a test scoping to "the" navigation
            // region would ambiguously match both. German per AGENTS.md (every user-facing/
            // aria-label string is German).
            aria-label="Dokumentenliste blättern"
          />
        </Stack>
      )}

      <DocumentTextPreviewDialog previewDocument={previewDocument} onClose={closePreview} />
      {bulkDialogOpen && (
        <BulkMetadataDialog
          open
          onClose={() => setBulkDialogOpen(false)}
          libraryId={libraryId}
          documentIds={selectedDocumentIds}
          onDone={handleBulkDone}
        />
      )}
      <Snackbar
        open={downloadMessage != null}
        autoHideDuration={6000}
        onClose={clearDownloadMessage}
        message={downloadMessage}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      />

      {canManageFolders && (
        <Menu
          anchorEl={folderMenu?.anchorEl ?? null}
          open={folderMenu != null}
          onClose={() => setFolderMenu(null)}
        >
          <MenuItem
            onClick={() => {
              if (folderMenu) setRenameFolderTarget(folderMenu.folder)
              setFolderMenu(null)
            }}
          >
            Umbenennen
          </MenuItem>
          <MenuItem
            onClick={() => {
              const folder = folderMenu?.folder
              setFolderMenu(null)
              if (folder) void handleDeleteFolder(folder)
            }}
          >
            Löschen
          </MenuItem>
        </Menu>
      )}

      {canManageFolders && (
        <NewFolderDialog
          // Mirrors EditLibrarySourceDialog's own remount-on-open pattern (LibraryIndexingSection
          // below) - the dialog's internal field state always starts fresh when reopened.
          key={newFolderDialogOpen ? 'new-folder-open' : 'new-folder-closed'}
          open={newFolderDialogOpen}
          onClose={() => {
            setNewFolderDialogOpen(false)
            // #822 review, finding 3: without this, cancelling out of a dialog that just showed a
            // 409 conflict left documentStore.folderError set - the page-level Alert above (see
            // its own guard) would then flash that same message once the dialog was gone.
            clearFolderError()
          }}
          onCreate={handleCreateFolder}
        />
      )}

      {canManageFolders && (
        <RenameFolderDialog
          key={renameFolderTarget ? `rename-${renameFolderTarget.id}-open` : 'rename-closed'}
          open={renameFolderTarget != null}
          folder={renameFolderTarget}
          onClose={() => {
            setRenameFolderTarget(null)
            clearFolderError()
          }}
          onRename={(name) =>
            renameFolderTarget ? handleRenameFolder(renameFolderTarget, name) : Promise.resolve()
          }
        />
      )}
    </Box>
  )
}

interface NewFolderDialogProps {
  open: boolean
  onClose: () => void
  onCreate: (name: string) => Promise<void>
}

// #822: creates a folder directly under the folder currently being browsed (the caller,
// handleCreateFolder above, passes the URL's own folder param as the explicit parent) - kept
// local to this file since it is only ever used from LibraryDocumentsSection above.
function NewFolderDialog({ open, onClose, onCreate }: NewFolderDialogProps) {
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit() {
    if (!name.trim()) return
    setSaving(true)
    setError(null)
    try {
      await onCreate(name.trim())
      onClose()
    } catch (err) {
      // #822: surfaces a 409 name conflict (or any other backend rejection) directly in the
      // dialog, not as a page-level alert - the conflicting name is still right there to correct.
      setError(err instanceof Error ? err.message : 'Ordner konnte nicht angelegt werden')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Neuer Ordner</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Box sx={{ pt: 0.5 }}>
          <FieldLabel htmlFor="new-folder-name">Ordnername</FieldLabel>
          <TextField
            id="new-folder-name"
            fullWidth
            size="small"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void handleSubmit()
            }}
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={saving || !name.trim()}
        >
          {saving ? 'Wird angelegt …' : 'Anlegen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

interface RenameFolderDialogProps {
  open: boolean
  folder: LibraryFolderListItem | null
  onClose: () => void
  onRename: (name: string) => Promise<void>
}

function RenameFolderDialog({ open, folder, onClose, onRename }: RenameFolderDialogProps) {
  const [name, setName] = useState(folder?.name ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit() {
    if (!name.trim()) return
    setSaving(true)
    setError(null)
    try {
      await onRename(name.trim())
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ordner konnte nicht umbenannt werden')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Ordner umbenennen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Box sx={{ pt: 0.5 }}>
          <FieldLabel htmlFor="rename-folder-name">Ordnername</FieldLabel>
          <TextField
            id="rename-folder-name"
            fullWidth
            size="small"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void handleSubmit()
            }}
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={saving || !name.trim()}
        >
          {saving ? 'Wird umbenannt …' : 'Umbenennen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

interface LibrarySpacesSectionProps {
  libraryId: string
}

// #203: the owner-facing "bereitgestellt in" view - every space this library is associated with,
// never filtered by the caller's own space membership (docs/features/spaces-and-assets.md#assets-
// in-einen-space-assoziieren: "Der Eigentümer des Assets sieht alle Assoziationen und kann jede
// davon jederzeit einseitig lösen"). Only rendered for MANAGER/OWNER (see canEdit above), the same
// threshold GET /v1/libraries/{libraryId}/spaces itself requires.
function LibrarySpacesSection({ libraryId }: LibrarySpacesSectionProps) {
  const [associations, setAssociations] = useState<LibrarySpaceAssociationResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getLibrarySpaceAssociations(libraryId)
      .then((data) => {
        if (!cancelled) setAssociations(data)
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Laden fehlgeschlagen')
      })
    return () => {
      cancelled = true
    }
  }, [libraryId])

  async function handleDetach(spaceId: string, spaceName: string) {
    if (!window.confirm(`Bereitstellung im Space "${spaceName}" lösen?`)) return
    setError(null)
    try {
      await detachSpaceLibrary(spaceId, libraryId)
      setAssociations((prev) => prev?.filter((a) => a.spaceId !== spaceId) ?? null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Lösen fehlgeschlagen')
    }
  }

  return (
    <Box sx={{ mb: 5, maxWidth: 760 }}>
      <SectionHead>Bereitgestellt in</SectionHead>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {associations === null ? (
        <Typography color="text.secondary">Wird geladen …</Typography>
      ) : associations.length === 0 ? (
        <Typography color="text.secondary">
          Diese Bibliothek ist derzeit keinem Space zugeordnet.
        </Typography>
      ) : (
        <Stack spacing={1}>
          {associations.map((association) => (
            <Box
              key={association.spaceId}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 1.5,
                flexWrap: 'wrap',
              }}
            >
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <Typography>{association.spaceName}</Typography>
                {association.narrowerReaderCircle && (
                  <Tooltip title="Mindestens ein Mitglied dieses Space hat keinen eigenen Lesezugriff auf diese Bibliothek.">
                    <Chip label="nicht alle Mitglieder lesen" size="small" color="warning" />
                  </Tooltip>
                )}
              </Stack>
              <Button
                color="error"
                size="small"
                onClick={() => void handleDetach(association.spaceId, association.spaceName)}
              >
                Lösen
              </Button>
            </Box>
          ))}
        </Stack>
      )}
    </Box>
  )
}

interface LibraryIndexingSectionProps {
  libraryId: string
  library: {
    name: string
    description?: string | null
    visibility: LibraryVisibility
    listed: boolean
    sourceType: 'FILESYSTEM' | 'HTTP_DIRECTORY' | 'RSS_FEED' | 'UPLOAD'
    sourcePath?: string | null
    sourceUrl?: string | null
    sourceProxy?: string | null
    sourceInsecureSsl?: boolean | null
    sourceCredentialsSet?: boolean | null
    schedule?: LibrarySchedule | null
    lastScheduledRunsFailed?: boolean | null
  }
  canTrigger: boolean
  canEditSource: boolean
}

function LibraryIndexingSection({
  libraryId,
  library,
  canTrigger,
  canEditSource,
}: LibraryIndexingSectionProps) {
  const [editSourceOpen, setEditSourceOpen] = useState(false)
  const [editScheduleOpen, setEditScheduleOpen] = useState(false)
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
    <Box sx={{ mb: 5, maxWidth: 760 }}>
      <Stack
        direction="row"
        sx={{
          alignItems: 'center',
          justifyContent: 'space-between',
          borderBottom: 1,
          borderColor: 'divider',
          pb: 1,
          mb: 2,
        }}
      >
        <SectionHead underline={false}>Quellkonfiguration</SectionHead>
        {canEditSource && (
          <Button
            size="small"
            onClick={() => setEditSourceOpen(true)}
            aria-label="Quellkonfiguration bearbeiten"
          >
            Bearbeiten
          </Button>
        )}
      </Stack>

      {/* #507: sourcePath/sourceUrl/sourceProxy expose internal server paths, source URLs and
          proxy hosts - the backend now only serves them to a caller with at least MANAGER
          (canEditSource, the same threshold "Bearbeiten" above uses), so a VIEWER's library
          object simply carries none of these fields. This block mirrors that on the display side
          rather than rendering a misleading "—"/"nicht konfiguriert" for data that was never
          sent. */}
      {canEditSource ? (
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
      ) : (
        // Deliberately avoids the word "Quellkonfiguration" itself - several other tests on this
        // page match the section heading with the loose /quellkonfiguration/i and would otherwise
        // ambiguously match this hint too.
        //
        // #507 code review, finding 3: "Bearbeitende" reads as EDITOR in this app's own role
        // vocabulary (assetRoleLabel maps EDITOR to "Bearbeiter") - the exact role this hint's
        // gate (canEditSource, the MANAGER/OWNER bar) excludes. "Verwaltende" mirrors MANAGER's
        // own label ("Verwalter") instead.
        <Alert severity="info" sx={{ mb: 2 }}>
          Die Verbindungsdaten sind nur für Verwaltende (Verwalter oder Eigentümer) sichtbar.
        </Alert>
      )}

      {canEditSource && (
        <EditLibrarySourceDialog
          // Forces a remount every time the dialog opens, so its internal field state always
          // starts fresh from the current library configuration without an effect calling
          // setState on open (react-hooks/set-state-in-effect) - mirrors LibraryDocumentsSection's
          // key={libraryId} above.
          key={editSourceOpen ? 'source-edit-open' : 'source-edit-closed'}
          open={editSourceOpen}
          onClose={() => setEditSourceOpen(false)}
          libraryId={libraryId}
          library={library}
        />
      )}

      <Divider sx={{ mb: 2 }} />

      {/* #485: Zeitplan - nur für Verwaltende sichtbar/bearbeitbar, dieselbe Schwelle wie die
          Quellkonfiguration oben (canEditSource). */}
      {canEditSource && (
        <Box sx={{ mb: 2 }}>
          <Stack
            direction="row"
            sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
          >
            <Typography variant="subtitle1">Zeitplan</Typography>
            <Button
              size="small"
              onClick={() => setEditScheduleOpen(true)}
              aria-label="Zeitplan bearbeiten"
            >
              Bearbeiten
            </Button>
          </Stack>
          <Typography variant="body2">
            {scheduleFrequencyLabel(library.schedule?.frequency ?? 'DISABLED')}
          </Typography>
          {library.schedule?.nextRunAt && (
            <Typography variant="body2" color="text.secondary">
              Nächster geplanter Lauf:{' '}
              {new Date(library.schedule.nextRunAt).toLocaleString('de-DE', {
                dateStyle: 'medium',
                timeStyle: 'short',
              })}
            </Typography>
          )}
          {library.lastScheduledRunsFailed && (
            <Alert severity="warning" sx={{ mt: 1 }}>
              Die letzten geplanten Läufe dieser Bibliothek sind fehlgeschlagen. Der Zeitplan bleibt
              aktiv und versucht es beim nächsten Termin erneut.
            </Alert>
          )}
          <EditLibraryScheduleDialog
            // Mirrors EditLibrarySourceDialog's own remount-on-open pattern above, so the dialog's
            // internal field state always starts fresh from the current schedule.
            key={editScheduleOpen ? 'schedule-edit-open' : 'schedule-edit-closed'}
            open={editScheduleOpen}
            onClose={() => setEditScheduleOpen(false)}
            libraryId={libraryId}
            schedule={library.schedule}
            library={library}
          />
        </Box>
      )}

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
              <Typography variant="body2" color="text.secondary" role="status">
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
    </Box>
  )
}

function formatRunTimestamp(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

function runStatusChipColor(
  status: IndexingRunResponse['status'],
): 'success' | 'warning' | 'error' | 'default' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'error'
  if (status === 'RUNNING') return 'warning'
  return 'default'
}

function runStatusLabel(status: IndexingRunResponse['status']): string {
  if (status === 'COMPLETED') return 'Abgeschlossen'
  if (status === 'FAILED') return 'Fehlgeschlagen'
  if (status === 'RUNNING') return 'Läuft'
  return 'Nie ausgeführt'
}

interface LibraryIndexingHistorySectionProps {
  libraryId: string
}

// A stable module-level reference (not a fresh `[]` literal per render) - a Zustand selector must
// never return a new array/object identity for an unchanged state slice, or useSyncExternalStore
// treats every render as a change and re-renders in an infinite loop ("getSnapshot should be
// cached" warning). Mirrors IDLE_RUN_STATE's own role for runsByLibrary above.
const EMPTY_RUN_HISTORY: IndexingRunResponse[] = []

// #513: einklappbares Protokoll der letzten Läufe einer Bibliothek - Kopfdaten immer sichtbar,
// die Ereignisliste (Kategorie/Meldung/Referenz je übersprungenem oder fehlgeschlagenem Element)
// nur nach dem Aufklappen. Getrennt von LibraryIndexingSection oben, deren runsByLibrary nur den
// aktuellen/letzten Lauf für die Fortschrittsanzeige trägt.
function LibraryIndexingHistorySection({ libraryId }: LibraryIndexingHistorySectionProps) {
  const runs = useIndexingStore((s) => s.runHistoryByLibrary[libraryId] ?? EMPTY_RUN_HISTORY)
  const loadRunHistory = useIndexingStore((s) => s.loadRunHistory)

  useEffect(() => {
    void loadRunHistory(libraryId)
  }, [libraryId, loadRunHistory])

  return (
    <Box sx={{ mb: 5, maxWidth: 760 }}>
      <SectionHead>Letzte Indizierungsläufe</SectionHead>

      {runs.length === 0 ? (
        <Typography color="text.secondary">Es liegen noch keine Läufe vor.</Typography>
      ) : (
        <Stack spacing={1}>
          {runs.map((run) => (
            <Accordion key={run.id} disableGutters variant="outlined">
              <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                <Stack
                  direction="row"
                  spacing={1.5}
                  sx={{ alignItems: 'center', flexWrap: 'wrap', width: '100%' }}
                >
                  <Chip
                    label={runStatusLabel(run.status)}
                    size="small"
                    color={runStatusChipColor(run.status)}
                    variant="outlined"
                  />
                  <Typography variant="body2" color="text.secondary">
                    {formatRunTimestamp(run.startedAt)}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    {run.documentCount} verarbeitet
                    {run.documentsSkipped > 0 ? `, ${run.documentsSkipped} übersprungen` : ''}
                    {run.documentsFailed > 0 ? `, ${run.documentsFailed} fehlgeschlagen` : ''}
                  </Typography>
                  {run.events.length > 0 && (
                    <Chip
                      label={`${run.events.length} Ereignis${run.events.length === 1 ? '' : 'se'}`}
                      size="small"
                    />
                  )}
                </Stack>
              </AccordionSummary>
              <AccordionDetails>
                {run.message && (
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
                    {run.message}
                  </Typography>
                )}
                {run.events.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    Dieser Lauf hat keine übersprungenen, fehlgeschlagenen oder abweichend erkannten
                    Elemente protokolliert.
                  </Typography>
                ) : (
                  <Stack spacing={1}>
                    {run.events.map((event, index) => (
                      // #513 acceptance criteria: message/reference are already German and
                      // scrubbed of raw challenge-/redirect-URLs by the backend
                      // (IndexingRunEvent's Javadoc) - this list renders them as-is. Events carry
                      // no id of their own, so the key combines the run and the position within
                      // its (stable, backend-ordered) event list.
                      <Box key={`${run.id}-${index}`}>
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
                          <Chip
                            label={indexingRunEventCategoryLabel(event.category)}
                            size="small"
                            variant="outlined"
                          />
                          <Typography variant="body2">{event.message}</Typography>
                        </Stack>
                        {event.reference && (
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ display: 'block', wordBreak: 'break-word' }}
                          >
                            {event.reference}
                          </Typography>
                        )}
                      </Box>
                    ))}
                    {run.eventsTruncatedCount > 0 && (
                      <Typography variant="caption" color="text.secondary">
                        … und {run.eventsTruncatedCount} weitere
                      </Typography>
                    )}
                  </Stack>
                )}
              </AccordionDetails>
            </Accordion>
          ))}
        </Stack>
      )}
    </Box>
  )
}
