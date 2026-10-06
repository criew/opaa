import { useMemo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import Typography from '@mui/material/Typography'
import Link from '@mui/material/Link'
import ButtonBase from '@mui/material/ButtonBase'
import Tooltip from '@mui/material/Tooltip'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import type { Components } from 'react-markdown'
import rehypeNormalizeHeadings, { MD_LEVEL_PROPERTY } from './markdownHeadings'
import type { CitationIndex } from './citations'
import { CLAIMED_MARKER_RE, readMarker } from './citations'
import { citationMarkColors, citationMarkSx } from './citationMark'
import { focusRingAlpha, fontFamily, motion } from '../../theme/tokens'
import 'highlight.js/styles/github-dark.css'

interface MarkdownRendererProps {
  content: string
  /** Footnote resolution for the answer's citation markers (#590); absent markers are stripped
   *  unless {@link preserveCitationMarkers} is set. */
  citations?: CitationIndex
  /** Fires with every footnote number a clicked marker covers - a range like "3–4" covers two
   *  Belege, all of which the Belegfenster marks. */
  onCitationClick?: (numbers: number[]) => void
  /** #780/#781 review, Nit 6: `content` here is a chat answer the backend generated with its own
   *  `【source: …】` marker syntax baked in, which the default behaviour above resolves into
   *  footnotes (or silently strips when no `citations` index matches). A document's own original
   *  content is neither - a coincidental `【…】` run in the source text is part of what was
   *  indexed, not a citation marker, so silently mutating it in DocumentTextPreviewDialog would be
   *  wrong. Set to render `content` completely unprocessed by the citation-marker logic. */
  preserveCitationMarkers?: boolean
}

const CITATION_RE = new RegExp(CLAIMED_MARKER_RE.source)

/**
 * Every citation marker becomes a footnote mark - the same tinted circle EvidenceFooter shows.
 * Hovering names the cited document, clicking opens the Belegfenster at the Belege it covers.
 * Markers without a resolved number (no citations passed, or an unknown key) are stripped rather
 * than shown raw.
 */
interface ResolvedCitation {
  number: number
  fileName: string
  location: string | undefined
}

/** Diameter of a footnote mark in running text, in px - small enough to keep the line height. */
const INLINE_MARK_SIZE = 15
const INLINE_MARK_FONT_SIZE = 9

function CitationTooltipContent({ citations }: { citations: ResolvedCitation[] }) {
  return (
    <Box component="span" sx={{ display: 'grid', gap: 0.5, py: 0.25 }}>
      {citations.map((citation) => (
        <Box
          component="span"
          key={citation.number}
          sx={{ display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 0.75 }}
        >
          <Box component="span" sx={{ fontFamily: fontFamily.mono, fontWeight: 600, opacity: 0.7 }}>
            {citation.number}
          </Box>
          <Box component="span" sx={{ fontWeight: 600, overflowWrap: 'anywhere' }}>
            {citation.fileName}
          </Box>
          {citation.location && (
            <Box component="span" sx={{ gridColumn: 2, opacity: 0.75 }}>
              {citation.location}
            </Box>
          )}
        </Box>
      ))}
    </Box>
  )
}

/** Adjacent citations render as one group; contiguous number runs compress to a range ("1–3",
 *  mockup 1a/1i) so back-to-back markers stay readable (#590 Nachbesserung). */
function renderCitationGroup(
  group: ResolvedCitation[],
  key: string,
  onCitationClick: ((numbers: number[]) => void) | undefined,
): React.ReactNode {
  // A number repeated anywhere in the group shows once, where it first appears.
  const seen = new Set<number>()
  const segments: ResolvedCitation[][] = []
  for (const citation of group) {
    if (seen.has(citation.number)) continue
    seen.add(citation.number)
    const current = segments[segments.length - 1]
    if (current && citation.number === current[current.length - 1].number + 1) {
      current.push(citation)
    } else {
      segments.push([citation])
    }
  }
  return (
    <Box
      component="span"
      key={key}
      sx={{ display: 'inline-flex', gap: '3px', ml: '3px', whiteSpace: 'nowrap' }}
    >
      {segments.map((segment) => {
        const first = segment[0]
        const last = segment[segment.length - 1]
        const isRange = first.number !== last.number
        return (
          <Tooltip
            key={first.number}
            title={<CitationTooltipContent citations={segment} />}
            describeChild
            placement="top"
            enterDelay={150}
          >
            <ButtonBase
              aria-haspopup="dialog"
              aria-label={
                isRange
                  ? `Fundstellen ${first.number} bis ${last.number}`
                  : `Fundstelle ${first.number}: ${first.fileName}`
              }
              onClick={() => onCitationClick?.(segment.map((c) => c.number))}
              sx={(theme) => ({
                ...citationMarkSx(theme, INLINE_MARK_SIZE, INLINE_MARK_FONT_SIZE),
                px: '4px',
                // Superscript: the circle sits above the x-height. Shifted with a relative offset
                // rather than vertical-align, so the line box - and the line spacing - stay put.
                verticalAlign: 'baseline',
                position: 'relative',
                top: '-0.6em',
                transition: `background ${motion.durationFastMs}ms ${motion.easeOut}, color ${motion.durationFastMs}ms ${motion.easeOut}`,
                '&:hover, &[aria-describedby]': {
                  background: citationMarkColors(theme).filledSurface,
                  color: citationMarkColors(theme).filledText,
                },
                '&.Mui-focusVisible': {
                  boxShadow: `0 0 0 3px ${alpha(theme.palette.primary.main, focusRingAlpha)}`,
                },
                '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
              })}
            >
              {isRange ? `${first.number}–${last.number}` : first.number}
            </ButtonBase>
          </Tooltip>
        )
      })}
    </Box>
  )
}

function renderWithCitations(
  text: string,
  citations: CitationIndex | undefined,
  onCitationClick: ((numbers: number[]) => void) | undefined,
): React.ReactNode[] {
  const parts: React.ReactNode[] = []
  let lastIndex = 0
  let group: ResolvedCitation[] = []
  let match: RegExpExecArray | null

  const flushGroup = (key: string) => {
    if (group.length > 0) {
      parts.push(renderCitationGroup(group, key, onCitationClick))
      group = []
    }
  }

  const regex = new RegExp(CLAIMED_MARKER_RE.source, 'g')
  while ((match = regex.exec(text)) !== null) {
    // A mark attaches to the word before it, like a footnote digit: the model's space in
    // "Euro 【…】" would otherwise add to the mark's own margin.
    const between = text.slice(lastIndex, match.index).trimEnd()
    if (between.length > 0) {
      flushGroup(`citation-${match.index}`)
      parts.push(between)
    }
    const marker = readMarker(match[0])
    const number = marker && citations?.numberByKey.get(marker.key)
    if (marker && number !== undefined) {
      group.push({
        number,
        fileName: marker.fileName.trim(),
        location: citations?.locationByNumber.get(number),
      })
    }
    lastIndex = regex.lastIndex
  }
  flushGroup('citation-tail')
  if (lastIndex < text.length) {
    parts.push(text.slice(lastIndex))
  }
  return parts
}

function makeProcessChildren(
  citations: CitationIndex | undefined,
  onCitationClick: ((numbers: number[]) => void) | undefined,
  preserveCitationMarkers: boolean,
) {
  return function processChildren(children: React.ReactNode): React.ReactNode {
    // #780/#781 review, Nit 6: a document preview's own content is not a chat answer - a
    // coincidental `【…】` run is part of what was indexed, not a citation marker to resolve or
    // strip.
    if (preserveCitationMarkers) {
      return children
    }
    if (typeof children === 'string') {
      if (CITATION_RE.test(children)) {
        return renderWithCitations(children, citations, onCitationClick)
      }
      return children
    }
    if (Array.isArray(children)) {
      return children.map((child, i) => {
        if (typeof child === 'string' && CITATION_RE.test(child)) {
          return <span key={i}>{renderWithCitations(child, citations, onCitationClick)}</span>
        }
        return child
      })
    }
    return children
  }
}

function makeComponents(
  processChildren: (children: React.ReactNode) => React.ReactNode,
): Components {
  // #1016: the element level comes from rehypeNormalizeHeadings (per-message rank compression
  // starting at h2); the visual variant keeps following the ORIGINAL Markdown level, carried in
  // MD_LEVEL_PROPERTY - "## Zusammenfassung" looks exactly as before, whatever element it gets.
  const VARIANT_BY_MD_LEVEL: Record<string, React.ComponentProps<typeof Typography>['variant']> = {
    '1': 'h5',
    '2': 'h6',
    '3': 'subtitle1',
  }
  const heading =
    (tag: 'h2' | 'h3' | 'h4' | 'h5' | 'h6'): Components[typeof tag] =>
    ({ children, node }) => {
      const mdLevel = String(node?.properties?.[MD_LEVEL_PROPERTY] ?? '')
      const variant = VARIANT_BY_MD_LEVEL[mdLevel] ?? 'subtitle2'
      const bold = mdLevel === '' || Number(mdLevel) >= 3
      return (
        <Typography
          component={tag}
          variant={variant}
          gutterBottom
          sx={bold ? { fontWeight: 'bold' } : undefined}
        >
          {children}
        </Typography>
      )
    }
  return {
    h2: heading('h2'),
    h3: heading('h3'),
    h4: heading('h4'),
    h5: heading('h5'),
    h6: heading('h6'),
    p: ({ children }) => (
      <Typography variant="body1" sx={{ mb: 1, '&:last-child': { mb: 0 } }}>
        {processChildren(children)}
      </Typography>
    ),
    a: ({ href, children }) => (
      <Link href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </Link>
    ),
    code: ({ className, children }) => {
      const isBlock = className?.includes('language-') || className?.includes('hljs')
      if (isBlock) {
        return <code className={className}>{children}</code>
      }
      return (
        <Box
          component="code"
          sx={{
            bgcolor: 'action.hover',
            px: 0.5,
            py: 0.25,
            borderRadius: 0.5,
            fontSize: '0.875em',
            fontFamily: 'monospace',
          }}
        >
          {children}
        </Box>
      )
    },
    pre: ({ children }) => (
      <Box
        component="pre"
        sx={{
          bgcolor: '#0d1117',
          color: '#e6edf3',
          p: 2,
          borderRadius: 1,
          overflowX: 'auto',
          my: 1,
          fontSize: '0.875rem',
          '& code': {
            bgcolor: 'transparent',
            p: 0,
          },
        }}
      >
        {children}
      </Box>
    ),
    ul: ({ children }) => (
      <Box component="ul" sx={{ pl: 2, my: 1 }}>
        {children}
      </Box>
    ),
    ol: ({ children }) => (
      <Box component="ol" sx={{ pl: 2, my: 1 }}>
        {children}
      </Box>
    ),
    li: ({ children }) => (
      <Typography component="li" variant="body1">
        {processChildren(children)}
      </Typography>
    ),
    table: ({ children }) => (
      <Table size="small" sx={{ my: 1 }}>
        {children}
      </Table>
    ),
    thead: ({ children }) => <TableHead>{children}</TableHead>,
    tbody: ({ children }) => <TableBody>{children}</TableBody>,
    tr: ({ children }) => <TableRow>{children}</TableRow>,
    th: ({ children }) => <TableCell sx={{ fontWeight: 'bold' }}>{children}</TableCell>,
    td: ({ children }) => <TableCell>{processChildren(children)}</TableCell>,
  }
}

export default function MarkdownRenderer({
  content,
  citations,
  onCitationClick,
  preserveCitationMarkers = false,
}: MarkdownRendererProps) {
  const components = useMemo(
    () => makeComponents(makeProcessChildren(citations, onCitationClick, preserveCitationMarkers)),
    [citations, onCitationClick, preserveCitationMarkers],
  )
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeHighlight, rehypeNormalizeHeadings]}
      components={components}
    >
      {content}
    </ReactMarkdown>
  )
}
