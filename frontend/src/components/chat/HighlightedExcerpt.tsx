import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import type { ChatSearchHighlight } from '../../types/api'

interface HighlightedExcerptProps {
  text: string
  highlights: ChatSearchHighlight[]
}

interface Segment {
  text: string
  marked: boolean
}

/**
 * Splits `text` at the highlight ranges (UTF-16 offsets, start inclusive, end exclusive). A range
 * that runs backwards, leaves the text or overlaps its predecessor is skipped.
 */
function segmentsOf(text: string, highlights: ChatSearchHighlight[]): Segment[] {
  const segments: Segment[] = []
  let cursor = 0
  for (const { start, end } of highlights) {
    if (start < cursor || end <= start || end > text.length) continue
    if (start > cursor) segments.push({ text: text.slice(cursor, start), marked: false })
    segments.push({ text: text.slice(start, end), marked: true })
    cursor = end
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor), marked: false })
  return segments
}

/**
 * A plain-text excerpt of a chat search hit with its matched words marked. Always rendered as
 * text nodes, never as HTML; the mark is bold and underlined, so it does not rely on colour alone.
 */
export default function HighlightedExcerpt({ text, highlights }: HighlightedExcerptProps) {
  return (
    <>
      {segmentsOf(text, highlights).map((segment, index) =>
        segment.marked ? (
          <Box
            component="mark"
            key={index}
            sx={(theme) => ({
              fontWeight: 700,
              textDecorationLine: 'underline',
              textUnderlineOffset: '2px',
              bgcolor: alpha(theme.palette.warning.main, 0.25),
              color: 'inherit',
              borderRadius: '2px',
            })}
          >
            {segment.text}
          </Box>
        ) : (
          segment.text
        ),
      )}
    </>
  )
}
