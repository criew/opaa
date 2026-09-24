import { useEffect, useRef, useState } from 'react'
import type { DragEvent } from 'react'
import { useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Breadcrumbs from '@mui/material/Breadcrumbs'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Link from '@mui/material/Link'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import CreateNewFolderIcon from '@mui/icons-material/CreateNewFolder'
import DeleteIcon from '@mui/icons-material/Delete'
import DriveFolderUploadIcon from '@mui/icons-material/DriveFolderUpload'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type {
  BulkMetadataValueResponse,
  ConfluenceSpaceRef,
  DocumentSourceType,
  LibraryDocumentResponse,
  LibraryFolderListItem,
} from '../../types/api'
import { getLibraryFolder } from '../../services/api'
import { confirmAction } from '../../stores/confirmStore'
import { DEFAULT_PAGE_SIZE, useDocumentStore } from '../../stores/documentStore'
import { useDocumentPreview } from '../../hooks/useDocumentPreview'
import {
  directoryPathFromWebkitRelativePath,
  filterAcceptedFiles,
  resolveDroppedItems,
} from '../../utils/directoryEntries'
import DocumentTextPreviewDialog from '../DocumentTextPreviewDialog'
import BulkMetadataDialog from '../metadata/BulkMetadataDialog'
import DocumentMetadataPanel from '../metadata/DocumentMetadataPanel'
import MetadataMaintenanceAnchor from '../metadata/MetadataMaintenanceAnchor'
import { coreMetadataFieldLabel } from '../metadata/metadataValues'
import FieldLabel from '../wizard/FieldLabel'
import LibraryDocumentList from './LibraryDocumentList'

// Mirrors what the registered DocumentFormats admit (DocumentFormat#admittedFormats,
// backend/src/main/java/io/opaa/indexing/format) - only a client-side hint for the file picker;
// the backend remains the authority on what is accepted.
// Exported so directoryEntries.test.ts exercises filterAcceptedFiles against the same list rather
// than a copy that can silently drift out of sync with it.
export const ACCEPTED_FILE_EXTENSIONS =
  '.csv,.doc,.docx,.eml,.html,.md,.msg,.odp,.ods,.odt,.pdf,.pptx,.txt,.xlsx'

/** #823: the directory portion within a dropped/selected folder tree, e.g. "Protokolle/2026". */
interface UploadEntry {
  file: File
  relativePath?: string
}

/** The most documents one bulk delete may name - mirrors BulkDocumentDeleteRequest's own ceiling. */
const MAX_BULK_DELETE = 200

export interface LibraryDocumentsSectionProps {
  libraryId: string
  sourceType: DocumentSourceType
  canManage: boolean
  /** Bumped by the page when an indexing run finishes - reloads the current view in place. */
  refreshToken: number
  /** The library's selected spaces (CONFLUENCE) - resolves a row's space key to its name. */
  confluenceSpaces?: ConfluenceSpaceRef[] | null
  onDocumentsChanged: () => void
}

/**
 * The Reiter „Dokumente" of a Wissensbibliothek: the store wiring, the upload affordances of an
 * UPLOAD library, the folder dialogs and the metadata actions around {@link LibraryDocumentList},
 * which renders the list itself for every source type alike (#1943).
 */
export default function LibraryDocumentsSection({
  libraryId,
  sourceType,
  canManage,
  refreshToken,
  confluenceSpaces,
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
  const removeDocuments = useDocumentStore((s) => s.removeDocuments)
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
  // #1069: the Pflege-Anker's filter is URL state (?missingField=<core field key>).
  const missingFieldParam = searchParams.get('missingField')

  const [isDragActive, setIsDragActive] = useState(false)
  const [searchInput, setSearchInput] = useState('')
  // #1184 (ADR-0022, Entscheidung 5): per-parent expansion of the attachment group. An absent key
  // falls back to the search default - during a search every group starts expanded so an
  // attachment hit is immediately visible.
  const [attachmentGroupExpansion, setAttachmentGroupExpansion] = useState<Record<string, boolean>>(
    {},
  )
  const [newFolderDialogOpen, setNewFolderDialogOpen] = useState(false)
  const [renameFolderTarget, setRenameFolderTarget] = useState<LibraryFolderListItem | null>(null)
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
  // #738/#780: opening the original is a read-only, per-click action that never touches the store -
  // its failure and download feedback surface as global popup notifications (guidelines 5.9).
  const { previewDocument, closePreview, openDocument } = useDocumentPreview()

  const isUploadLibrary = sourceType === 'UPLOAD'
  // ADR-0018/#443: a connector document only ever leaves the index because its source did too -
  // deleting the row here does not touch that source, so the next run adds it right back. Rather
  // than offer a delete that silently undoes itself, the action is hidden for connector libraries.
  const canDelete = isUploadLibrary && canManage
  // #822: folder management is UPLOAD-only, same EDITOR-or-above threshold as document upload.
  const canManageFolders = isUploadLibrary && canManage
  // #1068: whoever may edit the library's documents may correct their metadata - for every
  // sourceType, since a metadata value hangs at the document row, not at the file.
  const canEditMetadata = canManage

  useEffect(() => {
    // #506 review, finding 2: uploadErrors/deleteError/error are not keyed by library - without
    // this reset, a failure left over from a previously viewed library would keep showing.
    reset()
    return () => {
      stopPolling(libraryId)
      // #517 code review, nit 1: a pending debounce would otherwise fire for the previous library.
      if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    }
  }, [libraryId, stopPolling, reset])

  useEffect(() => {
    // #822: reacts to the URL's folder param too - a breadcrumb click or a folder row navigates by
    // changing the URL, and this is what turns that into a fresh load for the requested folder.
    void loadDocuments(libraryId, {
      page: 0,
      size: DEFAULT_PAGE_SIZE,
      q: '',
      folderId: folderIdParam,
      missingMetadataField: missingFieldParam,
    })
  }, [libraryId, folderIdParam, missingFieldParam, loadDocuments])

  // A finished indexing run bumps refreshToken: reload the view the user is looking at - same
  // page, search and folder. The ref guard keeps unrelated dependency changes from re-firing it.
  const lastRefreshTokenRef = useRef(refreshToken)
  useEffect(() => {
    if (refreshToken === lastRefreshTokenRef.current) return
    lastRefreshTokenRef.current = refreshToken
    void loadDocuments(libraryId, {})
  }, [refreshToken, libraryId, loadDocuments])

  useEffect(() => {
    // #822: an invalid/foreign folderId is caught by loadDocuments, which falls back to the root -
    // this just corrects the URL to match, dropping the dead ?folder= param.
    if (folderNotFoundMessage && folderIdParam) {
      const next = new URLSearchParams(searchParams)
      next.delete('folder')
      setSearchParams(next, { replace: true })
    }
  }, [folderNotFoundMessage, folderIdParam, searchParams, setSearchParams])

  useEffect(() => {
    // #823: `webkitdirectory` (plus its older aliases) is not part of React's <input> typings, so
    // it has to be set on the DOM node directly rather than passed as a JSX prop.
    const node = folderInputRef.current
    if (!node) return
    node.setAttribute('webkitdirectory', '')
    node.setAttribute('directory', '')
    node.setAttribute('mozdirectory', '')
  }, [])

  const documents = documentsByLibrary[libraryId] ?? []
  const pageState = pageStateByLibrary[libraryId]
  // #822 review, finding 6a: folders are not paged - the backend returns the folder's entire set of
  // direct subfolders on every page, so they belong on the first page only.
  const isFirstPage = (pageState?.page ?? 0) === 0
  const folders = isFirstPage ? (foldersByLibrary[libraryId] ?? []) : []
  const breadcrumb = breadcrumbByLibrary[libraryId] ?? []
  const isSearchActive = Boolean(pageState?.q)

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

  /**
   * #1943: the Sammellöschen of an upload library. The confirmation names the number, and the
   * answer is reported as it comes back - a partial success says how many stayed and why, instead
   * of leaving the person to compare the list against their own selection.
   */
  async function handleBulkDelete() {
    const ids = selectedDocumentIds.slice(0, MAX_BULK_DELETE)
    if (ids.length === 0) return
    const confirmed = await confirmAction({
      question:
        ids.length === 1 ? '1 Dokument löschen?' : `${ids.length} ausgewählte Dokumente löschen?`,
      consequence:
        'Die Dokumente werden mit ihren Anhängen aus dieser Bibliothek entfernt. Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      const result = await removeDocuments(libraryId, ids)
      if (!result) return
      const deleted = result.deletedDocumentIds.length
      const message =
        result.failures.length === 0
          ? `${deleted} ${deleted === 1 ? 'Dokument' : 'Dokumente'} gelöscht.`
          : `${deleted} von ${ids.length} Dokumenten gelöscht. Nicht gelöscht: ${result.failures
              .map((failure) => failure.message)
              .join('; ')}.`
      setBulkResultMessage(message)
      setSelectedDocumentIds([])
      setAnchorRefreshToken((previous) => previous + 1)
      onDocumentsChanged()
    } catch {
      // Die Fehlermeldung steht bereits über documentStore.deleteError.
    }
  }

  // #1069: a corrected document leaves the anchor - and, while its worklist is open, the list too.
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
  // param - the load effect above reacts to that change. Also clears any in-flight search.
  function navigateToFolder(folderId: string | null) {
    setSearchInput('')
    setSelectedDocumentIds([])
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    // #822 review, finding 1: a search hit's folderPath link can name the very folder already
    // loaded - the URL then does not change, so the load effect never fires and the stale,
    // still-bibliotheksweit results would keep showing. Reloading explicitly is the only way.
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
    // #822 review, finding 6c: derives the parent explicitly from the URL's own folder param, so a
    // click before the deep-linked folder's first load has resolved cannot create it at the root.
    await createFolder(libraryId, name, folderIdParam)
  }

  async function handleRenameFolder(folder: LibraryFolderListItem, name: string) {
    await renameFolder(libraryId, folder.id, name)
  }

  async function handleDeleteFolder(folder: LibraryFolderListItem) {
    // #822 review, finding 4: re-fetches the folder right before confirming, so the confirmation
    // names the current recursive document count rather than a possibly stale one from the list.
    let documentCount = folder.documentCount
    try {
      const current = await getLibraryFolder(libraryId, folder.id)
      documentCount = current.documentCount
    } catch {
      // Falls back to the (possibly stale) count already shown in the row above.
    }
    const question =
      documentCount > 0
        ? `Ordner "${folder.name}" und ${documentCount} ${
            documentCount === 1 ? 'Dokument' : 'Dokumente'
          } löschen?`
        : `Ordner "${folder.name}" löschen?`
    const confirmed = await confirmAction({
      question,
      consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
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
    // themselves, right before adding their own skipped-files summary.
    if (!options?.skipClear) {
      clearUploadErrors()
    }
    for (const entry of entries) {
      try {
        await uploadNewDocument(libraryId, entry.file, entry.relativePath || undefined)
        onDocumentsChanged()
      } catch {
        // Der Fehler landet bereits gesammelt in documentStore.uploadErrors; die Schleife läuft
        // weiter, damit ein Fehler bei einer Datei nicht die übrigen blockiert.
      }
    }
  }

  // #823 review, Befund 2: one collective German message for every file skipped client-side before
  // ever reaching the backend - naming three hundred rejected files would be worse than none.
  function reportSkippedFiles(skippedCount: number, failedCount: number) {
    const parts: string[] = []
    if (skippedCount > 0) {
      parts.push(
        `${skippedCount} ${skippedCount === 1 ? 'Datei wurde' : 'Dateien wurden'} wegen eines nicht unterstützten Formats übersprungen`,
      )
    }
    if (failedCount > 0) {
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
    // the items list has to be captured here, inside this synchronous handler.
    const items = event.dataTransfer.items
    if (items && items.length > 0) {
      resolveDroppedItems(items)
        .then(({ files, failedCount }) => {
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
    const confirmed = await confirmAction({
      question: `Dokument "${document.fileName}" löschen?`,
      consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      await removeDocument(libraryId, document.id)
      onDocumentsChanged()
    } catch {
      // Fehlermeldung wird bereits über documentStore.deleteError angezeigt.
    }
  }

  // #738/#747: every sourceType opens through GET .../content (fetched as a Blob, since the
  // endpoint is Bearer-authenticated and a plain <a href> cannot carry that token).
  async function handleOpenOriginal(document: LibraryDocumentResponse) {
    await openDocument({
      id: document.id,
      fileName: document.fileName,
      sourceType: document.sourceType,
      sourceUrl: document.sourceUrl,
      sourceEntryUrl: document.sourceEntryUrl,
    })
  }

  // #1068: the selection is bound to the list the person is looking at - a folder, search or page
  // change drops it, so a Sammelaktion never acts on documents nobody sees.
  function handleSearchChange(value: string) {
    setSearchInput(value)
    setSelectedDocumentIds([])
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    searchDebounceRef.current = setTimeout(() => {
      void loadDocuments(libraryId, { page: 0, q: value })
    }, 300)
  }

  return (
    <Box sx={{ mb: 5 }}>
      {/* #1943: one sentence on why a connector bestand has no per-document delete, once - the
          list below repeats neither the sentence nor the action. */}
      {!isUploadLibrary && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Dieser Bestand stammt aus der Quelle; einzelne Dokumente lassen sich hier nicht löschen —
          ein gelöschtes Dokument käme mit dem nächsten Indizierungslauf zurück.
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
          {/* #823: webkitdirectory is set imperatively on this node (see the useEffect above). */}
          <input
            ref={folderInputRef}
            type="file"
            multiple
            hidden
            // #823 review, Befund 2: browsers do not reliably enforce `accept` for a
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

      {bulkResultMessage && (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setBulkResultMessage(null)}>
          {bulkResultMessage}
        </Alert>
      )}

      <LibraryDocumentList
        documents={documents}
        folders={folders}
        pageState={pageState}
        isLoading={isLoading}
        confluenceSpaces={confluenceSpaces}
        emptyMessage={
          isUploadLibrary
            ? 'Es sind noch keine Dokumente vorhanden.'
            : 'Es sind noch keine Dokumente vorhanden. Der erste Indizierungslauf startet über „Jetzt indizieren“ oben auf der Seite.'
        }
        breadcrumb={
          // #1943: the Ordnerpfad stands directly above the search field, where the list it
          // describes begins - not above the Pflege-Anker, which is bibliotheksweit.
          // breadcrumb.length is 0 at the root and while a search is active (ADR-0020).
          breadcrumb.length > 0 ? (
            <Breadcrumbs aria-label="Ordnerpfad" sx={{ mb: 2 }}>
              <Link component="button" underline="hover" onClick={() => navigateToFolder(null)}>
                Wurzel
              </Link>
              {breadcrumb.map((item, index) =>
                index === breadcrumb.length - 1 ? (
                  <Typography key={item.id} sx={{ color: 'text.primary' }}>
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
          ) : undefined
        }
        searchInput={searchInput}
        onSearchChange={handleSearchChange}
        onPageChange={(page) => {
          setSelectedDocumentIds([])
          void loadDocuments(libraryId, { page: page - 1 })
        }}
        onPageSizeChange={(size) => void loadDocuments(libraryId, { page: 0, size })}
        selectable={canEditMetadata}
        selectedIds={selectedDocumentIds}
        onToggleSelected={toggleSelected}
        onToggleAllVisible={toggleAllVisible}
        onClearSelection={() => setSelectedDocumentIds([])}
        bulkActions={
          // #1943: every single-row action that is permitted for all selected documents - "Feld
          // setzen" everywhere, "Löschen" wherever a single row offers it too.
          <>
            <Button
              size="small"
              variant="outlined"
              disabled={selectedDocumentIds.length === 0}
              onClick={() => setBulkDialogOpen(true)}
            >
              Feld setzen
            </Button>
            {canDelete && (
              <Button
                size="small"
                variant="outlined"
                color="error"
                disabled={selectedDocumentIds.length === 0}
                onClick={() => void handleBulkDelete()}
              >
                Löschen
              </Button>
            )}
          </>
        }
        onOpenFolder={navigateToFolder}
        renderFolderActions={
          canManageFolders
            ? (folder) => (
                <Tooltip title="Ordner umbenennen oder löschen">
                  <IconButton
                    aria-label={`Optionen für Ordner ${folder.name}`}
                    size="small"
                    onClick={(e) => setFolderMenu({ anchorEl: e.currentTarget, folder })}
                  >
                    <MoreVertIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              )
            : undefined
        }
        renderRowActions={
          canDelete
            ? (document) => (
                <Tooltip title="Dokument löschen">
                  <IconButton
                    aria-label={`Dokument ${document.fileName} löschen`}
                    size="small"
                    onClick={() => void handleDelete(document)}
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              )
            : undefined
        }
        metadataOpenById={metadataOpenById}
        onToggleMetadata={toggleMetadataPanel}
        renderMetadataPanel={(document) => (
          <DocumentMetadataPanel
            libraryId={libraryId}
            documentId={document.id}
            fileName={document.fileName}
            canEdit={canEditMetadata}
            refreshToken={metadataRefreshToken}
            onValueChanged={handleMetadataValueChanged}
          />
        )}
        onOpenOriginal={(document) => void handleOpenOriginal(document)}
        isGroupExpanded={isGroupExpanded}
        onToggleAttachmentGroup={toggleAttachmentGroup}
      />

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
          // Remount on open, so the dialog's internal field state always starts fresh.
          key={newFolderDialogOpen ? 'new-folder-open' : 'new-folder-closed'}
          open={newFolderDialogOpen}
          onClose={() => {
            setNewFolderDialogOpen(false)
            // #822 review, finding 3: without this, cancelling out of a dialog that just showed a
            // 409 conflict left documentStore.folderError set, flashing it on the page behind.
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

// #822: creates a folder directly under the folder currently being browsed - kept local to this
// file since it is only ever used from the section above.
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
      // #822: surfaces a 409 name conflict directly in the dialog, where the name is still there
      // to correct, rather than as a page-level alert.
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
