import { useEffect, useImperativeHandle, useRef, useState, type DragEvent, type Ref } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import IconButton from '@mui/material/IconButton'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import type { SourceTypeKey, LibraryDocumentResponse } from '@/shared/api/types'
import type { ConfluenceSpaceRef } from '../utils/confluenceSource'
import { confirmAction } from '@/shared/stores/confirmStore'
import { DEFAULT_PAGE_SIZE, useDocumentStore } from '../stores/documentStore'
import { useDocumentPreview } from '../hooks/useDocumentPreview'
import { useBulkDocumentActions } from '../hooks/useBulkDocumentActions'
import { useDocumentSearch } from '../hooks/useDocumentSearch'
import { useDocumentSelection } from '../hooks/useDocumentSelection'
import { useDocumentsUrlState } from '../hooks/useDocumentsUrlState'
import { useDocumentUpload } from '../hooks/useDocumentUpload'
import { useLibraryFolderActions } from '../hooks/useLibraryFolderActions'
import DocumentTextPreviewDialog from './DocumentTextPreviewDialog'
import BulkMetadataDialog from './metadata/BulkMetadataDialog'
import DocumentMetadataPanel from './metadata/DocumentMetadataPanel'
import { CORE_METADATA_FIELDS } from './metadata/metadataValues'
import LibraryDocumentBulkActions from './LibraryDocumentBulkActions'
import { EmptyLine } from '@/shared/ui/TabSection'
import LibraryDocumentList from './LibraryDocumentList'
import LibraryDocumentsNotices from './LibraryDocumentsNotices'
import LibraryDocumentUploadArea, { UploadToolbarActions } from './LibraryDocumentUploadArea'
import LibraryFolderBreadcrumb from './LibraryFolderBreadcrumb'
import LibraryFolderDialogs from './LibraryFolderDialogs'

export { ACCEPTED_FILE_EXTENSIONS } from '../hooks/useDocumentUpload'

export interface LibraryDocumentsSectionProps {
  libraryId: string
  sourceType: SourceTypeKey
  canManage: boolean
  /** Bumped by the page when an indexing run finishes - reloads the current view in place. */
  refreshToken: number
  /** The library's selected spaces (CONFLUENCE) - resolves a row's space key to its name. */
  confluenceSpaces?: ConfluenceSpaceRef[] | null
  onDocumentsChanged: () => void
  /** A connector library's first run; offered in the empty state only when given. */
  onIndexNow?: () => void
  /** Opens the file picker of an upload library - the head's main action uses it. */
  uploadPickerRef?: Ref<{ open: () => void; openFolder: () => void }>
}

/**
 * The Reiter „Dokumente" of a Wissensbibliothek: the store wiring, the upload affordances of an
 * UPLOAD library, the folder dialogs and the metadata actions around {@link LibraryDocumentList},
 * which renders the list itself for every source type alike.
 */
export default function LibraryDocumentsSection({
  libraryId,
  sourceType,
  canManage,
  refreshToken,
  confluenceSpaces,
  onDocumentsChanged,
  onIndexNow,
  uploadPickerRef,
}: LibraryDocumentsSectionProps) {
  const documentsByLibrary = useDocumentStore((s) => s.documentsByLibrary)
  const pageStateByLibrary = useDocumentStore((s) => s.pageStateByLibrary)
  const foldersByLibrary = useDocumentStore((s) => s.foldersByLibrary)
  const breadcrumbByLibrary = useDocumentStore((s) => s.breadcrumbByLibrary)
  const isLoading = useDocumentStore((s) => s.isLoading)
  const folderNotFoundMessage = useDocumentStore((s) => s.folderNotFoundMessage)
  const loadDocuments = useDocumentStore((s) => s.loadDocuments)
  const removeDocument = useDocumentStore((s) => s.removeDocument)
  const stopPolling = useDocumentStore((s) => s.stopPolling)
  const reset = useDocumentStore((s) => s.reset)

  const documents = documentsByLibrary[libraryId] ?? []
  const pageState = pageStateByLibrary[libraryId]
  // Folders are not paged - the backend returns the folder's entire set of
  // direct subfolders on every page, so they belong on the first page only.
  const isFirstPage = (pageState?.page ?? 0) === 0
  const folders = isFirstPage ? (foldersByLibrary[libraryId] ?? []) : []
  const breadcrumb = breadcrumbByLibrary[libraryId] ?? []
  const isSearchActive = Boolean(pageState?.q)

  const { selectedDocumentIds, setSelectedDocumentIds, toggleSelected, toggleAllVisible } =
    useDocumentSelection(documents)
  const { searchInput, clearSearch, handleSearchChange } = useDocumentSearch(
    libraryId,
    setSelectedDocumentIds,
  )
  const {
    searchParams,
    setSearchParams,
    folderIdParam,
    metadataEnabled,
    missingFieldParam,
    setMissingFieldFilter,
    navigateToFolder,
  } = useDocumentsUrlState(libraryId, leaveList)
  const folderActions = useLibraryFolderActions(libraryId, folderIdParam, onDocumentsChanged)
  const { newFolderDialogOpen, setNewFolderDialogOpen, renameFolderTarget, setFolderMenu } =
    folderActions

  // ADR-0022, Entscheidung 5: per-parent expansion of the attachment group. An absent key
  // falls back to the search default - during a search every group starts expanded so an
  // attachment hit is immediately visible.
  const [attachmentGroupExpansion, setAttachmentGroupExpansion] = useState<Record<string, boolean>>(
    {},
  )
  const [metadataOpenById, setMetadataOpenById] = useState<Record<string, boolean>>({})
  const [metadataRefreshToken, setMetadataRefreshToken] = useState(0)
  // The bulk actions bump a recount token; the fill level lives in the Metadaten tab (D22), so
  // nothing on this tab reads it.
  const [, setAnchorRefreshToken] = useState(0)
  const bulk = useBulkDocumentActions({
    libraryId,
    selectedDocumentIds,
    setSelectedDocumentIds,
    setMetadataRefreshToken,
    setAnchorRefreshToken,
    onDocumentsChanged,
  })
  const { bulkDialogOpen, setBulkDialogOpen, bulkResult, setBulkResult, handleBulkDone } = bulk
  // Opening the original is a read-only, per-click action that never touches the store -
  // its failure and download feedback surface as global popup notifications (guidelines 5.9).
  const { previewDocument, closePreview, openDocument } = useDocumentPreview()

  const isUploadLibrary = sourceType === 'UPLOAD'
  // ADR-0018/#443: a connector document only ever leaves the index because its source did too -
  // deleting the row here does not touch that source, so the next run adds it right back. Rather
  // than offer a delete that silently undoes itself, the action is hidden for connector libraries.
  const canDelete = isUploadLibrary && canManage
  // Folder management is UPLOAD-only, same EDITOR-or-above threshold as document upload.
  const canManageFolders = isUploadLibrary && canManage
  // Whoever may edit the library's documents may correct their metadata - for every
  // sourceType, since a metadata value hangs at the document row, not at the file.
  const canEditMetadata = canManage

  useEffect(() => {
    // uploadErrors/deleteError/error are not keyed by library - without
    // this reset, a failure left over from a previously viewed library would keep showing.
    reset()
    return () => {
      stopPolling(libraryId)
    }
  }, [libraryId, stopPolling, reset])

  useEffect(() => {
    // Reacts to the URL's folder param too - a breadcrumb click or a folder row navigates by
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
    // An invalid/foreign folderId is caught by loadDocuments, which falls back to the root -
    // this just corrects the URL to match, dropping the dead ?folder= param.
    if (folderNotFoundMessage && folderIdParam) {
      const next = new URLSearchParams(searchParams)
      next.delete('folder')
      setSearchParams(next, { replace: true })
    }
  }, [folderNotFoundMessage, folderIdParam, searchParams, setSearchParams])

  const upload = useDocumentUpload(libraryId, canManage, onDocumentsChanged)
  useImperativeHandle(uploadPickerRef, () => ({
    open: () => upload.fileInputRef.current?.click(),
    openFolder: () => upload.folderInputRef.current?.click(),
  }))

  // Leaving the shown list (into a folder or the anchor's worklist) drops its search and selection.
  function leaveList() {
    clearSearch()
    setSelectedDocumentIds([])
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

  // A corrected document leaves the anchor - and, while its worklist is open, the list too.
  function handleMetadataValueChanged() {
    setAnchorRefreshToken((previous) => previous + 1)
    if (missingFieldParam) {
      void loadDocuments(libraryId, {})
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
      // The error message is already shown via documentStore.deleteError.
    }
  }

  // Every sourceType opens through GET .../content (fetched as a Blob, since the
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

  // Nothing in the library at all - not a search or filter without hits: only the one sentence and,
  // for an upload library, the drop zone (D21).
  const libraryEmpty =
    !isLoading &&
    pageState?.totalElements === 0 &&
    folders.length === 0 &&
    !pageState?.q &&
    !missingFieldParam &&
    !folderIdParam
  const uploadArea = isUploadLibrary && canManage && (
    <LibraryDocumentUploadArea upload={upload} empty={libraryEmpty} />
  )

  if (libraryEmpty) {
    return (
      <Box sx={{ mb: 5 }}>
        <LibraryDocumentsNotices
          libraryId={libraryId}
          newFolderDialogOpen={newFolderDialogOpen}
          renameFolderTarget={renameFolderTarget}
        />
        {uploadArea || (
          <EmptyLine
            text="Noch keine Dokumente."
            action={
              onIndexNow ? (
                <Button size="small" onClick={onIndexNow}>
                  Jetzt indizieren
                </Button>
              ) : undefined
            }
          />
        )}
        {canManageFolders && <LibraryFolderDialogs folderActions={folderActions} />}
      </Box>
    )
  }

  return (
    <Box
      sx={{ mb: 5 }}
      // an upload library takes files dropped anywhere on its list
      {...(isUploadLibrary && canManage
        ? {
            onDragOver: (event: DragEvent) => {
              event.preventDefault()
              upload.setIsDragActive(true)
            },
            onDragLeave: () => upload.setIsDragActive(false),
            onDrop: upload.handleDrop,
          }
        : {})}
    >
      {uploadArea}

      <LibraryDocumentsNotices
        libraryId={libraryId}
        newFolderDialogOpen={newFolderDialogOpen}
        renameFolderTarget={renameFolderTarget}
      />

      {bulkResult && (
        // role="status" (polite) rather than the Alert's own assertive default: the outcome of a
        // finished action is announced without interrupting (accessibility.md 2.8).
        <Alert
          severity={bulkResult.severity}
          role="status"
          sx={{ mb: 2 }}
          onClose={() => setBulkResult(null)}
        >
          {bulkResult.text}
        </Alert>
      )}

      <LibraryDocumentList
        documents={documents}
        folders={folders}
        pageState={pageState}
        isLoading={isLoading}
        confluenceSpaces={confluenceSpaces}
        emptyMessage={
          missingFieldParam ? 'Jedes Dokument hat hier einen Wert.' : 'Dieser Ordner ist leer.'
        }
        filterControl={
          metadataEnabled ? (
            <Select
              size="small"
              value={missingFieldParam ?? ''}
              displayEmpty
              onChange={(event) => setMissingFieldFilter(event.target.value || null)}
              inputProps={{ 'aria-label': 'Nur Dokumente ohne Wert' }}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">Alle Dokumente</MenuItem>
              {CORE_METADATA_FIELDS.map((field) => (
                <MenuItem key={field.key} value={field.key}>
                  {`Ohne ${field.label}`}
                </MenuItem>
              ))}
            </Select>
          ) : undefined
        }
        toolbarActions={
          isUploadLibrary && canManage ? (
            <UploadToolbarActions
              upload={upload}
              canManageFolders={canManageFolders}
              onNewFolder={() => setNewFolderDialogOpen(true)}
            />
          ) : undefined
        }
        breadcrumb={
          // The Ordnerpfad stands directly above the search field, where the list it
          // describes begins - not above the Pflege-Anker, which is bibliotheksweit.
          // breadcrumb.length is 0 at the root and while a search is active (ADR-0020).
          breadcrumb.length > 0 ? (
            <LibraryFolderBreadcrumb breadcrumb={breadcrumb} onOpenFolder={navigateToFolder} />
          ) : undefined
        }
        searchInput={searchInput}
        onSearchChange={handleSearchChange}
        onPageChange={(page) => {
          setSelectedDocumentIds([])
          void loadDocuments(libraryId, { page: page - 1 })
        }}
        onPageSizeChange={(size) => {
          // Like every other change of the shown list: a smaller page size would otherwise leave
          // documents selected that nobody can see any more - and "Löschen" is irreversible.
          setSelectedDocumentIds([])
          void loadDocuments(libraryId, { page: 0, size })
        }}
        selectable={metadataEnabled ? canEditMetadata : canDelete}
        selectedIds={selectedDocumentIds}
        onToggleSelected={toggleSelected}
        onToggleAllVisible={toggleAllVisible}
        onClearSelection={() => setSelectedDocumentIds([])}
        bulkActions={
          // Every single-row action that is permitted for all selected documents - "Feld
          // setzen" everywhere, "Löschen" wherever a single row offers it too.
          <LibraryDocumentBulkActions
            bulk={bulk}
            selectedDocumentIds={selectedDocumentIds}
            metadataEnabled={metadataEnabled}
            canDelete={canDelete}
          />
        }
        onOpenFolder={navigateToFolder}
        renderFolderActions={
          canManageFolders
            ? (folder) => (
                <IconButton
                  aria-label={`Aktionen für Ordner ${folder.name}`}
                  aria-haspopup="menu"
                  size="small"
                  onClick={(e) => setFolderMenu({ anchorEl: e.currentTarget, folder })}
                >
                  <MoreHorizIcon fontSize="small" />
                </IconButton>
              )
            : undefined
        }
        renderRowActions={
          canDelete
            ? (document) => (
                <DocumentRowMenu
                  fileName={document.fileName}
                  onDelete={() => void handleDelete(document)}
                />
              )
            : undefined
        }
        metadataOpenById={metadataOpenById}
        onToggleMetadata={toggleMetadataPanel}
        renderMetadataPanel={
          metadataEnabled
            ? (document) => (
                <DocumentMetadataPanel
                  libraryId={libraryId}
                  documentId={document.id}
                  fileName={document.fileName}
                  canEdit={canEditMetadata}
                  refreshToken={metadataRefreshToken}
                  onValueChanged={handleMetadataValueChanged}
                />
              )
            : undefined
        }
        onOpenOriginal={(document) => void handleOpenOriginal(document)}
        isGroupExpanded={isGroupExpanded}
        onToggleAttachmentGroup={toggleAttachmentGroup}
      />

      <DocumentTextPreviewDialog previewDocument={previewDocument} onClose={closePreview} />
      {metadataEnabled && bulkDialogOpen && (
        <BulkMetadataDialog
          open
          onClose={() => setBulkDialogOpen(false)}
          libraryId={libraryId}
          documentIds={selectedDocumentIds}
          onDone={handleBulkDone}
        />
      )}

      {canManageFolders && <LibraryFolderDialogs folderActions={folderActions} />}
    </Box>
  )
}

/** A document row's „⋯“ - the one pattern of row actions; red only in the question that follows. */
function DocumentRowMenu({ fileName, onDelete }: { fileName: string; onDelete: () => void }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  return (
    <>
      <IconButton
        aria-label={`Aktionen für ${fileName}`}
        aria-haspopup="menu"
        size="small"
        onClick={(event) => setAnchor(event.currentTarget)}
      >
        <MoreHorizIcon fontSize="small" />
      </IconButton>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        <MenuItem
          onClick={() => {
            setAnchor(null)
            onDelete()
          }}
        >
          Löschen
        </MenuItem>
      </Menu>
    </>
  )
}
