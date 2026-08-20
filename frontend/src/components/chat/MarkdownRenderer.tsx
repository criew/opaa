import { useMemo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import Typography from '@mui/material/Typography'
import Link from '@mui/material/Link'
import Box from '@mui/material/Box'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import type { Components } from 'react-markdown'
import type { CitationIndex } from './citations'
import { CITATION_MARKER_RE, citationRowId } from './citations'
import 'highlight.js/styles/github-dark.css'

interface MarkdownRendererProps {
  content: string
  /** Footnote resolution for the answer's citation markers (#590); absent markers are stripped. */
  citations?: CitationIndex
  /** Needed for the footnote anchors' target ids; only used together with `citations`. */
  messageId?: string
  /** Fires with every footnote number a clicked anchor covers - a range like "3–4" covers two
   *  rows, which the URL hash alone cannot highlight (#590 Nachbesserung). */
  onCitationClick?: (numbers: number[]) => void
}

const CITATION_RE = new RegExp(CITATION_MARKER_RE.source)

/**
 * Mockup 1a (#590): every citation marker becomes a superscript footnote number linking to its
 * Fundstellen row below the answer. Markers without a resolved number (no citations passed, or
 * an unknown key) are stripped rather than shown raw.
 */
interface ResolvedCitation {
  number: number
  docIndex: number | undefined
  fileName: string
}

/** Adjacent citations render as one superscript group; contiguous number runs compress to a
 *  range ("1–3", mockup 1a/1i) so back-to-back markers stay readable (#590 Nachbesserung). */
function renderCitationGroup(
  group: ResolvedCitation[],
  messageId: string | undefined,
  key: string,
  onCitationClick: ((numbers: number[]) => void) | undefined,
): React.ReactNode {
  const segments: { first: ResolvedCitation; last: ResolvedCitation }[] = []
  for (const citation of group) {
    const current = segments[segments.length - 1]
    if (current && citation.number === current.last.number + 1) {
      current.last = citation
    } else {
      segments.push({ first: citation, last: citation })
    }
  }
  return (
    <Box
      component="sup"
      key={key}
      sx={{ lineHeight: 0, fontSize: 10.5, fontWeight: 600, mx: 0.125 }}
    >
      {segments.map((segment, i) => {
        const label =
          segment.first.number === segment.last.number
            ? `${segment.first.number}`
            : `${segment.first.number}–${segment.last.number}`
        const ariaLabel =
          segment.first.number === segment.last.number
            ? `Fundstelle ${segment.first.number}: ${segment.first.fileName}`
            : `Fundstellen ${segment.first.number} bis ${segment.last.number}`
        const segmentNumbers = Array.from(
          { length: segment.last.number - segment.first.number + 1 },
          (_, offset) => segment.first.number + offset,
        )
        return (
          <span key={segment.first.number}>
            {i > 0 && '·'}
            <Link
              href={
                messageId !== undefined && segment.first.docIndex !== undefined
                  ? `#${citationRowId(messageId, segment.first.docIndex)}`
                  : undefined
              }
              onClick={() => onCitationClick?.(segmentNumbers)}
              underline="none"
              aria-label={ariaLabel}
              sx={{ fontWeight: 600 }}
            >
              {label}
            </Link>
          </span>
        )
      })}
    </Box>
  )
}

function renderWithCitations(
  text: string,
  citations: CitationIndex | undefined,
  messageId: string | undefined,
  onCitationClick: ((numbers: number[]) => void) | undefined,
): React.ReactNode[] {
  const parts: React.ReactNode[] = []
  let lastIndex = 0
  let group: ResolvedCitation[] = []
  let match: RegExpExecArray | null

  const flushGroup = (key: string) => {
    if (group.length > 0) {
      parts.push(renderCitationGroup(group, messageId, key, onCitationClick))
      group = []
    }
  }

  const regex = new RegExp(CITATION_MARKER_RE.source, 'g')
  while ((match = regex.exec(text)) !== null) {
    const between = text.slice(lastIndex, match.index)
    if (between.trim().length > 0 || (group.length === 0 && between.length > 0)) {
      flushGroup(`citation-${match.index}`)
      parts.push(between)
    }
    const number = citations?.numberByKey.get(match[1])
    if (number !== undefined) {
      group.push({
        number,
        docIndex: citations?.docIndexByNumber.get(number),
        fileName: match[2].trim(),
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
  messageId: string | undefined,
  onCitationClick: ((numbers: number[]) => void) | undefined,
) {
  return function processChildren(children: React.ReactNode): React.ReactNode {
    if (typeof children === 'string') {
      if (CITATION_RE.test(children)) {
        return renderWithCitations(children, citations, messageId, onCitationClick)
      }
      return children
    }
    if (Array.isArray(children)) {
      return children.map((child, i) => {
        if (typeof child === 'string' && CITATION_RE.test(child)) {
          return (
            <span key={i}>{renderWithCitations(child, citations, messageId, onCitationClick)}</span>
          )
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
  return {
    h1: ({ children }) => (
      <Typography variant="h5" gutterBottom>
        {children}
      </Typography>
    ),
    h2: ({ children }) => (
      <Typography variant="h6" gutterBottom>
        {children}
      </Typography>
    ),
    h3: ({ children }) => (
      <Typography variant="subtitle1" gutterBottom sx={{ fontWeight: 'bold' }}>
        {children}
      </Typography>
    ),
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
  messageId,
  onCitationClick,
}: MarkdownRendererProps) {
  const components = useMemo(
    () => makeComponents(makeProcessChildren(citations, messageId, onCitationClick)),
    [citations, messageId, onCitationClick],
  )
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeHighlight]}
      components={components}
    >
      {content}
    </ReactMarkdown>
  )
}
