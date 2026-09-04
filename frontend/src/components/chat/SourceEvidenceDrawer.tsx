import { useMemo, useState } from 'react'
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
import {
  describeMetadata,
  formatMailSummary,
  formatMetadataLine,
  metadataFilterMatchLabel,
} from './citations'
import type { DocumentSourceType } from '../../types/api'
import type { OpenableDocument } from '../../hooks/useDocumentPreview'
import { fontFamily } from '../../theme/tokens'

interface SourceEvidenceDrawerProps {
  open: boolean
  onClose: () => void
  citations: CitationIndex
  /** When the answer arrived - mockup 1i's footer line. */
  answeredAt: Date
  /** #739/#747/#780: MessageBubble's single `useDocumentPreview()` instance (../../hooks/
   *  useDocumentPreview), shared with SourceFootnotes - the preview dialog/download snackbar it
   *  drives are rendered by MessageBubble as siblings of this Drawer, not inside it, so they
   *  survive the Drawer unmounting its children on close (#781 review, Nit 5; Wichtig 1). */
  openDocument: (document: OpenableDocument) => Promise<void>
}

interface EvidenceDoc {
  fileName: string
  numbers: number[]
  cited: boolean
  /** #386: false when the backend's deterministic check found at least one citation naming this
   *  source that does not match the chunks actually retrieved for this answer. */
  citationValid: boolean
  relevanceScore?: number
  /** #1102: this row's position in the backend's `sources` array - the order the retrieval pipeline
   *  settled on, which the cited rows are sorted by and every row is numbered by.
   *  `Number.MAX_SAFE_INTEGER` when this message's source list does not contain the row's source. */
  sourceIndex: number
  /** #1102: the rank shown in the row, derived from {@link sourceIndex} and never from
   *  `relevanceScore` - a message persisted before #1102 still carries a raw path-dependent score
   *  in its snapshot. Undefined for a synthetic entry (#386), which backs no retrieved passage and
   *  therefore holds no rank; such a row does not consume a rank either, so the numbering stays
   *  gap-free. */
  rank?: number
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
  /** #1164: "Mail von …, TT.MM.JJJJ — Betreff", undefined for a non-mail source. */
  mailSummary?: string
  /** #1066: the schema metadata line, undefined when the document carries no value. */
  metadataLine?: string
  /** #1066: "Label: Wert, …" - the accessible name of {@link metadataLine}. */
  metadataDescription?: string
  /** #1070: "ohne Angabe" for a hit the Leerwert rule kept under an active filter. */
  filterMatchLabel?: string
}

function formatAnsweredAt(answeredAt: Date): string {
  return `${answeredAt.toLocaleDateString('de-DE', { dateStyle: 'medium' })}, ${answeredAt.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`
}

/**
 * Mockup 1i's Belegfenster (#592): the side panel with every source of one answer - searchable,
 * filterable to cited ones, cited documents first in citation-number order, then the checked but
 * uncited ones in the order the retrieval pipeline selected the chunks (#1102, #1238). The
 * per-passage verbatim quotes and locations the mockup shows wait on the backend's chunk metadata
 * (#667); until then each document row carries what the API can already vouch for.
 */
export default function SourceEvidenceDrawer({
  open,
  onClose,
  citations,
  answeredAt,
  openDocument,
}: SourceEvidenceDrawerProps) {
  const [query, setQuery] = useState('')
  const [citedOnly, setCitedOnly] = useState(false)

  // #739/#747: a document with a documentId opens through the Bearer-authenticated content
  // endpoint (a plain <a href> cannot carry the token, ADR-0005), which proxies
  // HTTP_DIRECTORY/RSS_FEED server-side; a CONFLUENCE document opens at its own source URL
  // instead (useDocumentPreview, ADR-0023 - the endpoint deliberately has no original there).
  // sourceEntryUrl/sourceUrl stay visible as secondary information alongside the button.
  async function handleOpenLocalOriginal(doc: EvidenceDoc) {
    if (!doc.documentId) return
    await openDocument({
      id: doc.documentId,
      fileName: doc.fileName,
      sourceType: doc.sourceType,
      sourceUrl: doc.sourceUrl,
      sourceEntryUrl: doc.sourceEntryUrl,
    })
  }

  const docs = useMemo((): EvidenceDoc[] => {
    const cited: EvidenceDoc[] = citations.docs.map((doc) => ({
      fileName: doc.fileName,
      numbers: doc.numbers,
      cited: true,
      citationValid: doc.source?.citationValid !== false,
      relevanceScore: doc.source?.relevanceScore,
      sourceIndex: doc.sourceIndex,
      indexedAt: doc.source?.indexedAt,
      sourceEntryUrl: doc.source?.sourceEntryUrl,
      documentId: doc.source?.documentId,
      sourceType: doc.source?.sourceType,
      sourceUrl: doc.source?.sourceUrl,
      mailSummary: formatMailSummary(doc.source),
      metadataLine: formatMetadataLine(doc.source),
      metadataDescription: describeMetadata(doc.source),
      filterMatchLabel: metadataFilterMatchLabel(doc.source),
    }))
    const uncited: EvidenceDoc[] = citations.uncited.map((source) => ({
      fileName: source.fileName,
      numbers: [],
      cited: false,
      citationValid: source.citationValid !== false,
      relevanceScore: source.relevanceScore,
      sourceIndex: citations.sourceIndexByReference.get(source) ?? Number.MAX_SAFE_INTEGER,
      indexedAt: source.indexedAt,
      sourceEntryUrl: source.sourceEntryUrl,
      documentId: source.documentId,
      sourceType: source.sourceType,
      sourceUrl: source.sourceUrl,
      mailSummary: formatMailSummary(source),
      metadataLine: formatMetadataLine(source),
      metadataDescription: describeMetadata(source),
      filterMatchLabel: metadataFilterMatchLabel(source),
    }))
    // #1102: never order by relevanceScore - a persisted message's snapshot may still carry the
    // pre-#1102 path-dependent raw score, and sorting by that would drop a lexical-only source to
    // the bottom.
    //
    // #1238: the cited rows stay in `cited`'s existing order (`citations.docs`), which is
    // ascending by citation number - a doc's row is created on its first marker, so the doc list
    // is already sorted by first-appearance number; a doc cited but never referenced in the text
    // (empty `numbers`) is appended afterwards in `sources` order, i.e. by pipeline position. Only
    // the uncited group is explicitly ordered by pipeline position, since `citations.uncited`
    // already arrives in that order.
    const byPipelineOrder = (a: EvidenceDoc, b: EvidenceDoc) => a.sourceIndex - b.sourceIndex
    const rows = [...cited, ...uncited]
    // The rank is the row's position in `sources`, never `1 / relevanceScore` and never its
    // position in `rows`: the score in a message persisted before #1102 is still a raw,
    // path-dependent one that would label a lexical-only source "Rang 11", while `rows` groups the
    // cited rows before the uncited ones and would renumber as soon as an uncited source sits
    // between two cited ones. A synthetic entry (#386) backs no retrieved passage, holds no rank
    // and consumes none. Numbering happens before `visibleDocs` filters, so it stays stable under
    // search and "nur zitierte".
    const rankByDoc = new Map<EvidenceDoc, number>()
    let nextRank = 1
    for (const doc of [...rows].sort(byPipelineOrder)) {
      if (doc.relevanceScore !== undefined && doc.relevanceScore !== 0) {
        rankByDoc.set(doc, nextRank++)
      }
    }
    return rows.map((doc) => ({ ...doc, rank: rankByDoc.get(doc) }))
  }, [citations])

  const visibleDocs = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return docs
      .filter((doc) => !citedOnly || doc.cited)
      .filter(
        (doc) =>
          needle.length === 0 ||
          doc.fileName.toLowerCase().includes(needle) ||
          (doc.metadataLine?.toLowerCase().includes(needle) ?? false),
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
            {stellen} in {dokumente} · zitierte nach Zitatnummer, übrige nach Relevanzrang sortiert
          </Typography>
        </Box>
        <IconButton size="small" onClick={onClose} aria-label="Belegfenster schließen">
          <CloseIcon sx={{ fontSize: 18 }} />
        </IconButton>
      </Box>

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
              // #739: two distinct documents may share a file name and each get their own row,
              // so the file name alone is not a unique key.
              key={doc.documentId ?? doc.fileName}
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
              {/* #1066: the schema metadata line, rendered from the generic list without field
                  knowledge - only for a document that actually carries a value. */}
              {doc.metadataLine && (
                <Typography
                  component="span"
                  data-testid="source-metadata"
                  aria-label={doc.metadataDescription}
                  sx={{ display: 'block', fontSize: 11.5, color: 'text.secondary', mt: 0.25 }}
                >
                  {doc.metadataLine}
                </Typography>
              )}
              {/* #1070: the Leerwert mark of a hit kept under an active filter. */}
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
              {/* #1164: the mail Kopfdaten summary ("Mail von …, TT.MM.JJJJ — Betreff"), only for a
                  source whose retrieved chunk carried mail_* metadata. */}
              {doc.mailSummary && (
                <Typography
                  component="span"
                  data-testid="source-mail-summary"
                  sx={{ display: 'block', fontSize: 11.5, color: 'text.secondary', mt: 0.25 }}
                >
                  {doc.mailSummary}
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
                <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                  {[
                    doc.rank !== undefined ? `Rang ${doc.rank}` : null,
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
