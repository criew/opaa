import { Fragment } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
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
import type {
  ConfluenceSpaceRef,
  LibraryDocumentResponse,
  LibraryFolderListItem,
} from '../../types/api'
import type { DocumentPageState } from '../../stores/documentStore'
import { DEFAULT_PAGE_SIZE } from '../../stores/documentStore'
import { documentStatusLabel, formatFileSize } from '../../utils/labels'

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
  /** Rendered directly above the search field (#1943) - the Ordnerpfad of the open folder. */
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
   * all selected documents (#1943). Rendered in the selection toolbar.
   */
  bulkActions?: ReactNode
  onOpenFolder: (folderId: string | null) => void
  /** Upload only: the per-folder menu button. */
  renderFolderActions?: (folder: LibraryFolderListItem) => ReactNode
  /** Upload only: the per-row delete button. */
  renderRowActions?: (document: LibraryDocumentResponse) => ReactNode
  /** Which rows show their metadata panel (#1068) and what that panel is. */
  metadataOpenById: Record<string, boolean>
  onToggleMetadata: (documentId: string) => void
  renderMetadataPanel: (document: LibraryDocumentResponse) => ReactNode
  onOpenOriginal: (document: LibraryDocumentResponse) => void
  /** Per-parent expansion of the attachment group (#1184). */
  isGroupExpanded: (parentId: string) => boolean
  onToggleAttachmentGroup: (parentId: string) => void
}

/**
 * The one document list of a Wissensbibliothek (#1943) - search, page size, selection, folder rows,
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
}: LibraryDocumentListProps) {
  const isSearchActive = Boolean(pageState?.q)
  const isMissingFilterActive = Boolean(pageState?.missingMetadataField)
  const pageCount = pageState ? Math.max(1, Math.ceil(pageState.totalElements / pageState.size)) : 1

  const documentsById = new Map(documents.map((doc) => [doc.id, doc]))
  // #1184: the backend guarantees each attachment row follows its (transitive) top-level parent
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

  function confluenceSpaceLabel(key: string): string {
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
                sx={{
                  fontWeight: isAttachment ? 500 : 600,
                  fontSize: isAttachment ? 13.5 : undefined,
                  wordBreak: 'break-word',
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
            </Typography>
            {/* ADR-0023 (#1136)/ADR-0027: a Confluence row names its space and the page's position
                in its hierarchy, an S3 row its bucket and the key's folders below the scope prefix -
                as text, since an s3:// path is no link a browser could open. */}
            {document.sourceContainerKey && (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                {document.sourceType === 'S3'
                  ? `Bucket: ${document.sourceContainerKey}`
                  : `Space: ${confluenceSpaceLabel(document.sourceContainerKey)}`}
                {document.sourceHierarchyPath ? ` · ${document.sourceHierarchyPath}` : ''}
              </Typography>
            )}
            {options.viaFileName && (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Anhang von: {options.viaFileName}
              </Typography>
            )}
            {/* #822: most useful on a search hit (bibliotheksweit, ADR-0020), whose result list has
                no breadcrumb of its own to place it in the structure. */}
            {document.folderPath && (
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
            {/* #493: sourceEntryUrl trägt nur eine Anlage aus einem RSS-Feed-Eintrag - der Link
                macht sichtbar, aus welchem Eintrag sie stammt. */}
            {document.sourceEntryUrl && (
              <Typography
                variant="caption"
                sx={{ color: 'text.secondary', wordBreak: 'break-word' }}
              >
                Herkunft:{' '}
                <Link href={document.sourceEntryUrl} target="_blank" rel="noopener noreferrer">
                  {document.sourceEntryUrl}
                </Link>
              </Typography>
            )}
            {/* #747: only shown when sourceEntryUrl above is absent, so the same remote address
                never appears twice for an RSS attachment. */}
            {!document.sourceEntryUrl && document.sourceUrl && (
              <Typography
                variant="caption"
                sx={{ color: 'text.secondary', wordBreak: 'break-word' }}
              >
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
            {/* #434: a FAILED document's asynchronous processing failure is only visible to the
                user via this German errorMessage - the status chip alone only says something went
                wrong, not what. */}
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
            <Tooltip title={metadataOpen ? 'Metadaten verbergen' : 'Metadaten anzeigen'}>
              <IconButton
                aria-label={`Metadaten von ${document.fileName} ${metadataOpen ? 'verbergen' : 'anzeigen'}`}
                aria-expanded={metadataOpen}
                size="small"
                color={metadataOpen ? 'primary' : 'default'}
                onClick={() => onToggleMetadata(document.id)}
              >
                <InfoOutlinedIcon fontSize="small" />
              </IconButton>
            </Tooltip>
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
        {metadataOpen && renderMetadataPanel(document)}
      </Fragment>
    )
  }

  return (
    <Box>
      {breadcrumb}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}>
        <TextField
          size="small"
          fullWidth
          value={searchInput}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Dokumente durchsuchen — Dateiname enthält …"
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
      </Stack>

      {/* #1943: the selection toolbar also stands on the root level, where a library may show only
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
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            {selectedIds.length} ausgewählt
          </Typography>
          {bulkActions}
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
          {/* #822: folders are rows ahead of the documents, mirroring a plain file browser - a
              folder's documentCount already counts its own subtree recursively. */}
          {folders.map((folder) => (
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
              {/* #1943: a folder is not a document and cannot be selected - said with a disabled
                  checkbox and its reason, rather than by leaving the column blank and making the
                  rows below look misaligned. */}
              {selectable && (
                <Tooltip title="Ordner lassen sich nicht auswählen — nur die Dokumente darin">
                  <Box component="span" sx={{ display: 'inline-flex' }}>
                    <Checkbox
                      size="small"
                      disabled
                      checked={false}
                      slotProps={{
                        input: {
                          'aria-label': `Ordner ${folder.name} ist nicht auswählbar`,
                        },
                      }}
                      sx={{ p: 0.5 }}
                    />
                  </Box>
                </Tooltip>
              )}
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
                <Typography sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
                  {folder.name}
                </Typography>
                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  {folder.documentCount} {folder.documentCount === 1 ? 'Dokument' : 'Dokumente'}
                </Typography>
              </Box>
              {renderFolderActions?.(folder)}
            </Box>
          ))}
          {/* #1184: a top-level document with its attachments is one group - the attachments hang
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
