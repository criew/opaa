import { useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Link from '@mui/material/Link'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined'
import SearchIcon from '@mui/icons-material/Search'
import type { CitationIndex } from './citations'
import type { DocumentSourceType } from '../../types/api'
import { fontFamily } from '../../theme/tokens'
import { openDocumentContent } from '../../utils/documentContent'

interface SourceEvidenceDrawerProps {
  open: boolean
  onClose: () => void
  citations: CitationIndex
  /** When the answer arrived - mockup 1i's footer line. */
  answeredAt: Date
}

interface EvidenceDoc {
  fileName: string
  numbers: number[]
  cited: boolean
  /** #386: false when the backend's deterministic check found at least one citation naming this
   *  source that does not match the chunks actually retrieved for this answer. */
  citationValid: boolean
  spaceName?: string | null
  relevanceScore?: number
  indexedAt?: string | null
  sourceEntryUrl?: string | null
  /** #739/#747: the original's document id - openable via GET /documents/{id}/content for every
   *  sourceType (that endpoint proxies HTTP_DIRECTORY/RSS_FEED server-side since #747). Undefined
   *  for a synthetic entry (#386). */
  documentId?: string | null
  sourceType?: DocumentSourceType | null
  /** #739/#747: the remote source URL for sourceType HTTP_DIRECTORY/RSS_FEED, mirroring
   *  LibraryDocumentResponse.sourceUrl (#738) - shown as secondary information alongside the
   *  documentId deep link above, not itself the primary way to open the original any more. */
  sourceUrl?: string | null
}

function formatAnsweredAt(answeredAt: Date): string {
  return `${answeredAt.toLocaleDateString('de-DE', { dateStyle: 'medium' })}, ${answeredAt.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`
}

/**
 * Mockup 1i's Belegfenster (#592): the side panel with every source of one answer - searchable,
 * filterable to cited ones, sorted by weight. The per-passage verbatim quotes and locations the
 * mockup shows wait on the backend's chunk metadata (#667); until then each document row carries
 * what the API can already vouch for.
 */
export default function SourceEvidenceDrawer({
  open,
  onClose,
  citations,
  answeredAt,
}: SourceEvidenceDrawerProps) {
  const [query, setQuery] = useState('')
  const [citedOnly, setCitedOnly] = useState(false)
  // #739: mirrors LibraryDetailPage's openOriginalError (#738) - opening the original is a
  // read-only, per-click action, so its failure (404, file missing) gets its own local message
  // rather than touching any store.
  const [openOriginalError, setOpenOriginalError] = useState<string | null>(null)

  // #739/#747: every sourceType with a documentId opens through the Bearer-authenticated content
  // endpoint (a plain <a href> cannot carry the token, ADR-0005) - since #747 that endpoint proxies
  // HTTP_DIRECTORY/RSS_FEED server-side from their own stored source URL instead of answering 404,
  // so this no longer branches on sourceType at all; sourceEntryUrl/sourceUrl are shown as
  // secondary information alongside the button instead (see the render code below).
  async function handleOpenLocalOriginal(doc: EvidenceDoc) {
    setOpenOriginalError(null)
    if (!doc.documentId) return
    try {
      await openDocumentContent(doc.documentId, doc.fileName)
    } catch (err) {
      setOpenOriginalError(
        err instanceof Error ? err.message : 'Das Original konnte nicht geöffnet werden.',
      )
    }
  }

  const docs = useMemo((): EvidenceDoc[] => {
    const cited: EvidenceDoc[] = citations.docs.map((doc) => ({
      fileName: doc.fileName,
      numbers: doc.numbers,
      cited: true,
      citationValid: doc.source?.citationValid !== false,
      spaceName: doc.source?.spaceName,
      relevanceScore: doc.source?.relevanceScore,
      indexedAt: doc.source?.indexedAt,
      sourceEntryUrl: doc.source?.sourceEntryUrl,
      documentId: doc.source?.documentId,
      sourceType: doc.source?.sourceType,
      sourceUrl: doc.source?.sourceUrl,
    }))
    const uncited: EvidenceDoc[] = citations.uncited.map((source) => ({
      fileName: source.fileName,
      numbers: [],
      cited: false,
      citationValid: source.citationValid !== false,
      spaceName: source.spaceName,
      relevanceScore: source.relevanceScore,
      indexedAt: source.indexedAt,
      sourceEntryUrl: source.sourceEntryUrl,
      documentId: source.documentId,
      sourceType: source.sourceType,
      sourceUrl: source.sourceUrl,
    }))
    // Mockup 1i: "nach Gewicht sortiert" - by relevance within each group, cited before checked.
    const byWeight = (a: EvidenceDoc, b: EvidenceDoc) =>
      (b.relevanceScore ?? 0) - (a.relevanceScore ?? 0)
    return [...cited.sort(byWeight), ...uncited.sort(byWeight)]
  }, [citations])

  const visibleDocs = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return docs
      .filter((doc) => !citedOnly || doc.cited)
      .filter(
        (doc) =>
          needle.length === 0 ||
          doc.fileName.toLowerCase().includes(needle) ||
          (doc.spaceName ?? '').toLowerCase().includes(needle),
      )
  }, [citedOnly, docs, query])

  const stellen = citations.markerCount === 1 ? '1 Stelle' : `${citations.markerCount} Stellen`
  const dokumente =
    citations.docs.length === 1 ? '1 Dokument' : `${citations.docs.length} Dokumenten`

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
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography sx={{ fontSize: 16, fontWeight: 600 }}>Belege dieser Antwort</Typography>
          <Typography sx={{ fontSize: 11, color: 'text.secondary', mt: 0.25 }}>
            {stellen} in {dokumente} · nach Gewicht sortiert
          </Typography>
        </Box>
        <IconButton size="small" onClick={onClose} aria-label="Belegfenster schließen">
          <CloseIcon sx={{ fontSize: 18 }} />
        </IconButton>
      </Box>

      {openOriginalError && (
        <Alert
          severity="error"
          sx={{ mx: 2.5, mt: 1.5 }}
          onClose={() => setOpenOriginalError(null)}
        >
          {openOriginalError}
        </Alert>
      )}

      <Box sx={{ display: 'flex', gap: 1, px: 2.5, py: 1.5 }}>
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
        <Button
          variant={citedOnly ? 'contained' : 'outlined'}
          size="small"
          onClick={() => setCitedOnly((v) => !v)}
          aria-pressed={citedOnly}
          sx={{ flex: 'none' }}
        >
          Nur zitierte
        </Button>
      </Box>

      <Box sx={{ flex: 1, overflowY: 'auto', px: 2.5, pb: 2 }}>
        {visibleDocs.length === 0 ? (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', py: 2 }}>
            Kein Beleg passt zur Suche.
          </Typography>
        ) : (
          visibleDocs.map((doc) => (
            <Box
              key={doc.fileName}
              data-testid="evidence-doc"
              data-file={doc.fileName}
              data-cited={doc.cited ? 'true' : 'false'}
              data-citation-valid={doc.citationValid ? 'true' : 'false'}
              sx={{
                py: 1.25,
                borderBottom: 1,
                borderColor: 'divider',
                opacity: doc.cited ? 1 : 0.65,
              }}
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
                <Typography component="span" noWrap sx={{ fontSize: 13, fontWeight: 500 }}>
                  {doc.fileName}
                </Typography>
                {!doc.cited && (
                  <Typography component="span" sx={{ fontSize: 10.5, color: 'text.secondary' }}>
                    geprüft, nicht zitiert
                  </Typography>
                )}
                {!doc.citationValid && (
                  // #697 review, Befund 2: reiner Text in warning.main unterschreitet auf heller
                  // Fläche 4,5:1 (docs/design/accessibility.md 2.4, rund 1,8:1 gemessen). Die Farbe
                  // trägt hier ohnehin nicht allein die Bedeutung (2.4, letzter Punkt) - das Icon in
                  // error.main erfüllt die UI-Komponentenschwelle von 3:1 in beiden Schemata, der Text
                  // selbst läuft in text.secondary (kontraststark, siehe "geprüft, nicht zitiert" oben).
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
              <Box
                sx={{
                  display: 'flex',
                  alignItems: 'baseline',
                  gap: 1,
                  flexWrap: 'wrap',
                  mt: 0.25,
                }}
              >
                <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                  {[
                    doc.spaceName,
                    doc.relevanceScore !== undefined
                      ? `Gewicht ${Math.round(doc.relevanceScore * 100)} %`
                      : null,
                    doc.indexedAt
                      ? `indiziert ${new Date(doc.indexedAt).toLocaleDateString('de-DE', { dateStyle: 'medium' })}`
                      : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </Typography>
                {/* #747: every sourceType with a documentId opens via the content endpoint
                    (LibraryDetailPage#handleOpenOriginal, #738); sourceEntryUrl/sourceUrl is
                    shown alongside it as secondary information (a small "Quelle" link carrying
                    the raw URL as its title tooltip and aria-label - #748 review, nit 5: a plain
                    title alone is generally not announced by a screen reader once the element
                    already has visible text), since that address may only be reachable from
                    OPAA's own network, not the caller's browser. */}
                {doc.documentId && (
                  <Link
                    component="button"
                    type="button"
                    underline="hover"
                    onClick={() => void handleOpenLocalOriginal(doc)}
                    sx={{ fontSize: 11.5 }}
                  >
                    Im Dokument öffnen
                  </Link>
                )}
                {(doc.sourceEntryUrl ?? doc.sourceUrl) && (
                  <Link
                    href={doc.sourceEntryUrl ?? doc.sourceUrl ?? undefined}
                    target="_blank"
                    rel="noopener noreferrer"
                    underline="hover"
                    title={doc.sourceEntryUrl ?? doc.sourceUrl ?? undefined}
                    aria-label={`Quelle: ${doc.sourceEntryUrl ?? doc.sourceUrl ?? ''}`}
                    sx={{ fontSize: 11.5, color: 'text.disabled' }}
                  >
                    Quelle
                  </Link>
                )}
              </Box>
            </Box>
          ))
        )}
      </Box>

      <Box sx={{ px: 2.5, py: 1.5, borderTop: 1, borderColor: 'divider' }}>
        <Typography sx={{ fontSize: 11, color: 'text.secondary' }}>
          Stand der Antwort: {formatAnsweredAt(answeredAt)}
        </Typography>
      </Box>
    </Drawer>
  )
}
