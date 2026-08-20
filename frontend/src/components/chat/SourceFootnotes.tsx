import { useState } from 'react'
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
export default function SourceFootnotes({ messageId, citations }: SourceFootnotesProps) {
  const [uncitedOpen, setUncitedOpen] = useState(false)
  const { docs, uncited, markerCount } = citations

  if (docs.length === 0 && uncited.length === 0) {
    return null
  }

  const stellen = markerCount === 1 ? '1 Stelle' : `${markerCount} Stellen`
  const dokumente = docs.length === 1 ? '1 Dokument' : `${docs.length} Dokumenten`

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
        {markerCount > 0 && (
          <Typography component="span" sx={{ fontSize: 11, color: 'text.disabled' }}>
            {stellen} in {dokumente}
          </Typography>
        )}
      </Box>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        {docs.map((doc, docIndex) => (
          <Box
            key={doc.fileName}
            id={citationRowId(messageId, docIndex)}
            data-testid="source-card"
            data-cited="true"
            sx={{
              display: 'flex',
              alignItems: 'baseline',
              gap: 1,
              flexWrap: 'wrap',
              fontSize: 12,
              lineHeight: 1.5,
              borderRadius: '4px',
              // The in-text footnote anchors land here - :target lights the row up.
              '&:target': {
                bgcolor: (theme) => alpha(theme.palette.primary.main, 0.1),
              },
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
        ))}
      </Box>

      {uncited.length > 0 && (
        <>
          <Link
            component="button"
            type="button"
            underline="hover"
            onClick={() => setUncitedOpen((open) => !open)}
            sx={{ mt: 0.75, fontSize: 11, color: 'text.disabled', display: 'inline-block' }}
          >
            Weitere geprüfte, nicht zitierte Treffer ({uncited.length}){' '}
            {uncitedOpen ? 'ausblenden' : 'anzeigen'}
          </Link>
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
