import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Collapse from '@mui/material/Collapse'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import type { CitationIndex } from './citations'
import { citationRowId, formatMailSummary } from './citations'
import type { SourceReference } from '../../types/api'
import { fontFamily } from '../../theme/tokens'

interface SourceFootnotesProps {
  messageId: string
  citations: CitationIndex
  /** Rows to light up after a footnote click - covers ranges the URL hash cannot (#590). */
  highlightedDocIndexes?: number[]
  /** Opens the Belegfenster with every source of this answer (#592, mockup 1i). */
  onOpenEvidence?: () => void
  /** #739/#747/#780: MessageBubble's single `useDocumentPreview()` instance (../../hooks/
   *  useDocumentPreview) - shared with SourceEvidenceDrawer so the preview dialog/download
   *  snackbar it also renders survive this component or the Belegfenster unmounting (#781
   *  review, Nit 5), rather than each row wiring its own call to `openDocumentContent` and
   *  discarding the result (#781 review, Wichtig 1). */
  openDocument: (documentId: string, fileName: string) => Promise<void>
  error: string | null
  clearError: () => void
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
 * #747: the primary action is always the content-endpoint button now (see MessageBubble's
 * `useDocumentPreview()` instance, passed down as the `openDocument` prop) -
 * `sourceEntryUrl`/`sourceUrl` (HTTP_DIRECTORY/RSS_FEED only) are
 * shown alongside it as secondary information, a small "Quelle" link carrying the raw URL, since
 * that address may only be reachable from OPAA's own network, not the caller's browser (#747,
 * Klick-Test finding on the Demo-Instanz).
 *
 * <p>#748 review, nit 5: the URL is carried both as the native `title` tooltip (for a sighted
 * mouse user hovering the link) and as `aria-label="Quelle: <url>"` (for a screen reader, which
 * generally does not announce `title` on an element that already has visible text) - not MUI's
 * `Tooltip`, which would instead replace the whole accessible name with the raw URL. `aria-label`
 * starting with "Quelle" keeps the visible label a substring of the accessible name (WCAG 2.5.3
 * Label in Name), rather than silently dropping the link's own visible text for assistive tech.
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
          aria-label={`Quelle: ${secondaryUrl}`}
          sx={{ fontSize: 11, color: 'text.disabled' }}
        >
          Quelle
        </Link>
      )}
    </>
  )
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
  const indexedAtLabel = formatIndexedAt(doc.source?.indexedAt)
  const mailSummary = formatMailSummary(doc.source)
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
      {/* #1164: the mail Kopfdaten summary ("Mail von …, TT.MM.JJJJ — Betreff"), only for a source
          whose retrieved chunk carried mail_* metadata. */}
      {mailSummary && (
        <Typography
          component="span"
          data-testid="source-mail-summary"
          sx={{ fontSize: 12, color: 'text.secondary' }}
        >
          {mailSummary}
        </Typography>
      )}
      {/* #667: the Fundort per cited passage (mockup 1a: "Abschn. 4.2 ‚Fristsetzung'", "S. 2–4"),
          resolved from the marker's chunk index - only where the pipeline could derive one. */}
      {doc.locations.length > 0 && (
        <Typography
          component="span"
          data-testid="source-location"
          sx={{ fontSize: 12, color: 'text.secondary' }}
        >
          {doc.locations.join(' · ')}
        </Typography>
      )}
      {indexedAtLabel && (
        <Typography component="span" sx={{ fontSize: 12, color: 'text.disabled' }}>
          {indexedAtLabel}
        </Typography>
      )}
      {/* #739/#747: a button that opens through the content endpoint for every sourceType, plus a
          secondary "Quelle" link for a remote sourceEntryUrl/sourceUrl where one exists (see
          renderOpenOriginalLink/canOpenOriginal) - the distinction this comment used to describe
          (button vs. link per sourceType) no longer exists since #747. */}
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
  openDocument,
  error: openOriginalError,
  clearError: clearOpenOriginalError,
}: SourceFootnotesProps) {
  const [uncitedOpen, setUncitedOpen] = useState(false)
  const [foldedOpen, setFoldedOpen] = useState(false)
  // #739/#780: openDocument is MessageBubble's single `useDocumentPreview()` instance (#781
  // review, Wichtig 1/Nit 5) - it already catches its own failure into `error`, so this only has
  // to guard against a synthetic entry with no documentId (see canOpenOriginal) before firing it.
  const handleOpenLocalOriginal = (source: SourceReference, fileName: string) => {
    if (!source.documentId) return
    void openDocument(source.documentId, fileName)
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
        <Alert severity="error" sx={{ mb: 0.75 }} onClose={clearOpenOriginalError}>
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
