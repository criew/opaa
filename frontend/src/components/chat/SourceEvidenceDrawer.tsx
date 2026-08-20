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
import SearchIcon from '@mui/icons-material/Search'
import type { CitationIndex } from './citations'
import { fontFamily } from '../../theme/tokens'

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
  spaceName?: string | null
  relevanceScore?: number
  indexedAt?: string | null
  sourceEntryUrl?: string | null
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

  const docs = useMemo((): EvidenceDoc[] => {
    const cited: EvidenceDoc[] = citations.docs.map((doc) => ({
      fileName: doc.fileName,
      numbers: doc.numbers,
      cited: true,
      spaceName: doc.source?.spaceName,
      relevanceScore: doc.source?.relevanceScore,
      indexedAt: doc.source?.indexedAt,
      sourceEntryUrl: doc.source?.sourceEntryUrl,
    }))
    const uncited: EvidenceDoc[] = citations.uncited.map((source) => ({
      fileName: source.fileName,
      numbers: [],
      cited: false,
      spaceName: source.spaceName,
      relevanceScore: source.relevanceScore,
      indexedAt: source.indexedAt,
      sourceEntryUrl: source.sourceEntryUrl,
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
                {doc.sourceEntryUrl && (
                  <Link
                    href={doc.sourceEntryUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    underline="hover"
                    sx={{ fontSize: 11.5 }}
                  >
                    Im Dokument öffnen
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
