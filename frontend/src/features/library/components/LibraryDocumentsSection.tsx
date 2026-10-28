import { useEffect, useRef, useState, type DragEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import RowMenu from '@/shared/ui/RowMenu'
import type { DocumentStatus, SourceTypeKey, LibraryDocumentResponse } from '@/shared/api/types'
import { TextAction } from '@/shared/ui/DetailSection'
import type { ConfluenceSpaceRef } from '../utils/confluenceSource'
import { confirmAction } from '@/shared/stores/confirmStore'
import { DEFAULT_PAGE_SIZE, useDocumentStore } from '../stores/documentStore'
import { useDocumentPreview } from '../hooks/useDocumentPreview'
import { useBulkDocumentActions } from '../hooks/useBulkDocumentActions'
import { useDocumentSearch } from '../hooks/useDocumentSearch'
import { useDocumentSelection } from '../hooks/useDocumentSelection'
import { useDocumentsUrlState } from '../hooks/useDocumentsUrlState'
import type { DocumentUpload } from '../hooks/useDocumentUpload'
import { useLibraryFolderActions } from '../hooks/useLibraryFolderActions'
import DocumentTextPreviewDialog from './DocumentTextPreviewDialog'
import BulkMetadataDialog from './metadata/BulkMetadataDialog'
import DocumentMetadataPanel from './metadata/DocumentMetadataPanel'
import { CORE_METADATA_FIELDS } from './metadata/metadataValues'
import { statusFilterOptions } from './documentStatusFilter'
import { listLibraryMetadataFields } from '../api/libraryMetadataApi'
import LibraryDocumentBulkActions from './LibraryDocumentBulkActions'
import LibraryDocumentList from './LibraryDocumentList'
import LibraryDocumentsNotices from './LibraryDocumentsNotices'
import LibraryDocumentUploadArea from './LibraryDocumentUploadArea'
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
  /** UPLOAD: the page's upload, whose pickers the head opens as well. */
  upload: DocumentUpload
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
  upload,
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
  const loadStatusCounts = useDocumentStore((s) => s.loadStatusCounts)
  const statusCounts = useDocumentStore((s) => s.statusCountsByLibrary[libraryId])

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
    statusParam,
    invalidStatusParam,
    setStatusFilter,
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
  const bulk = useBulkDocumentActions({
    libraryId,
    selectedDocumentIds,
    setSelectedDocumentIds,
    setMetadataRefreshToken,
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

  // after the reset above: the rows per state, for the toolbar's filter and the page's head -
  // counted anew when a run finished; changes and settled uploads count anew in the store
  useEffect(() => {
    void loadStatusCounts(libraryId)
  }, [libraryId, refreshToken, loadStatusCounts])

  // Any way into another list - the toolbar, a breadcrumb, a link from the page's head or another
  // tab - leaves the shown one: its search and selection go, so a Sammelaktion never acts on rows
  // nobody sees any more.
  const listKey = [folderIdParam, missingFieldParam, statusParam].join('|')
  const [shownListKey, setShownListKey] = useState(listKey)
  if (shownListKey !== listKey) {
    setShownListKey(listKey)
    leaveList()
  }

  useEffect(() => {
    // Reacts to the URL's folder param too - a breadcrumb click or a folder row navigates by
    // changing the URL, and this is what turns that into a fresh load for the requested folder.
    void loadDocuments(libraryId, {
      page: 0,
      size: DEFAULT_PAGE_SIZE,
      q: '',
      folderId: folderIdParam,
      missingMetadataField: missingFieldParam,
      status: statusParam,
    })
  }, [libraryId, folderIdParam, missingFieldParam, statusParam, loadDocuments])

  // the „ohne …“ filter offers the library's own fields after the core fields
  const [ownFields, setOwnFields] = useState<Array<[string, string]>>([])
  useEffect(() => {
    if (!metadataEnabled) return
    let cancelled = false
    listLibraryMetadataFields(libraryId)
      .then((response) => {
        if (!cancelled)
          setOwnFields(response.items.map((field) => [field.documentFieldKey, field.label]))
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [libraryId, metadataEnabled])

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

  useEffect(() => {
    // the list shows unfiltered for a status it does not know - the URL says so too
    if (invalidStatusParam) {
      const next = new URLSearchParams(searchParams)
      next.delete('status')
      setSearchParams(next, { replace: true })
    }
  }, [invalidStatusParam, searchParams, setSearchParams])

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

  // While the „ohne …“ list is open, a corrected document leaves it.
  function handleMetadataValueChanged() {
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

  // a library without a single document: only the empty state and its one action
  const filtered = Boolean(pageState?.q || missingFieldParam || statusParam || folderIdParam)
  const libraryEmpty =
    !isLoading &&
    pageState !== undefined &&
    pageState.totalElements === 0 &&
    folders.length === 0 &&
    !filtered

  // role="status" (polite) rather than the Alert's own assertive default: the outcome of a
  // finished action is announced without interrupting (accessibility.md 2.8). It stays when the
  // action emptied the library.
  const bulkOutcome = bulkResult && (
    <Alert
      severity={bulkResult.severity}
      role="status"
      sx={{ mb: 2 }}
      onClose={() => setBulkResult(null)}
    >
      {bulkResult.text}
    </Alert>
  )

  if (libraryEmpty) {
    return (
      <Box sx={{ mb: 5 }}>
        <LibraryDocumentsNotices
          libraryId={libraryId}
          newFolderDialogOpen={newFolderDialogOpen}
          renameFolderTarget={renameFolderTarget}
        />
        {bulkOutcome}
        {isUploadLibrary && canManage ? (
          <LibraryDocumentUploadArea upload={upload} />
        ) : (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            {/* the one action is the head's main action; the sentence names no second one */}
            Noch keine Dokumente.
          </Typography>
        )}
        {canManageFolders && <LibraryFolderDialogs folderActions={folderActions} />}
      </Box>
    )
  }

  // search and filters from eight documents on, or while a filter or search is active
  const searchable = (pageState?.totalElements ?? 0) >= 8 || filtered
  // the status filter answers to the counts of the whole library, not to the open folder
  const statusOptions = statusFilterOptions(statusCounts, statusParam)
  const statusFilter =
    statusOptions.length > 0 ? (
      <FilterSelect
        label="Status"
        value={statusParam ?? ''}
        onChange={(value) => setStatusFilter((value || null) as DocumentStatus | null)}
        options={[['', 'Status'], ...statusOptions]}
      />
    ) : null
  // a folder can be created at the root and inside any open folder
  const newFolder = canManageFolders ? (
    <Box sx={{ ml: 'auto' }}>
      <TextAction onClick={() => setNewFolderDialogOpen(true)}>Neuer Ordner</TextAction>
    </Box>
  ) : undefined

  const dropTarget =
    isUploadLibrary && canManage
      ? {
          onDragOver: (e: DragEvent<HTMLDivElement>) => {
            e.preventDefault()
            upload.setIsDragActive(true)
          },
          onDragLeave: () => upload.setIsDragActive(false),
          onDrop: upload.handleDrop,
        }
      : {}

  return (
    <Box
      {...dropTarget}
      sx={{
        mb: 5,
        outline: upload.isDragActive ? 2 : 0,
        outlineColor: 'primary.main',
        outlineOffset: 4,
        borderRadius: 1,
      }}
    >
      <LibraryDocumentsNotices
        libraryId={libraryId}
        newFolderDialogOpen={newFolderDialogOpen}
        renameFolderTarget={renameFolderTarget}
      />

      {bulkOutcome}

      <LibraryDocumentList
        showSearch={searchable}
        filters={
          searchable ? (
            <>
              {statusFilter}
              {metadataEnabled && (
                <FilterSelect
                  label="Metadaten"
                  value={missingFieldParam ?? ''}
                  onChange={(value) => setMissingFieldFilter(value || null)}
                  options={[
                    ['', 'Metadaten'],
                    ...[
                      ...CORE_METADATA_FIELDS.map((field): [string, string] => [
                        field.key,
                        field.label,
                      ]),
                      ...ownFields,
                    ].map(([key, label]): [string, string] => [key, `ohne ${label}`]),
                  ]}
                />
              )}
              {newFolder}
            </>
          ) : statusFilter || (documents.length === 0 && folders.length === 0) ? (
            <>
              {statusFilter}
              {documents.length === 0 && folders.length === 0 && newFolder}
            </>
          ) : undefined
        }
        // without a toolbar „Neuer Ordner“ sits in the list's head, never on a line of its own
        headAction={
          !searchable && (documents.length > 0 || folders.length > 0) ? newFolder : undefined
        }
        documents={documents}
        folders={folders}
        pageState={pageState}
        isLoading={isLoading}
        confluenceSpaces={confluenceSpaces}
        emptyMessage="Kein Dokument entspricht diesem Filter."
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
                <Tooltip title="Weitere Aktionen">
                  <IconButton
                    edge="end"
                    aria-label={`Optionen für Ordner ${folder.name}`}
                    size="small"
                    onClick={(e) => setFolderMenu({ anchorEl: e.currentTarget, folder })}
                  >
                    <MoreHorizIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              )
            : undefined
        }
        renderRowActions={
          canDelete
            ? (document) => (
                <RowMenu
                  label={`Aktionen für Dokument ${document.fileName}`}
                  items={[{ label: 'Löschen', onClick: () => void handleDelete(document) }]}
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

/** A compact filter of the toolbar: a native-looking select with its name as accessible label. */
function FilterSelect({
  label,
  value,
  onChange,
  options,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  options: Array<[string, string]>
}) {
  return (
    <Select
      size="small"
      value={value}
      displayEmpty
      onChange={(e) => onChange(String(e.target.value))}
      inputProps={{ 'aria-label': label }}
      sx={{ flex: { xs: '1 1 120px', sm: 'none' }, minWidth: { xs: 0, sm: 150 }, fontSize: 13.5 }}
    >
      {options.map(([key, text]) => (
        <MenuItem key={key} value={key}>
          {text}
        </MenuItem>
      ))}
    </Select>
  )
}
