import { Fragment, useState } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Link from '@mui/material/Link'
import Pagination from '@mui/material/Pagination'
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
import { formatFileSize } from '@/shared/utils/labels'
import StateDot from '@/shared/ui/StateDot'
import { DOCUMENT_STATUS_WORDS } from './indexingLabels'
import { TextAction } from '@/shared/ui/DetailSection'
import { sourceRegistration } from './sources/registry'

function formatIndexedAt(indexedAt: string | null | undefined): string {
  if (!indexedAt) return '—'
  return new Date(indexedAt).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/** Folders a level shows before „alle n Ordner anzeigen“. */
const FOLDERS_SHOWN = 8

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
  /** Rendered directly above the search field - the Ordnerpfad of the open folder. */
  breadcrumb?: ReactNode
  searchInput: string
  onSearchChange: (value: string) => void
  /** 1-based, as the pagination control itself counts. */
  onPageChange: (page: number) => void
  /** Kept for callers that still offer a page size; the toolbar shows filters instead. */
  onPageSizeChange?: (size: number) => void
  /** The toolbar's filters after the search field. */
  filters?: ReactNode
  /** Search and filters only from eight documents on or while filtering (D19, R1-63). */
  showSearch?: boolean
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

/**
 * A document's state as dot and word, like a run's: „indiziert“ quiet without a dot, a failure
 * with its reason one focus away (guidelines 5.5).
 */

function DocumentStatusMark({ document }: { document: LibraryDocumentResponse }) {
  const label = DOCUMENT_STATUS_WORDS[document.status]
  if (document.status === 'INDEXED') {
    return (
      <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary', minWidth: 112 }}>
        {label}
      </Typography>
    )
  }
  const mark = (
    <Box component="span" tabIndex={document.errorMessage ? 0 : undefined} sx={{ minWidth: 112 }}>
      <StateDot tone={document.status === 'FAILED' ? 'error' : 'neutral'} label={label} />
    </Box>
  )
  return document.status === 'FAILED' && document.errorMessage ? (
    <Tooltip title={document.errorMessage}>{mark}</Tooltip>
  ) : (
    mark
  )
}

/** Where a connector document comes from: its space and page, or its address. */
function originOf(
  document: LibraryDocumentResponse,
  names: Map<string, string | null | undefined>,
): string | null {
  if (document.sourceContainerKey) {
    const noun = sourceRegistration(document.sourceType)?.containerLabel ?? 'Bereich'
    return `${noun}: ${names.get(document.sourceContainerKey) ?? document.sourceContainerKey}${document.sourceHierarchyPath ? ` · ${document.sourceHierarchyPath}` : ''}`
  }
  return document.sourceEntryUrl ?? document.sourceUrl ?? null
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
  breadcrumb,
  searchInput,
  onSearchChange,
  onPageChange,
  filters,
  showSearch = true,
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
  // folders are not paged by the backend: from eight on the list shows the first ones (D19)
  const [allFolders, setAllFolders] = useState(false)
  const shownFolders = allFolders ? folders : folders.slice(0, FOLDERS_SHOWN)
  const isMissingFilterActive = Boolean(pageState?.missingMetadataField)
  const pageCount = pageState ? Math.max(1, Math.ceil(pageState.totalElements / pageState.size)) : 1

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
    const metadataOpen = Boolean(renderMetadataPanel && metadataOpenById?.[document.id])
    return (
      <Fragment key={document.id}>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: { xs: 1, sm: 2 },
            px: { xs: 0.5, sm: 1.5 },
            py: isAttachment ? 1 : 1.25,
          }}
        >
          {!selectable && <Box aria-hidden sx={{ width: 28, flex: 'none' }} />}
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
                title={[document.fileName, originOf(document, confluenceSpaceNameByKey)]
                  .filter(Boolean)
                  .join('\n')}
                sx={{ fontWeight: isAttachment ? 500 : 600, fontSize: isAttachment ? 13.5 : 14 }}
              >
                {document.fileName}
              </Typography>
              {isAttachment && <Chip label="Anhang" size="small" variant="outlined" />}
            </Stack>
            {/* one line under the name: where it lies (search hits), size, sections, date - its
                origin in the source stands in the name's tooltip (P6) */}
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              {options.viaFileName && `Anhang von ${options.viaFileName} · `}
              {document.folderPath && (
                <>
                  <Link
                    component="button"
                    underline="hover"
                    onClick={() => onOpenFolder(document.folderId ?? null)}
                  >
                    {document.folderPath}
                  </Link>
                  {' · '}
                </>
              )}
              {formatFileSize(document.fileSize)}
              {/* sections and date only where the document was indexed - no placeholders */}
              {document.status === 'INDEXED' && (
                <Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>
                  {` · ${document.chunkCount} ${document.chunkCount === 1 ? 'Abschnitt' : 'Abschnitte'} · ${formatIndexedAt(document.indexedAt)}`}
                </Box>
              )}
              {/* below 600 px the state joins this line, with its dot, instead of its own column */}
              <Box
                component="span"
                sx={{ display: { xs: 'inline-flex', sm: 'none' }, ml: 1, verticalAlign: 'top' }}
              >
                {document.status === 'INDEXED' ? (
                  DOCUMENT_STATUS_WORDS.INDEXED
                ) : (
                  <StateDot
                    tone={document.status === 'FAILED' ? 'error' : 'neutral'}
                    label={DOCUMENT_STATUS_WORDS[document.status]}
                  />
                )}
              </Box>
            </Typography>
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
            <Box sx={{ display: { xs: 'none', sm: 'block' } }}>
              <DocumentStatusMark document={document} />
            </Box>
            {renderMetadataPanel && (
              <Tooltip title={metadataOpen ? 'Metadaten verbergen' : 'Metadaten anzeigen'}>
                <IconButton
                  sx={{ display: { xs: 'none', sm: 'inline-flex' } }}
                  aria-label={`Metadaten von ${document.fileName} ${metadataOpen ? 'verbergen' : 'anzeigen'}`}
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
        {metadataOpen && renderMetadataPanel?.(document)}
      </Fragment>
    )
  }

  return (
    <Box>
      {breadcrumb}

      {(showSearch || filters) && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}>
          {showSearch && (
            <TextField
              size="small"
              fullWidth
              value={searchInput}
              onChange={(e) => onSearchChange(e.target.value)}
              placeholder="Dateiname enthält …"
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
          )}
          {filters && (
            <Stack
              direction="row"
              useFlexGap
              spacing={1.5}
              sx={{ alignItems: 'center', flexShrink: 0, minWidth: 0, flexWrap: 'wrap' }}
            >
              {filters}
            </Stack>
          )}
        </Stack>
      )}

      {/* The list's head for every role: the select-all box (or its space) and the column
          name; while something is selected, its actions */}
      {(documents.length > 0 || folders.length > 0) && (
        <Stack
          direction="row"
          spacing={{ xs: 1, sm: 2 }}
          sx={{
            alignItems: 'center',
            minHeight: 40,
            px: { xs: 0.5, sm: 1.5 },
            borderBottom: 1,
            borderColor: 'divider',
            flexWrap: 'wrap',
          }}
          role={selectable ? 'toolbar' : undefined}
          aria-label={selectable ? 'Sammelaktionen' : undefined}
        >
          {selectable ? (
            <Checkbox
              size="small"
              checked={allVisibleSelected}
              disabled={documents.length === 0}
              indeterminate={!allVisibleSelected && selectedIds.length > 0}
              onChange={onToggleAllVisible}
              slotProps={{ input: { 'aria-label': 'Alle auf dieser Seite auswählen' } }}
              sx={{ p: 0.5 }}
            />
          ) : (
            <Box aria-hidden sx={{ width: 28, flex: 'none' }} />
          )}
          {/* accessibility.md 2.8: the count changes without moving the focus */}
          <Typography
            role="status"
            aria-live="polite"
            sx={{ fontSize: 12.5, color: 'text.secondary' }}
          >
            {selectedIds.length > 0 ? `${selectedIds.length} ausgewählt` : 'Name'}
          </Typography>
          {selectedIds.length > 0 && (
            <>
              {bulkActions}
              <Button size="small" onClick={onClearSelection}>
                Auswahl aufheben
              </Button>
            </>
          )}
        </Stack>
      )}

      {isLoading ? (
        <Stack spacing={1} aria-label="Dokumente werden geladen">
          <Skeleton variant="rounded" height={64} />
          <Skeleton variant="rounded" height={64} />
          <Skeleton variant="rounded" height={64} />
        </Stack>
      ) : documents.length === 0 && folders.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          {searchInput ? 'Kein Dokument entspricht dieser Suche.' : emptyMessage}
        </Typography>
      ) : (
        <Stack spacing={1}>
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
                px: { xs: 0.5, sm: 1.5 },
                borderBottom: 1,
                borderColor: 'divider',
                '&:last-of-type': { borderBottom: 0 },
              }}
            >
              {/* A folder is not a document and cannot be selected - said with a disabled
                  checkbox and its reason, rather than by leaving the column blank and making the
                  rows below look misaligned. */}
              {/* a folder is no document and has no box; the space keeps the names aligned */}
              <Box aria-hidden sx={{ width: 28, flex: 'none' }} />
              <Box
                role="button"
                tabIndex={0}
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
                <Typography noWrap title={folder.name} sx={{ fontWeight: 600, fontSize: 14 }}>
                  {folder.name}
                </Typography>
                <Typography
                  variant="caption"
                  sx={{ color: 'text.secondary', whiteSpace: 'nowrap' }}
                >
                  {folder.documentCount} {folder.documentCount === 1 ? 'Dokument' : 'Dokumente'}
                </Typography>
              </Box>
              {renderFolderActions?.(folder)}
            </Box>
          ))}
          {folders.length > shownFolders.length && (
            <Box sx={{ py: 1, pl: { xs: 4, sm: 6.5 }, borderBottom: 1, borderColor: 'divider' }}>
              <TextAction onClick={() => setAllFolders(true)}>
                alle {folders.length} Ordner anzeigen
              </TextAction>
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

      {/* the head names the documents; the foot counts only a page or a filtered list */}
      {!isLoading &&
        pageState &&
        (pageCount > 1 || isSearchActive || isMissingFilterActive || Boolean(pageState.status)) && (
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
              {/* the head counts the library; the foot counts a filtered list only */}
              {[
                isSearchActive
                  ? `${pageState.totalElements.toLocaleString('de-DE')} Treffer für „${pageState.q}“`
                  : isMissingFilterActive || pageState.status
                    ? `${pageState.totalElements.toLocaleString('de-DE')} ${pageState.totalElements === 1 ? 'Dokument' : 'Dokumente'}`
                    : null,
                pageCount > 1 ? `Seite ${(pageState.page ?? 0) + 1} von ${pageCount}` : null,
              ]
                .filter(Boolean)
                .join(' · ')}
            </Typography>
            {pageCount > 1 && (
              <Pagination
                count={pageCount}
                size="small"
                siblingCount={0}
                boundaryCount={1}
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
