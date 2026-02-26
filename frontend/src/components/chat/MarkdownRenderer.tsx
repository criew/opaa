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
import 'highlight.js/styles/github-dark.css'

interface MarkdownRendererProps {
  content: string
}

const components: Components = {
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
      {children}
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
      {children}
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
  td: ({ children }) => <TableCell>{children}</TableCell>,
}

const SOURCE_CITATION_RE = /\s*\((?:Quelle:\s*|Source:\s*)?([^):/]+\.\w{1,5})\)[.!]?\s*$/

function splitSourceCitation(content: string): { body: string; citation: string | null } {
  const match = content.match(SOURCE_CITATION_RE)
  if (!match) return { body: content, citation: null }
  return { body: content.slice(0, match.index).trimEnd(), citation: match[1] }
}

export default function MarkdownRenderer({ content }: MarkdownRendererProps) {
  const { body, citation } = splitSourceCitation(content)
  return (
    <>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight]}
        components={components}
      >
        {body}
      </ReactMarkdown>
      {citation && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.5 }}>
          Quelle: {citation}
        </Typography>
      )}
    </>
  )
}
