import { Fragment, useState } from 'react'
import type { DragEvent, ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import StateDot from '@/shared/ui/StateDot'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Link from '@mui/material/Link'
import MenuItem from '@mui/material/MenuItem'
import Pagination from '@mui/material/Pagination'
import Select from '@mui/material/Select'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AttachFileIcon from '@mui/icons-material/AttachFile'
import CloseIcon from '@mui/icons-material/Close'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import FolderIcon from '@mui/icons-material/Folder'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import OpenInNewIcon from '@mui/icons-material/OpenInNew'
import SearchIcon from '@mui/icons-material/Search'
import type { LibraryDocumentResponse, LibraryFolderListItem } from '@/shared/api/types'
import type { ConfluenceSpaceRef } from '../utils/confluenceSource'
import type { DocumentPageState } from '../stores/documentStore'
import { DEFAULT_PAGE_SIZE } from '../stores/documentStore'
import { documentStatusLabel } from './indexingLabels'
import { formatFileSize } from '@/shared/utils/labels'
import { safeExternalHref } from '@/shared/utils/safeExternalHref'
import { sourceRegistration } from './sources/registry'

function formatIndexedAt(indexedAt: string | null | undefined): string {
  if (!indexedAt) return '—'
  return new Date(indexedAt).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/** Folders a level shows before „alle n Ordner anzeigen“. */
const FOLDERS_SHOWN = 8

function statusTone(status: LibraryDocumentResponse['status']): 'success' | 'neutral' | 'error' {
  if (status === 'INDEXED') return 'success'
  if (status === 'FAILED') return 'error'
  return 'neutral'
}

export interface LibraryDocumentListProps {
  documents: LibraryDocumentResponse[]
  /** Only ever non-empty on the first page - the backend does not page folders. */
  folders: LibraryFolderListItem[]
  pageState: DocumentPageState | undefined
  isLoading: boolean
  /** The library's selected spaces (CONFLUENCE) - resolves a row's space key to its name. */
  confluenceSpaces?: ConfluenceSpaceRef[] | null
  /** What to say when neither a document nor a folder is left to show. */
  emptyMessage: string
  /** The one action of the empty state, e.g. „Jetzt indizieren“. */
  emptyAction?: ReactNode
  /** Filters beside the search, e.g. „ohne Wert“. */
  toolbarFilter?: ReactNode
  /** Actions at the toolbar's right edge, e.g. „Hochladen“. */
  toolbarActions?: ReactNode
  /** Files dropped on the list are uploaded (upload library). */
  onDrop?: (event: DragEvent<HTMLDivElement>) => void
  /** Rendered directly above the search field - the Ordnerpfad of the open folder. */
  breadcrumb?: ReactNode
  searchInput: string
  onSearchChange: (value: string) => void
  /** 1-based, as the pagination control itself counts. */
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
  /** Whether rows carry a checkbox at all - a reader's list has no selection. */
  selectable: boolean
  selectedIds: string[]
  onToggleSelected: (documentId: string) => void
  onToggleAllVisible: () => void
  onClearSelection: () => void
  /**
   * The actions offered for the current selection - every single-row action that is permitted for
   * all selected documents. Rendered in the selection toolbar.
   */
  bulkActions?: ReactNode
  onOpenFolder: (folderId: string | null) => void
  /** Upload only: the per-folder menu button. */
  renderFolderActions?: (folder: LibraryFolderListItem) => ReactNode
  /** Upload only: the per-row delete button. */
  renderRowActions?: (document: LibraryDocumentResponse) => ReactNode
  /**
   * Which rows show their metadata panel and what that panel is; without
   * `renderMetadataPanel` - metadata switched off (ADR-0044) - no row offers one.
   */
  metadataOpenById?: Record<string, boolean>
  onToggleMetadata?: (documentId: string) => void
  renderMetadataPanel?: (document: LibraryDocumentResponse) => ReactNode
  onOpenOriginal: (document: LibraryDocumentResponse) => void
  /** Per-parent expansion of the attachment group. */
  isGroupExpanded: (parentId: string) => boolean
  onToggleAttachmentGroup: (parentId: string) => void
}

/** A source address from the document's metadata: a link for http(s), plain text otherwise. */
function ExternalAddress({ address }: { address: string }) {
  const href = safeExternalHref(address)
  if (!href) return <span>{address}</span>
  return (
    <Link href={href} target="_blank" rel="noopener noreferrer">
      {address}
    </Link>
  )
}

/**
 * The one document list of a Wissensbibliothek - search, page size, selection, folder rows,
 * document rows with their attachment groups, and paging. Every source type uses it; what differs
 * per type comes in as props: the extra caption lines follow from the row's own fields
 * (Space/Bucket, Herkunft, Ordner), the actions are passed in ({@link
 * LibraryDocumentListProps#renderRowActions}, {@link LibraryDocumentListProps#bulkActions}).
 *
 * <p>Presentation only: every piece of state it shows is owned by the caller, so the selection a
 * Sammelaktion acts on and the rows on screen can never drift apart.
 */
export default function LibraryDocumentList({
  documents,
  folders,
  pageState,
  isLoading,
  confluenceSpaces,
  emptyMessage,
  emptyAction,
  toolbarFilter,
  toolbarActions,
  onDrop,
  breadcrumb,
  searchInput,
  onSearchChange,
  onPageChange,
  onPageSizeChange,
  selectable,
  selectedIds,
  onToggleSelected,
  onToggleAllVisible,
  onClearSelection,
  bulkActions,
  onOpenFolder,
  renderFolderActions,
  renderRowActions,
  metadataOpenById,
  onToggleMetadata,
  renderMetadataPanel,
  onOpenOriginal,
  isGroupExpanded,
  onToggleAttachmentGroup,
}: LibraryDocumentListProps) {
  const isSearchActive = Boolean(pageState?.q)
  // an empty library shows its empty state alone: no search, no paging (D21)
  const isEmptyRoot =
    !isLoading &&
    documents.length === 0 &&
    folders.length === 0 &&
    !searchInput &&
    !pageState?.missingMetadataField &&
    !breadcrumb
  const isMissingFilterActive = Boolean(pageState?.missingMetadataField)
  const pageCount = pageState ? Math.max(1, Math.ceil(pageState.totalElements / pageState.size)) : 1

  const [dragActive, setDragActive] = useState(false)
  // many folders on one level are folded behind one link, the documents stay in reach (R1-37)
  const [allFolders, setAllFolders] = useState(false)
  const shownFolders = allFolders ? folders : folders.slice(0, FOLDERS_SHOWN)
  const documentsById = new Map(documents.map((doc) => [doc.id, doc]))
  // The backend guarantees each attachment row follows its (transitive) top-level parent
  // within the same page, so grouping is a single sequential pass. A row whose parent is missing
  // from the page is rendered as its own top-level row rather than dropped.
  const documentGroups: {
    parent: LibraryDocumentResponse
    attachments: LibraryDocumentResponse[]
  }[] = []
  for (const doc of documents) {
    const lastGroup = documentGroups[documentGroups.length - 1]
    if (!isMissingFilterActive && doc.parentDocumentId && lastGroup) {
      lastGroup.attachments.push(doc)
    } else {
      documentGroups.push({ parent: doc, attachments: [] })
    }
  }

  const confluenceSpaceNameByKey = new Map(
    (confluenceSpaces ?? []).map((space) => [space.key, space.name]),
  )

  /** A container key with the name the library's own selection gives it ("Name (KEY)"). */
  function containerName(key: string): string {
    const name = confluenceSpaceNameByKey.get(key)
    return name ? `${name} (${key})` : key
  }

  /** Where a document sits at its source - shown behind ⓘ, so the row keeps two lines (P6). */
  function originOf(document: LibraryDocumentResponse): ReactNode {
    if (document.sourceContainerKey) {
      return `${sourceRegistration(document.sourceType)?.containerLabel ?? 'Bereich'}: ${containerName(document.sourceContainerKey)}${document.sourceHierarchyPath ? ` · ${document.sourceHierarchyPath}` : ''}`
    }
    if (document.sourceEntryUrl) {
      return (
        <>
          Herkunft: <ExternalAddress address={document.sourceEntryUrl} />
        </>
      )
    }
    if (document.sourceUrl) {
      return (
        <>
          Quelle: <ExternalAddress address={document.sourceUrl} />
        </>
      )
    }
    if (document.sourceHierarchyPath) return `Pfad: ${document.sourceHierarchyPath}`
    return null
  }

  const visibleDocumentIds = documents.map((doc) => doc.id)
  const allVisibleSelected =
    visibleDocumentIds.length > 0 && visibleDocumentIds.every((id) => selectedIds.includes(id))

  function renderDocumentRow(
    document: LibraryDocumentResponse,
    options: { attachments?: LibraryDocumentResponse[]; viaFileName?: string | null } = {},
  ) {
    const isAttachment = Boolean(document.parentDocumentId)
    const attachments = options.attachments ?? []
    const expanded = isGroupExpanded(document.id)
    const origin = originOf(document)
    const hasInfo = Boolean(renderMetadataPanel || origin)
    const metadataOpen = Boolean(hasInfo && metadataOpenById?.[document.id])
    return (
      <Fragment key={document.id}>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: { xs: 1, sm: 2 },
            p: { xs: 1, sm: 1.5 },
            ...(isAttachment && { py: 1.25 }),
          }}
        >
          {selectable && (
            <Checkbox
              size="small"
              checked={selectedIds.includes(document.id)}
              onChange={() => onToggleSelected(document.id)}
              slotProps={{ input: { 'aria-label': `Dokument ${document.fileName} auswählen` } }}
              sx={{ p: 0.5 }}
            />
          )}
          <Stack spacing={0.25} sx={{ minWidth: 0, flexGrow: 1 }}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
              {isAttachment && <AttachFileIcon sx={{ fontSize: 16 }} color="action" />}
              <Typography
                noWrap
                title={document.fileName}
                sx={{
                  fontWeight: isAttachment ? 500 : 600,
                  fontSize: isAttachment ? 13.5 : undefined,
                  minWidth: 0,
                }}
              >
                {document.fileName}
              </Typography>
              {isAttachment && <Chip label="Anhang" size="small" variant="outlined" />}
            </Stack>
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              {formatFileSize(document.fileSize)} · {document.chunkCount}{' '}
              {document.chunkCount === 1 ? 'Abschnitt' : 'Abschnitte'} ·{' '}
              {formatIndexedAt(document.indexedAt)}
              {/* below sm the state joins the second line instead of a column of its own */}
              <Box component="span" sx={{ display: { xs: 'inline', sm: 'none' } }}>
                {` · ${documentStatusLabel(document.status)}`}
              </Box>
            </Typography>
            {options.viaFileName && (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Anhang von: {options.viaFileName}
              </Typography>
            )}
            {/* a search hit names its folder - the result list has no breadcrumb to place it */}
            {isSearchActive && document.folderPath && (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Ordner:{' '}
                <Link
                  component="button"
                  underline="hover"
                  onClick={() => onOpenFolder(document.folderId ?? null)}
                >
                  {document.folderPath}
                </Link>
              </Typography>
            )}
            {attachments.length > 0 && (
              <Box>
                <Button
                  size="small"
                  onClick={() => onToggleAttachmentGroup(document.id)}
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
            {/* A FAILED document's asynchronous processing failure is only visible to the
                user via this German errorMessage - the status chip alone only says something went
                wrong, not what. */}
            <Tooltip
              title={document.status === 'FAILED' ? (document.errorMessage ?? '') : ''}
              disableHoverListener={document.status !== 'FAILED' || !document.errorMessage}
            >
              <Box
                component="span"
                sx={{ display: { xs: 'none', sm: 'inline-flex' }, minWidth: 112 }}
              >
                <StateDot
                  tone={statusTone(document.status)}
                  label={documentStatusLabel(document.status)}
                />
              </Box>
            </Tooltip>
            {hasInfo && (
              <Tooltip title={metadataOpen ? 'Angaben verbergen' : 'Angaben anzeigen'}>
                <IconButton
                  aria-label={`Angaben zu ${document.fileName} ${metadataOpen ? 'verbergen' : 'anzeigen'}`}
                  aria-expanded={metadataOpen}
                  size="small"
                  color={metadataOpen ? 'primary' : 'default'}
                  onClick={() => onToggleMetadata?.(document.id)}
                >
                  <InfoOutlinedIcon fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
            <Tooltip title="Original öffnen">
              <IconButton
                aria-label={`Original von ${document.fileName} öffnen`}
                size="small"
                onClick={() => onOpenOriginal(document)}
              >
                <OpenInNewIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            {renderRowActions?.(document)}
          </Stack>
        </Box>
        {metadataOpen && origin && (
          <Typography
            variant="caption"
            component="div"
            sx={{
              color: 'text.secondary',
              px: 1.5,
              pb: 1,
              pl: selectable ? 6.5 : 1.5,
              overflowWrap: 'anywhere',
            }}
          >
            {origin}
          </Typography>
        )}
        {metadataOpen && renderMetadataPanel?.(document)}
      </Fragment>
    )
  }

  return (
    <Box>
      {breadcrumb}

      {!isEmptyRoot && (
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1.5}
          sx={{ mb: 1.5, alignItems: { sm: 'center' } }}
        >
          {selectable && (
            <Checkbox
              size="small"
              checked={allVisibleSelected}
              disabled={documents.length === 0}
              indeterminate={!allVisibleSelected && selectedIds.length > 0}
              onChange={onToggleAllVisible}
              slotProps={{ input: { 'aria-label': 'Alle auf dieser Seite auswählen' } }}
              sx={{
                p: 0.5,
                alignSelf: { xs: 'flex-start', sm: 'center' },
                display: { xs: 'none', sm: 'inline-flex' },
              }}
            />
          )}
          <TextField
            size="small"
            fullWidth
            value={searchInput}
            onChange={(e) => onSearchChange(e.target.value)}
            placeholder="Dateiname"
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon sx={{ fontSize: 16 }} />
                  </InputAdornment>
                ),
                endAdornment: searchInput ? (
                  <InputAdornment position="end">
                    <IconButton
                      size="small"
                      aria-label="Suche zurücksetzen"
                      onClick={() => onSearchChange('')}
                    >
                      <CloseIcon sx={{ fontSize: 16 }} />
                    </IconButton>
                  </InputAdornment>
                ) : undefined,
              },
              htmlInput: { 'aria-label': 'Dokumente durchsuchen' },
            }}
          />
          {toolbarFilter}
          {toolbarActions}
        </Stack>
      )}

      {/* the selection's actions appear with a selection, not as a disabled bar before it */}
      {selectable && selectedIds.length > 0 && (
        <Stack
          direction="row"
          spacing={1.5}
          sx={{ alignItems: 'center', mb: 1.5, flexWrap: 'wrap' }}
          role="toolbar"
          aria-label="Sammelaktionen"
        >
          <Typography
            variant="body2"
            role="status"
            aria-live="polite"
            sx={{ color: 'text.secondary' }}
          >
            {selectedIds.length} ausgewählt
          </Typography>
          {bulkActions}
          <Button size="small" onClick={onClearSelection}>
            Auswahl aufheben
          </Button>
        </Stack>
      )}

      {isLoading ? (
        <Stack spacing={1} aria-label="Dokumente werden geladen">
          <Skeleton variant="rounded" height={64} />
          <Skeleton variant="rounded" height={64} />
          <Skeleton variant="rounded" height={64} />
        </Stack>
      ) : documents.length === 0 && folders.length === 0 ? (
        <Box>
          <Typography sx={{ color: 'text.secondary', fontSize: 13.5 }}>
            {searchInput || pageState?.missingMetadataField
              ? 'Kein Dokument entspricht dieser Auswahl.'
              : emptyMessage}
          </Typography>
          {isEmptyRoot && emptyAction && <Box sx={{ mt: 1.5 }}>{emptyAction}</Box>}
        </Box>
      ) : (
        <Stack
          spacing={1}
          onDragOver={
            onDrop
              ? (event) => {
                  event.preventDefault()
                  setDragActive(true)
                }
              : undefined
          }
          onDragLeave={onDrop ? () => setDragActive(false) : undefined}
          onDrop={
            onDrop
              ? (event) => {
                  setDragActive(false)
                  onDrop(event)
                }
              : undefined
          }
          sx={{
            outline: dragActive ? '2px dashed' : 'none',
            outlineColor: 'primary.main',
            outlineOffset: 4,
            borderRadius: 1,
          }}
        >
          {/* Folders are rows ahead of the documents, mirroring a plain file browser - a
              folder's documentCount already counts its own subtree recursively. */}
          {shownFolders.map((folder) => (
            <Box
              key={folder.id}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 2,
                py: 1.25,
                borderBottom: 1,
                borderColor: 'divider',
                '&:last-of-type': { borderBottom: 0 },
              }}
            >
              {/* a folder cannot be selected; the spacer keeps the names on one edge */}
              {selectable && <Box aria-hidden sx={{ width: 28, flex: 'none' }} />}
              <Box
                role="button"
                tabIndex={0}
                aria-label={`Ordner ${folder.name} öffnen`}
                onClick={() => onOpenFolder(folder.id)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onOpenFolder(folder.id)
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
                <Typography noWrap title={folder.name} sx={{ fontWeight: 600, minWidth: 0 }}>
                  {folder.name}
                </Typography>
                <Typography variant="caption" noWrap sx={{ color: 'text.secondary', flex: 'none' }}>
                  {folder.documentCount} {folder.documentCount === 1 ? 'Dokument' : 'Dokumente'}
                </Typography>
              </Box>
              {renderFolderActions?.(folder)}
            </Box>
          ))}
          {folders.length > FOLDERS_SHOWN && (
            <Box sx={{ py: 1, borderBottom: 1, borderColor: 'divider' }}>
              <Link
                component="button"
                type="button"
                underline="hover"
                onClick={() => setAllFolders((value) => !value)}
                sx={{ fontSize: 13.5 }}
              >
                {allFolders ? 'Weniger Ordner anzeigen' : `Alle ${folders.length} Ordner anzeigen`}
              </Link>
            </Box>
          )}
          {/* A top-level document with its attachments is one group - the attachments hang
              indented on a guide line below it, a divider closes the group against the next. */}
          {documentGroups.map(({ parent, attachments }) => (
            <Box
              key={parent.id}
              sx={{
                borderBottom: 1,
                borderColor: 'divider',
                '&:last-of-type': { borderBottom: 0 },
              }}
            >
              {renderDocumentRow(parent, { attachments })}
              {isGroupExpanded(parent.id) && attachments.length > 0 && (
                <Box
                  sx={{
                    ml: 3.5,
                    mr: 1.5,
                    mb: 1.5,
                    borderLeft: '2px solid',
                    borderLeftColor: 'divider',
                  }}
                >
                  {attachments.map((attachment, index) => (
                    <Box
                      key={attachment.id}
                      sx={{
                        borderTop: index === 0 ? 'none' : '1px solid',
                        borderTopColor: 'divider',
                      }}
                    >
                      {renderDocumentRow(attachment, {
                        // Mail-in-Mail: an attachment whose direct parent is itself an attachment
                        // names that parent, since visually it sits flat under the top-level row.
                        viaFileName:
                          attachment.parentDocumentId && attachment.parentDocumentId !== parent.id
                            ? (documentsById.get(attachment.parentDocumentId)?.fileName ?? null)
                            : null,
                      })}
                    </Box>
                  ))}
                </Box>
              )}
            </Box>
          ))}
        </Stack>
      )}

      {!isLoading && pageState && (documents.length > 0 || pageCount > 1) && (
        <Stack
          direction="row"
          sx={{
            mt: 2,
            alignItems: 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: 1,
          }}
        >
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {isSearchActive
              ? `${pageState.totalElements.toLocaleString('de-DE')} Treffer für „${pageState.q}“`
              : `${pageState.totalElements.toLocaleString('de-DE')} ${
                  pageState.totalElements === 1 ? 'Dokument' : 'Dokumente'
                }`}
            {pageCount > 1 ? ` · Seite ${(pageState.page ?? 0) + 1} von ${pageCount}` : ''}
          </Typography>
          {pageState.totalElements > DEFAULT_PAGE_SIZE && (
            <Select
              size="small"
              value={pageState.size ?? DEFAULT_PAGE_SIZE}
              onChange={(e) => onPageSizeChange(Number(e.target.value))}
              inputProps={{ 'aria-label': 'Dokumente je Seite' }}
              sx={{ minWidth: 120, ml: { sm: 'auto' } }}
            >
              {[20, 50, 100].map((size) => (
                <MenuItem key={size} value={size}>
                  {size} je Seite
                </MenuItem>
              ))}
            </Select>
          )}
          {pageCount > 1 && (
            <Pagination
              count={pageCount}
              page={(pageState?.page ?? 0) + 1}
              onChange={(_event, page) => onPageChange(page)}
              // The app shell's own persistent navigation is also a <nav>; without a
              // distinguishing name, a test scoping to "the" navigation region would match both.
              aria-label="Dokumentenliste blättern"
            />
          )}
        </Stack>
      )}
    </Box>
  )
}
