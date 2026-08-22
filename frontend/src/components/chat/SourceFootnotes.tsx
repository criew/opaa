import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Collapse from '@mui/material/Collapse'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import type { CitationIndex } from './citations'
import { citationRowId } from './citations'
import type { SourceReference } from '../../types/api'
import { fontFamily } from '../../theme/tokens'
import { openDocumentContent } from '../../utils/documentContent'

interface SourceFootnotesProps {
  messageId: string
  citations: CitationIndex
  /** Rows to light up after a footnote click - covers ranges the URL hash cannot (#590). */
  highlightedDocIndexes?: number[]
  /** Opens the Belegfenster with every source of this answer (#592, mockup 1i). */
  onOpenEvidence?: () => void
}

function formatIndexedAt(indexedAt: string | null | undefined): string | null {
  if (!indexedAt) return null
  return `indiziert ${new Date(indexedAt).toLocaleDateString('de-DE', { dateStyle: 'medium' })}`
}

/** #739/#747: whether a source carries anything at all to open - every sourceType with a
 *  documentId now opens through the content endpoint (#747: it proxies HTTP_DIRECTORY/RSS_FEED
 *  server-side instead of answering 404). False for a synthetic entry (#386), whose citation
 *  named a document id that matched no retrieved chunk at all. */
function canOpenOriginal(source: SourceReference | undefined): boolean {
  return Boolean(source?.documentId)
}

/**
 * #739/#747: opens a citation's original document through the Bearer-authenticated content
 * endpoint, mirroring LibraryDetailPage#handleOpenOriginal (#738) - a plain `<a href>` cannot
 * carry the token (ADR-0005), and since #747 this now covers every sourceType: the endpoint
 * proxies HTTP_DIRECTORY/RSS_FEED server-side from their own stored source URL instead of
 * answering 404, so a source only reachable from OPAA's own network (the demo's
 * `http://demo-corpus/...`) still opens for the caller's browser.
 */
async function openLocalOriginal(
  source: SourceReference,
  fileName: string,
  onError: (message: string) => void,
) {
  if (!source.documentId) return
  try {
    await openDocumentContent(source.documentId, fileName)
  } catch (err) {
    onError(err instanceof Error ? err.message : 'Das Original konnte nicht geöffnet werden.')
  }
}

/**
 * #747: the primary action is always the content-endpoint button now (see
 * {@link openLocalOriginal}) - `sourceEntryUrl`/`sourceUrl` (HTTP_DIRECTORY/RSS_FEED only) are
 * shown alongside it as secondary information, a small "Quelle" link carrying the raw URL as its
 * native `title` tooltip, since that address may only be reachable from OPAA's own network, not
 * the caller's browser (#747, Klick-Test finding on the Demo-Instanz). A plain `title` attribute
 * rather than MUI's `Tooltip` (which clones the child and sets `aria-label` to the tooltip text,
 * overriding "Quelle" as the link's accessible name with the raw URL instead).
 */
function renderOpenOriginalLink(
  source: SourceReference,
  fileName: string,
  onOpenLocalOriginal: (source: SourceReference, fileName: string) => void,
) {
  const secondaryUrl = source.sourceEntryUrl ?? source.sourceUrl
  return (
    <>
      <Link
        component="button"
        type="button"
        underline="hover"
        onClick={() => onOpenLocalOriginal(source, fileName)}
        sx={{ fontSize: 11 }}
      >
        Im Dokument öffnen
      </Link>
      {secondaryUrl && (
        <Link
          href={secondaryUrl}
          target="_blank"
          rel="noopener noreferrer"
          underline="hover"
          title={secondaryUrl}
          sx={{ fontSize: 11, color: 'text.disabled' }}
        >
          Quelle
        </Link>
      )}
    </>
  )
}

/** The metadata the API can already vouch for - the mockup's Abschnitt/Paragraf follow with the
 *  backend's location metadata (#590 follow-up). */
function sourceMeta(doc: CitationIndex['docs'][number]): string | null {
  if (!doc.source) return null
  const parts = [doc.source.spaceName, formatIndexedAt(doc.source.indexedAt)].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : null
}

/**
 * Mockup 1a's Fundstellen block (#590): a quiet footnote list under the answer - eyebrow, count
 * line, one row per document with its footnote numbers, and the checked-but-uncited tail behind
 * a toggle. Replaces the former SourceCard strip.
 */
function renderDocRow(
  doc: CitationIndex['docs'][number],
  docIndex: number,
  messageId: string,
  highlighted: boolean,
  onOpenLocalOriginal: (source: SourceReference, fileName: string) => void,
) {
  return (
    <Box
      key={doc.fileName}
      id={citationRowId(messageId, docIndex)}
      data-testid="source-card"
      data-cited="true"
      data-highlighted={highlighted ? 'true' : undefined}
      sx={{
        display: 'flex',
        alignItems: 'baseline',
        gap: 1,
        flexWrap: 'wrap',
        fontSize: 12,
        lineHeight: 1.5,
        borderRadius: '4px',
        // One mechanism, one truth (#590 Nachbesserung): the anchor only scrolls, the
        // click-driven transient highlight marks every covered row. A parallel :target rule
        // would keep the first row lit after the others faded - the hash stays in the URL.
        '&[data-highlighted="true"]': {
          bgcolor: (theme) => alpha(theme.palette.primary.main, 0.1),
        },
        transition: 'background-color 200ms',
      }}
    >
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
      <Typography component="span" sx={{ fontSize: 12, fontWeight: 500 }}>
        {doc.fileName}
      </Typography>
      {sourceMeta(doc) && (
        <Typography component="span" sx={{ fontSize: 12, color: 'text.disabled' }}>
          {sourceMeta(doc)}
        </Typography>
      )}
      {/* #739/#745: a button for a local original (download endpoint), a real link for a remote
          sourceEntryUrl/sourceUrl deep link (see renderOpenOriginalLink/canOpenOriginal). */}
      {canOpenOriginal(doc.source) &&
        renderOpenOriginalLink(doc.source!, doc.fileName, onOpenLocalOriginal)}
    </Box>
  )
}

/** Rows shown before the block folds - mockup 1a's own answer to long source lists: the block
 *  keeps a constant footprint, the first-cited documents (lowest footnote numbers) stay visible,
 *  everything else is one quiet interaction away (#590 Nachbesserung). */
const VISIBLE_DOCS = 3

export default function SourceFootnotes({
  messageId,
  citations,
  highlightedDocIndexes = [],
  onOpenEvidence,
}: SourceFootnotesProps) {
  const [uncitedOpen, setUncitedOpen] = useState(false)
  const [foldedOpen, setFoldedOpen] = useState(false)
  // #739: mirrors LibraryDetailPage's openOriginalError (#738) - opening the original is a
  // read-only, per-click action, so its failure (404, file missing) gets its own local message.
  const [openOriginalError, setOpenOriginalError] = useState<string | null>(null)
  const handleOpenLocalOriginal = (source: SourceReference, fileName: string) => {
    setOpenOriginalError(null)
    void openLocalOriginal(source, fileName, setOpenOriginalError)
  }
  const { docs, uncited, markerCount } = citations

  const visibleDocs = docs.slice(0, VISIBLE_DOCS)
  const foldedDocs = docs.slice(VISIBLE_DOCS)
  const foldedStellen = foldedDocs.reduce((sum, doc) => sum + doc.numbers.length, 0)

  // A clicked range may cover folded rows - unfold so the highlight is actually visible, and
  // stay unfolded after the flash fades. State adjustment during render (the React-documented
  // pattern) instead of an effect, so no cascading render frame is needed.
  const highlightNeedsUnfold = highlightedDocIndexes.some((i) => i >= VISIBLE_DOCS)
  const [prevHighlightNeedsUnfold, setPrevHighlightNeedsUnfold] = useState(false)
  if (highlightNeedsUnfold !== prevHighlightNeedsUnfold) {
    setPrevHighlightNeedsUnfold(highlightNeedsUnfold)
    if (highlightNeedsUnfold && !foldedOpen) {
      setFoldedOpen(true)
    }
  }

  // A footnote in the text may target a folded row - unfold before the browser scrolls there,
  // so the anchor jump never lands on a collapsed element.
  useEffect(() => {
    if (foldedDocs.length === 0) return
    const openIfFoldedTarget = () => {
      const hash = window.location.hash.slice(1)
      const targetIndex = docs.findIndex((_, i) => citationRowId(messageId, i) === hash)
      if (targetIndex >= VISIBLE_DOCS) {
        setFoldedOpen(true)
        requestAnimationFrame(() =>
          document.getElementById(hash)?.scrollIntoView({ block: 'nearest' }),
        )
      }
    }
    // Deferred initial check (deep link onto a folded row) - synchronous setState in an
    // effect body would cascade renders; the subscription callback itself is exempt.
    const initialCheck = requestAnimationFrame(openIfFoldedTarget)
    window.addEventListener('hashchange', openIfFoldedTarget)
    return () => {
      cancelAnimationFrame(initialCheck)
      window.removeEventListener('hashchange', openIfFoldedTarget)
    }
  }, [docs, foldedDocs.length, messageId])

  if (docs.length === 0 && uncited.length === 0) {
    return null
  }

  // Mockup 1a's count line. Markers are the exact truth; when an answer carries none (older
  // turns, mock data), the cited sources' matchCount sums to the honest fallback.
  const stellenCount =
    markerCount > 0
      ? markerCount
      : docs.reduce((sum, doc) => sum + (doc.source?.matchCount ?? 1), 0)
  const stellen = stellenCount === 1 ? '1 Stelle' : `${stellenCount} Stellen`
  const dokumente = docs.length === 1 ? '1 Dokument' : `${docs.length} Dokumenten`
  const foldedLabel =
    `${foldedDocs.length} ${foldedDocs.length === 1 ? 'weiteres Dokument' : 'weitere Dokumente'}` +
    ` mit ${foldedStellen === 1 ? '1 Stelle' : `${foldedStellen} Stellen`}`

  return (
    <Box sx={{ mt: 1.75, pt: 1.25, borderTop: 1, borderColor: 'divider' }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, mb: 0.75 }}>
        <Typography
          component="span"
          sx={{
            fontFamily: fontFamily.mono,
            fontSize: 9.5,
            letterSpacing: '0.08em',
            textTransform: 'uppercase',
            color: 'text.disabled',
          }}
        >
          Fundstellen
        </Typography>
        {docs.length > 0 && (
          <Typography component="span" sx={{ fontSize: 11, color: 'text.disabled' }}>
            {stellen} in {dokumente}
          </Typography>
        )}
      </Box>

      {openOriginalError && (
        <Alert severity="error" sx={{ mb: 0.75 }} onClose={() => setOpenOriginalError(null)}>
          {openOriginalError}
        </Alert>
      )}

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        {visibleDocs.map((doc, docIndex) =>
          renderDocRow(
            doc,
            docIndex,
            messageId,
            highlightedDocIndexes.includes(docIndex),
            handleOpenLocalOriginal,
          ),
        )}
        {foldedOpen &&
          foldedDocs.map((doc, i) => {
            const docIndex = i + VISIBLE_DOCS
            return renderDocRow(
              doc,
              docIndex,
              messageId,
              highlightedDocIndexes.includes(docIndex),
              handleOpenLocalOriginal,
            )
          })}
      </Box>

      {(foldedDocs.length > 0 || uncited.length > 0 || onOpenEvidence) && (
        <Box sx={{ mt: 0.75, display: 'flex', alignItems: 'baseline', gap: 1, flexWrap: 'wrap' }}>
          {foldedDocs.length > 0 && (
            <Link
              component="button"
              type="button"
              underline="hover"
              onClick={() => setFoldedOpen((open) => !open)}
              sx={{ fontSize: 11, color: 'text.disabled' }}
            >
              {foldedLabel} {foldedOpen ? 'ausblenden' : 'anzeigen'}
            </Link>
          )}
          {foldedDocs.length > 0 && uncited.length > 0 && (
            <Typography component="span" sx={{ fontSize: 11, color: 'text.disabled' }}>
              ·
            </Typography>
          )}
          {uncited.length > 0 && (
            <Link
              component="button"
              type="button"
              underline="hover"
              onClick={() => setUncitedOpen((open) => !open)}
              sx={{ fontSize: 11, color: 'text.disabled' }}
            >
              Weitere geprüfte, nicht zitierte Treffer ({uncited.length}){' '}
              {uncitedOpen ? 'ausblenden' : 'anzeigen'}
            </Link>
          )}
          {onOpenEvidence && (foldedDocs.length > 0 || uncited.length > 0) && (
            <Typography component="span" sx={{ fontSize: 11, color: 'text.disabled' }}>
              ·
            </Typography>
          )}
          {onOpenEvidence && (
            <Link
              component="button"
              type="button"
              underline="hover"
              onClick={onOpenEvidence}
              sx={{ fontSize: 11, color: 'text.disabled' }}
            >
              Alle als Liste im Belegfenster öffnen
            </Link>
          )}
        </Box>
      )}

      {uncited.length > 0 && (
        <>
          <Collapse in={uncitedOpen}>
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, mt: 0.5 }}>
              {uncited.map((source) => (
                <Box
                  key={source.fileName}
                  data-testid="source-card"
                  data-cited="false"
                  sx={{ display: 'flex', alignItems: 'baseline', gap: 1, fontSize: 12 }}
                >
                  <Typography component="span" sx={{ fontSize: 12, color: 'text.secondary' }}>
                    {source.fileName}
                  </Typography>
                  {source.spaceName && (
                    <Typography component="span" sx={{ fontSize: 12, color: 'text.disabled' }}>
                      {source.spaceName}
                    </Typography>
                  )}
                  {canOpenOriginal(source) &&
                    renderOpenOriginalLink(source, source.fileName, handleOpenLocalOriginal)}
                </Box>
              ))}
            </Box>
          </Collapse>
        </>
      )}
    </Box>
  )
}
