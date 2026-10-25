import { Fragment, useState } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import { ListSearchField } from '@/shared/ui/TabSection'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import MenuItem from '@mui/material/MenuItem'
import Pagination from '@mui/material/Pagination'
import Select from '@mui/material/Select'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AttachFileIcon from '@mui/icons-material/AttachFile'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import FolderIcon from '@mui/icons-material/Folder'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import OpenInNewIcon from '@mui/icons-material/OpenInNew'
import type { LibraryDocumentResponse, LibraryFolderListItem } from '@/shared/api/types'
import type { ConfluenceSpaceRef } from '../utils/confluenceSource'
import type { DocumentPageState } from '../stores/documentStore'
import { DEFAULT_PAGE_SIZE } from '../stores/documentStore'
import { documentStatusLabel } from './indexingLabels'
import { formatFileSize } from '@/shared/utils/labels'
import { safeExternalHref } from '@/shared/utils/safeExternalHref'
import { sourceRegistration } from './sources/registry'
import StateDot from '@/shared/ui/StateDot'

/** Folders shown before „Alle n Ordner anzeigen“ once a level holds more than this many. */
const FOLDERS_SHOWN = 10

function formatIndexedAt(indexedAt: string | null | undefined): string {
  if (!indexedAt) return '—'
  return new Date(indexedAt).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/** Only a failure carries a signal colour; indexed and pending are quiet (guidelines 5.5). */
function statusTone(status: LibraryDocumentResponse['status']): 'neutral' | 'error' {
  return status === 'FAILED' ? 'error' : 'neutral'
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
  /** Beside the search, e.g. the filter „ohne Wert“. */
  filterControl?: ReactNode
  /** At the toolbar's right edge, e.g. „Hochladen“. */
  toolbarActions?: ReactNode
}

/** The caption's origin part in plain text, for the title of the cut line. */
function originText(document: LibraryDocumentResponse, viaFileName?: string | null): string | null {
  const parts = [
    document.sourceHierarchyPath,
    viaFileName ? `Anhang von ${viaFileName}` : null,
    document.folderPath,
    document.sourceEntryUrl ?? document.sourceUrl,
  ].filter(Boolean)
  return parts.length ? parts.join(' · ') : null
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
  filterControl,
  toolbarActions,
}: LibraryDocumentListProps) {
  const [allFolders, setAllFolders] = useState(false)
  const shownFolders =
    allFolders || folders.length <= FOLDERS_SHOWN + 2 ? folders : folders.slice(0, FOLDERS_SHOWN)
  const isSearchActive = Boolean(pageState?.q)
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

  /** A container key with the name the library's own selection gives it ("Name (KEY)"). */
  function containerName(key: string): string {
    const name = confluenceSpaceNameByKey.get(key)
    return name ? `${name} (${key})` : key
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
    const metadataOpen = Boolean(renderMetadataPanel && metadataOpenById?.[document.id])
    return (
      <Fragment key={document.id}>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            // below 600 px the actions move under the name instead of squeezing it
            flexWrap: { xs: 'wrap', sm: 'nowrap' },
            columnGap: 2,
            rowGap: 0.5,
            p: 1.5,
            ...(isAttachment && { py: 1.25 }),
          }}
        >
          {/* without checkboxes the column stays where folders show their symbol */}
          {!selectable && folders.length > 0 && <Box sx={{ width: 28, flexShrink: 0 }} />}
          {selectable && (
            <Checkbox
              size="small"
              checked={selectedIds.includes(document.id)}
              onChange={() => onToggleSelected(document.id)}
              slotProps={{ input: { 'aria-label': `Dokument ${document.fileName} auswählen` } }}
              sx={{ p: 0.5 }}
            />
          )}
          <Stack
            spacing={0.25}
            sx={{ minWidth: 0, flexGrow: 1, flexBasis: { xs: 'calc(100% - 56px)', sm: 'auto' } }}
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
              {isAttachment && <AttachFileIcon sx={{ fontSize: 16 }} color="action" />}
              {/* at most two lines; the whole name in the tooltip */}
              <Typography
                title={document.fileName}
                sx={{
                  fontWeight: isAttachment ? 500 : 600,
                  fontSize: isAttachment ? 13.5 : 14,
                  overflowWrap: 'anywhere',
                  hyphens: 'auto',
                  display: '-webkit-box',
                  WebkitLineClamp: 2,
                  WebkitBoxOrient: 'vertical',
                  overflow: 'hidden',
                }}
              >
                {document.fileName}
              </Typography>
            </Stack>
            {/* One caption line (P6): size, sections and date, then where the row comes from -
                cut with an ellipsis, the whole line in the title. */}
            <Typography
              variant="caption"
              title={originText(document, options.viaFileName) ?? undefined}
              sx={{
                color: 'text.secondary',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {formatFileSize(document.fileSize)} · {document.chunkCount}{' '}
              {document.chunkCount === 1 ? 'Abschnitt' : 'Abschnitte'} ·{' '}
              {formatIndexedAt(document.indexedAt)}
              {/* ADR-0023/ADR-0027: a row names its container under the noun its source
                  registered (a Confluence space, an S3 bucket) and its position below it. */}
              {document.sourceContainerKey &&
                ` · ${sourceRegistration(document.sourceType)?.containerLabel ?? 'Bereich'}: ${containerName(document.sourceContainerKey)}${document.sourceHierarchyPath ? ` › ${document.sourceHierarchyPath}` : ''}`}
              {options.viaFileName && ` · Anhang von ${options.viaFileName}`}
              {/* most useful on a search hit, whose result list has no breadcrumb */}
              {document.folderPath && (
                <>
                  {' · Ordner '}
                  <Link
                    component="button"
                    underline="hover"
                    onClick={() => onOpenFolder(document.folderId ?? null)}
                    sx={{ fontSize: 'inherit', verticalAlign: 'baseline' }}
                  >
                    {document.folderPath}
                  </Link>
                </>
              )}
              {/* an RSS attachment names its feed entry; otherwise the plain source address */}
              {(document.sourceEntryUrl ?? document.sourceUrl) && (
                <>
                  {document.sourceEntryUrl ? ' · Herkunft ' : ' · Quelle '}
                  <ExternalAddress
                    address={(document.sourceEntryUrl ?? document.sourceUrl) as string}
                  />
                </>
              )}
            </Typography>
          </Stack>
          <Stack
            direction="row"
            spacing={1}
            sx={{ alignItems: 'center', flexShrink: 0, ml: { xs: 'auto', sm: 0 } }}
          >
            {attachments.length > 0 && (
              <Button
                size="small"
                onClick={() => onToggleAttachmentGroup(document.id)}
                aria-expanded={expanded}
                aria-label={`Anhänge von ${document.fileName} ${expanded ? 'verbergen' : 'anzeigen'}`}
                endIcon={expanded ? <ExpandLessIcon /> : <ExpandMoreIcon />}
              >
                {attachments.length} {attachments.length === 1 ? 'Anhang' : 'Anhänge'}
              </Button>
            )}
            {/* A FAILED document's asynchronous processing failure is only visible to the
                user via this German errorMessage - the status chip alone only says something went
                wrong, not what. */}
            <Tooltip
              title={document.status === 'FAILED' ? (document.errorMessage ?? '') : ''}
              disableHoverListener={document.status !== 'FAILED' || !document.errorMessage}
            >
              <Box component="span" sx={{ minWidth: 112, display: 'inline-flex' }}>
                <StateDot
                  tone={statusTone(document.status)}
                  label={documentStatusLabel(document.status)}
                />
              </Box>
            </Tooltip>
            {renderMetadataPanel && (
              <Tooltip title={metadataOpen ? 'Metadaten verbergen' : 'Metadaten anzeigen'}>
                <IconButton
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

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        useFlexGap
        sx={{ mb: 2, alignItems: { sm: 'center' }, flexWrap: 'wrap' }}
      >
        <ListSearchField
          value={searchInput}
          onChange={onSearchChange}
          placeholder="Dateiname suchen"
          ariaLabel="Dokumente durchsuchen"
        />
        {filterControl}
        <Select
          size="small"
          value={pageState?.size ?? DEFAULT_PAGE_SIZE}
          onChange={(e) => onPageSizeChange(Number(e.target.value))}
          inputProps={{ 'aria-label': 'Dokumente je Seite' }}
          sx={{ flexShrink: 0, minWidth: 132 }}
        >
          {[20, 50, 100].map((size) => (
            <MenuItem key={size} value={size}>
              {size} je Seite
            </MenuItem>
          ))}
        </Select>
        {toolbarActions && <Box sx={{ ml: { sm: 'auto' } }}>{toolbarActions}</Box>}
      </Stack>

      {/* The selection toolbar also stands on the root level, where a library may show only
          folders - hiding it there made "Alle auf dieser Seite auswählen" look unavailable rather
          than simply empty. */}
      {selectable && (documents.length > 0 || folders.length > 0) && (
        <Stack
          direction="row"
          spacing={1.5}
          sx={{ alignItems: 'center', mb: 1.5, flexWrap: 'wrap' }}
          role="toolbar"
          aria-label="Sammelaktionen"
        >
          <FormControlLabel
            control={
              <Checkbox
                size="small"
                checked={allVisibleSelected}
                disabled={documents.length === 0}
                indeterminate={!allVisibleSelected && selectedIds.length > 0}
                onChange={onToggleAllVisible}
              />
            }
            label="Alle auf dieser Seite auswählen"
          />
          {/* accessibility.md 2.8: the count changes without moving the focus, so it is announced
              politely instead of staying silent for anyone not watching the checkboxes. */}
          <Typography
            variant="body2"
            role="status"
            aria-live="polite"
            sx={{ color: 'text.secondary' }}
          >
            {selectedIds.length > 0 ? `${selectedIds.length} ausgewählt` : ''}
          </Typography>
          {selectedIds.length > 0 && bulkActions}
          {selectedIds.length > 0 && (
            <Button size="small" onClick={onClearSelection}>
              Auswahl aufheben
            </Button>
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
                // the same edge, gap and checkbox as a document row
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 2,
                p: 1.5,
                borderBottom: 1,
                borderColor: 'divider',
                '&:last-of-type': { borderBottom: 0 },
              }}
            >
              {/* a folder cannot be selected: its symbol stands in the checkbox column, so the
                  names of folders and documents share one edge */}
              <Box
                aria-hidden
                sx={{ width: 28, flexShrink: 0, display: 'flex', justifyContent: 'center' }}
              >
                <FolderIcon color="action" sx={{ fontSize: 20 }} />
              </Box>
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
                <Typography
                  title={folder.name}
                  sx={{
                    fontWeight: 600,
                    fontSize: 14,
                    minWidth: 0,
                    overflowWrap: 'anywhere',
                    display: '-webkit-box',
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: 'vertical',
                    overflow: 'hidden',
                  }}
                >
                  {folder.name}
                </Typography>
                <Typography
                  variant="caption"
                  sx={{ color: 'text.secondary', whiteSpace: 'nowrap', flexShrink: 0 }}
                >
                  {folder.documentCount} {folder.documentCount === 1 ? 'Dokument' : 'Dokumente'}
                </Typography>
              </Box>
              {renderFolderActions?.(folder)}
            </Box>
          ))}
          {shownFolders.length < folders.length && (
            <Box sx={{ py: 1, borderBottom: 1, borderColor: 'divider' }}>
              <Button size="small" onClick={() => setAllFolders(true)}>
                Alle {folders.length} Ordner anzeigen
              </Button>
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

      {/* the count stands in the head; the foot speaks only while paging or searching */}
      {!isLoading && pageState && (pageCount > 1 || isSearchActive) && (
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
            {[
              isSearchActive
                ? `${pageState.totalElements.toLocaleString('de-DE')} Treffer für „${pageState.q}“`
                : null,
              pageCount > 1 ? `Seite ${(pageState.page ?? 0) + 1} von ${pageCount}` : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </Typography>
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
