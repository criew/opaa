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
}

const CITATION_RE = new RegExp(CITATION_MARKER_RE.source)

/**
 * Mockup 1a (#590): every citation marker becomes a superscript footnote number linking to its
 * Fundstellen row below the answer. Markers without a resolved number (no citations passed, or
 * an unknown key) are stripped rather than shown raw.
 */
function renderWithCitations(
  text: string,
  citations: CitationIndex | undefined,
  messageId: string | undefined,
): React.ReactNode[] {
  const parts: React.ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  const regex = new RegExp(CITATION_MARKER_RE.source, 'g')
  while ((match = regex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(text.slice(lastIndex, match.index))
    }
    const number = citations?.numberByKey.get(match[1])
    const docIndex = number !== undefined ? citations?.docIndexByNumber.get(number) : undefined
    const fileName = match[2].trim()
    if (number !== undefined) {
      parts.push(
        <Box
          component="sup"
          key={`citation-${match.index}`}
          sx={{ lineHeight: 0, fontSize: 10.5, fontWeight: 600, ml: 0.125 }}
        >
          <Link
            href={
              messageId !== undefined && docIndex !== undefined
                ? `#${citationRowId(messageId, docIndex)}`
                : undefined
            }
            underline="none"
            aria-label={`Fundstelle ${number}: ${fileName}`}
            sx={{ fontWeight: 600 }}
          >
            {number}
          </Link>
        </Box>,
      )
    }
    lastIndex = regex.lastIndex
  }
  if (lastIndex < text.length) {
    parts.push(text.slice(lastIndex))
  }
  return parts
}

function makeProcessChildren(citations: CitationIndex | undefined, messageId: string | undefined) {
  return function processChildren(children: React.ReactNode): React.ReactNode {
    if (typeof children === 'string') {
      if (CITATION_RE.test(children)) {
        return renderWithCitations(children, citations, messageId)
      }
      return children
    }
    if (Array.isArray(children)) {
      return children.map((child, i) => {
        if (typeof child === 'string' && CITATION_RE.test(child)) {
          return <span key={i}>{renderWithCitations(child, citations, messageId)}</span>
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

export default function MarkdownRenderer({ content, citations, messageId }: MarkdownRendererProps) {
  const components = useMemo(
    () => makeComponents(makeProcessChildren(citations, messageId)),
    [citations, messageId],
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
