import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Collapse from '@mui/material/Collapse'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import type { CitationIndex } from './citations'
import { citationRowId } from './citations'
import { fontFamily } from '../../theme/tokens'

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
      {/* #666/#639: sourceEntryUrl carries an RSS-entry attachment's origin - the mockup's
          "Im Dokument öffnen" affordance, pointing at the entry itself (#493). */}
      {doc.source?.sourceEntryUrl && (
        <Link
          href={doc.source.sourceEntryUrl}
          target="_blank"
          rel="noopener noreferrer"
          underline="hover"
          sx={{ fontSize: 11 }}
        >
          Im Dokument öffnen
        </Link>
      )}
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

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        {visibleDocs.map((doc, docIndex) =>
          renderDocRow(doc, docIndex, messageId, highlightedDocIndexes.includes(docIndex)),
        )}
        {foldedOpen &&
          foldedDocs.map((doc, i) => {
            const docIndex = i + VISIBLE_DOCS
            return renderDocRow(doc, docIndex, messageId, highlightedDocIndexes.includes(docIndex))
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
                  {source.sourceEntryUrl && (
                    <Link
                      href={source.sourceEntryUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      underline="hover"
                      sx={{ fontSize: 11 }}
                    >
                      Im Dokument öffnen
                    </Link>
                  )}
                </Box>
              ))}
            </Box>
          </Collapse>
        </>
      )}
    </Box>
  )
}
