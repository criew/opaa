import { useCallback, useEffect, useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Link from '@mui/material/Link'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import CloseIcon from '@mui/icons-material/Close'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import SearchIcon from '@mui/icons-material/Search'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { CitationIndex } from '@/shared/ui/markdown/citations'
import {
  citationRowId,
  metadataFilterMatchLabel,
  withTitleMetadataOnly,
} from '@/shared/ui/markdown/citations'
import type {
  ChunkAnchor,
  SourceBlockReason,
  SourceFreeze,
  SourceReference,
  SourceTypeKey,
} from '@/shared/api/types'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import type { OpenableDocument } from '@/features/library'
import { focusRingWidthPx, fontFamily } from '@/shared/theme/tokens'

interface SourceEvidenceDrawerProps {
  open: boolean
  onClose: () => void
  /** Scopes the rows' element ids to this answer - several answers each own a Belegfenster. */
  messageId: string
  citations: CitationIndex
  /** Rows in {@link CitationIndex.docs} a clicked footnote points at: they are marked, and the
   *  first one is scrolled into view on opening. Empty when opened via "Belege anzeigen". */
  focusedDocIndexes?: number[]
  /** When the answer arrived - mockup 1i's footer line. */
  answeredAt: Date
  /** MessageBubble's `useDocumentPreview()` instance - the preview dialog/download snackbar it
   *  drives are rendered by MessageBubble as siblings of this Drawer, not inside it, so they
   *  survive the Drawer unmounting its children on close. */
  openDocument: (document: OpenableDocument) => Promise<void>
}

interface EvidenceDoc {
  fileName: string
  numbers: number[]
  cited: boolean
  /** The row's index in {@link CitationIndex.docs}; undefined for an uncited source. */
  docIndex?: number
  /** The Fundorte of this row's footnotes ("S. 2–4 · Abschn. Fristsetzung"). */
  locations: string[]
  /** Where „Dokument öffnen“ jumps: the anchor of the row's first cited passage. */
  anchor?: ChunkAnchor | null
  /** SHA-256 of the original as indexed when the answer was given. */
  contentSha256?: string | null
  /** False when the backend's deterministic check found at least one citation naming this
   *  source that does not match the chunks actually retrieved for this answer. */
  citationValid: boolean
  /** Present when the source's library was not updated when the answer was given. */
  freeze?: SourceFreeze | null
  /** The source came from one of the caller's private libraries. */
  privateSource: boolean
  sourceEntryUrl?: string | null
  /** The original's document id - openable via GET /documents/{id}/content for every
   *  sourceType (that endpoint proxies HTTP_DIRECTORY/RSS_FEED server-side). Undefined
   *  for a synthetic entry. */
  documentId?: string | null
  sourceType?: SourceTypeKey | null
  /** The remote source URL for sourceType HTTP_DIRECTORY/RSS_FEED, mirroring
   *  LibraryDocumentResponse.sourceUrl - handed to the opener, not shown in the row. */
  sourceUrl?: string | null
  /** "ohne Angabe" for a hit the Leerwert rule kept under an active filter. */
  filterMatchLabel?: string
}

function formatAnsweredAt(answeredAt: Date): string {
  return `${answeredAt.toLocaleDateString('de-DE', { dateStyle: 'medium' })}, ${answeredAt.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`
}

/** What happens to a source that is not updated, by the reason the backend reports. */
const FREEZE_REASONS: Record<SourceBlockReason, string> = {
  TYPE_LOCKED: 'die Quelle ist gesperrt und wird nicht mehr aktualisiert',
  PROFILE_LOCKED: 'die Quelle ist gesperrt und wird nicht mehr aktualisiert',
  PROFILE_REQUIRED:
    'die Quelle ist gesperrt, bis sie einem Zugang zugeordnet ist, und wird nicht mehr aktualisiert',
  NOT_CONNECTED: 'die Verbindung zur Quelle ist getrennt, der Inhalt wird nicht aktualisiert',
  ACCESS_REMOVED: 'der Zugang der Quelle wurde entfernt, der Inhalt wird nicht aktualisiert',
  OWNER_DEACTIVATED:
    'das Konto der Besitzerin ist deaktiviert, der Inhalt wird nicht aktualisiert und nach Ablauf der Löschfrist gelöscht',
  DORMANT: 'die Verbindung der Quelle ruht, der Inhalt wird nicht aktualisiert',
  TARGET_OUTSIDE_PROFILE:
    'die Adresse der Quelle liegt außerhalb ihres Zugangs, der Inhalt wird nicht aktualisiert',
  EXPIRED: 'die Anmeldung an der Quelle ist abgelaufen, der Inhalt wird nicht aktualisiert',
}

/**
 * „Stand vom …“ for a source whose library was not updated when the answer was given (spec
 * „Konnektor-Freigabe und Sperre“): the library's last successful run, what happens to the content
 * and who is in charge - all three from the backend.
 */
function freezeNotice(freeze: SourceFreeze): string {
  const date = freeze.asOf
    ? new Date(freeze.asOf).toLocaleDateString('de-DE', { dateStyle: 'medium' })
    : 'unbekannt'
  return `Stand vom ${date} – ${FREEZE_REASONS[freeze.block.reason]} (zuständig: ${freeze.block.responsible})`
}

/**
 * The Belegfenster: the side panel with the sources of one answer. It opens with the cited
 * documents in citation-number order; the disclosure "Nicht zitierte anzeigen" below them appends
 * the checked but uncited ones in pipeline order, and a search always covers both groups. A row
 * names the document and its Fundort only (guidelines 5.8).
 */
export default function SourceEvidenceDrawer({
  open,
  onClose,
  messageId,
  citations,
  focusedDocIndexes = [],
  answeredAt,
  openDocument,
}: SourceEvidenceDrawerProps) {
  const [query, setQuery] = useState('')
  const [showUncited, setShowUncited] = useState(false)
  // Polite status line: the unfolded rows appear above the disclosure, out of a screen reader's
  // reading position, so their arrival is announced instead.
  const [announcement, setAnnouncement] = useState('')

  // Every opening starts with an empty search and the uncited sources folded away, so a
  // footnote's Beleg - always a cited one - is never hidden behind a search left over from the
  // last visit. State adjustment during render, not an effect.
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setQuery('')
      setShowUncited(false)
      setAnnouncement('')
    }
  }

  const firstFocusedDocIndex = focusedDocIndexes[0]
  useEffect(() => {
    if (!open || firstFocusedDocIndex === undefined) return
    // The Drawer mounts its rows in the same commit; one frame later they are laid out. Focus
    // follows the mark, so keyboard and screen reader users land on the Beleg they asked for.
    const frame = requestAnimationFrame(() => {
      const row = document.getElementById(citationRowId(messageId, firstFocusedDocIndex))
      row?.scrollIntoView?.({ block: 'center' })
      row?.focus({ preventScroll: true })
    })
    return () => cancelAnimationFrame(frame)
  }, [open, firstFocusedDocIndex, messageId])

  // A document with a documentId opens through the Bearer-authenticated content
  // endpoint (a plain <a href> cannot carry the token, ADR-0005), which proxies
  // HTTP_DIRECTORY/RSS_FEED server-side; a CONFLUENCE document opens at its own source URL
  // instead (useDocumentPreview, ADR-0023 - the endpoint deliberately has no original there).
  async function handleOpenLocalOriginal(doc: EvidenceDoc) {
    if (!doc.documentId) return
    await openDocument({
      id: doc.documentId,
      fileName: doc.fileName,
      sourceType: doc.sourceType,
      sourceUrl: doc.sourceUrl,
      sourceEntryUrl: doc.sourceEntryUrl,
      anchor: doc.anchor,
      contentSha256: doc.contentSha256,
    })
  }

  // Without metadata (ADR-0044) a Beleg carries no filter mark.
  const metadataEnabled = useFeatureEnabled('metadata')
  const shown = useCallback(
    (source: SourceReference | undefined) =>
      metadataEnabled ? source : withTitleMetadataOnly(source),
    [metadataEnabled],
  )

  const docs = useMemo((): EvidenceDoc[] => {
    const cited: EvidenceDoc[] = citations.docs.map((doc, docIndex) => ({
      fileName: doc.fileName,
      numbers: doc.numbers,
      cited: true,
      docIndex,
      locations: doc.locations,
      anchor: doc.anchors[0] ?? null,
      contentSha256: doc.source?.contentSha256,
      citationValid: doc.source?.citationValid !== false,
      freeze: doc.source?.freeze ?? null,
      privateSource: doc.source?.privateSource === true,
      sourceEntryUrl: doc.source?.sourceEntryUrl,
      documentId: doc.source?.documentId,
      sourceType: doc.source?.sourceType,
      sourceUrl: doc.source?.sourceUrl,
      filterMatchLabel: metadataFilterMatchLabel(shown(doc.source)),
    }))
    const uncited: EvidenceDoc[] = citations.uncited.map((source) => ({
      fileName: source.fileName,
      numbers: [],
      cited: false,
      locations: [],
      citationValid: source.citationValid !== false,
      freeze: source.freeze ?? null,
      privateSource: source.privateSource === true,
      sourceEntryUrl: source.sourceEntryUrl,
      documentId: source.documentId,
      sourceType: source.sourceType,
      sourceUrl: source.sourceUrl,
      filterMatchLabel: metadataFilterMatchLabel(shown(source)),
    }))
    // Cited rows keep `citations.docs` order, ascending by citation number; uncited ones keep
    // `citations.uncited` order, the pipeline's. Never reordered by relevanceScore - a persisted
    // message may still carry a legacy path-dependent raw score.
    return [...cited, ...uncited]
  }, [citations, shown])

  const hasCited = citations.docs.length > 0
  const hasUncited = citations.uncited.length > 0
  const searching = query.trim().length > 0
  const visibleDocs = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return docs
      .filter((doc) => showUncited || doc.cited || needle.length > 0)
      .filter(
        (doc) =>
          needle.length === 0 ||
          doc.fileName.toLowerCase().includes(needle) ||
          doc.locations.some((location) => location.toLowerCase().includes(needle)),
      )
  }, [showUncited, docs, query])
  const listId = `belege-${messageId}`

  function toggleUncited() {
    const next = !showUncited
    const count = citations.uncited.length
    setShowUncited(next)
    setAnnouncement(
      next
        ? count === 1
          ? '1 nicht zitierter Beleg eingeblendet'
          : `${count} nicht zitierte Belege eingeblendet`
        : 'Nicht zitierte Belege ausgeblendet',
    )
  }

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{
        paper: {
          role: 'dialog',
          'aria-label': 'Belege dieser Antwort',
          sx: { width: { xs: '100%', sm: 440 }, display: 'flex', flexDirection: 'column' },
        },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 1,
          px: 2.5,
          pt: 2,
          pb: 1.5,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Typography sx={{ flex: 1, minWidth: 0, fontSize: 16, fontWeight: 600 }}>
          Belege dieser Antwort
        </Typography>
        <IconButton size="small" onClick={onClose} aria-label="Belegfenster schließen">
          <CloseIcon sx={{ fontSize: 18 }} />
        </IconButton>
      </Box>

      <Box sx={{ px: 2.5, py: 1.5 }}>
        <TextField
          fullWidth
          size="small"
          placeholder="In Belegen suchen …"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon sx={{ fontSize: 16 }} />
                </InputAdornment>
              ),
            },
            htmlInput: { 'aria-label': 'In Belegen suchen' },
          }}
        />
      </Box>

      <Box id={listId} sx={{ flex: 1, overflowY: 'auto', px: 2.5, pb: 2 }}>
        {visibleDocs.length === 0 ? (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', py: 2 }}>
            {searching || hasCited
              ? 'Kein Beleg passt zur Suche.'
              : 'Diese Antwort zitiert keinen Beleg.'}
          </Typography>
        ) : (
          visibleDocs.map((doc) => {
            const focused = doc.docIndex !== undefined && focusedDocIndexes.includes(doc.docIndex)
            return (
              <Box
                // Two distinct documents may share a file name and each get their own row,
                // so the file name alone is not a unique key.
                key={doc.documentId ?? doc.fileName}
                id={doc.docIndex !== undefined ? citationRowId(messageId, doc.docIndex) : undefined}
                data-testid="evidence-doc"
                data-file={doc.fileName}
                data-cited={doc.cited ? 'true' : 'false'}
                data-citation-valid={doc.citationValid ? 'true' : 'false'}
                data-focused={focused ? 'true' : undefined}
                aria-current={focused ? 'true' : undefined}
                tabIndex={focused ? -1 : undefined}
                sx={(theme) => ({
                  // Full-bleed rows, so the mark of a footnote's Beleg spans the panel's width.
                  mx: -2.5,
                  px: 2.5,
                  py: 1.25,
                  borderBottom: 1,
                  borderColor: 'divider',
                  scrollMarginBlock: theme.spacing(2),
                  ...(focused && {
                    bgcolor: alpha(
                      theme.palette.primary.main,
                      theme.palette.mode === 'dark' ? 0.16 : 0.07,
                    ),
                    boxShadow: `inset 3px 0 0 ${theme.palette.primary.main}`,
                  }),
                  // Inset: the panel scrolls and would clip a ring outside the full-bleed row.
                  '&:focus-visible': { outlineOffset: `-${focusRingWidthPx}px` },
                })}
              >
                <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
                  {doc.numbers.length > 0 && (
                    <Typography
                      component="span"
                      sx={{
                        flex: 'none',
                        fontFamily: fontFamily.mono,
                        fontSize: 10.5,
                        fontWeight: 600,
                        color: 'primary.main',
                      }}
                    >
                      {doc.numbers.join('·')}
                    </Typography>
                  )}
                  {/* An uncited row steps back by colour role, not opacity, so every text keeps
                    its contrast (guidelines 2.3). */}
                  <Typography
                    component="span"
                    noWrap
                    sx={{
                      fontSize: 13,
                      fontWeight: 500,
                      color: doc.cited ? 'text.primary' : 'text.secondary',
                    }}
                  >
                    {doc.fileName}
                  </Typography>
                  {!doc.cited && (
                    <Typography component="span" sx={{ fontSize: 10.5, color: 'text.secondary' }}>
                      geprüft, nicht zitiert
                    </Typography>
                  )}
                  {doc.privateSource && (
                    <Box
                      component="span"
                      data-testid="source-private"
                      title="Aus Ihrer privaten Bibliothek – nur Sie sehen diese Quelle"
                      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, flex: 'none' }}
                    >
                      <LockOutlinedIcon
                        aria-hidden
                        sx={{ fontSize: 12, color: 'text.secondary' }}
                      />
                      <Typography component="span" sx={{ fontSize: 10.5, color: 'text.secondary' }}>
                        Private Quelle
                      </Typography>
                    </Box>
                  )}
                  {!doc.citationValid && (
                    // The signal colour is in the icon (error.main, above 3:1 in both schemes), the
                    // text runs in text.secondary (design guidelines 2.3).
                    <Box
                      component="span"
                      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
                    >
                      <ReportProblemOutlinedIcon sx={{ fontSize: 13, color: 'error.main' }} />
                      <Typography component="span" sx={{ fontSize: 10.5, color: 'text.secondary' }}>
                        Beleg nicht bestätigt
                      </Typography>
                    </Box>
                  )}
                </Box>
                {/* Where in the document the cited passages sit. */}
                {doc.locations.length > 0 && (
                  <Typography
                    component="span"
                    data-testid="source-location"
                    sx={{ display: 'block', fontSize: 11.5, color: 'text.secondary', mt: 0.25 }}
                  >
                    {doc.locations.join(' · ')}
                  </Typography>
                )}
                {/* The Leerwert mark of a hit kept under an active filter. */}
                {doc.filterMatchLabel && (
                  <Typography
                    component="span"
                    data-testid="source-filter-match"
                    aria-label="Metadatenfilter: ohne Angabe im gefilterten Feld"
                    sx={{ display: 'block', fontSize: 11, color: 'text.secondary', mt: 0.25 }}
                  >
                    {doc.filterMatchLabel}
                  </Typography>
                )}
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'baseline',
                    gap: 1,
                    flexWrap: 'wrap',
                    mt: 0.25,
                  }}
                >
                  {doc.freeze && (
                    <Typography
                      component="span"
                      data-testid="source-frozen"
                      sx={{ fontSize: 11.5, color: 'warning.main', fontWeight: 600 }}
                    >
                      {freezeNotice(doc.freeze)}
                    </Typography>
                  )}
                  {/* Every sourceType with a documentId opens via the content endpoint
                    (LibraryDetailPage#handleOpenOriginal); a synthetic entry has none. */}
                  {doc.documentId && (
                    <Link
                      component="button"
                      type="button"
                      underline="hover"
                      onClick={() => void handleOpenLocalOriginal(doc)}
                      sx={{ fontSize: 11.5 }}
                    >
                      Dokument öffnen
                    </Link>
                  )}
                </Box>
              </Box>
            )
          })
        )}
        {/* Disclosure pattern: a fixed name, only aria-expanded (and the chevron) change. A search
          already covers the uncited rows, so the disclosure steps aside while one is active. */}
        {hasUncited && !searching && (
          <Link
            component="button"
            type="button"
            underline="hover"
            aria-expanded={showUncited}
            aria-controls={listId}
            onClick={toggleUncited}
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.25,
              mt: 1.5,
              fontSize: 12.5,
            }}
          >
            Nicht zitierte anzeigen
            <ExpandMoreIcon
              aria-hidden
              sx={{
                fontSize: 16,
                transform: showUncited ? 'rotate(180deg)' : 'none',
                '@media (prefers-reduced-motion: no-preference)': {
                  transition: 'transform 120ms',
                },
              }}
            />
          </Link>
        )}
        <Box component="span" role="status" aria-live="polite" sx={visuallyHidden}>
          {announcement}
        </Box>
      </Box>

      <Box sx={{ px: 2.5, py: 1.5, borderTop: 1, borderColor: 'divider' }}>
        <Typography sx={{ fontSize: 11, color: 'text.secondary' }}>
          Stand der Antwort: {formatAnsweredAt(answeredAt)}
        </Typography>
      </Box>
    </Drawer>
  )
}
