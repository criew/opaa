import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

export type StatusTone = 'neutral' | 'success' | 'warning' | 'error'

const DOT_COLOR: Record<StatusTone, string> = {
  neutral: 'text.disabled',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
}

interface StatusLineProps {
  /** The sentence a reader takes away - what the state is, not what the field is called. */
  headline: string
  /** The reason or the next step, where there is one. */
  detail?: ReactNode
  tone?: StatusTone
  /** `h2` or `h3` where the line opens a section; a plain line by default. */
  component?: 'h2' | 'h3' | 'p'
  /**
   * Names the line as a landmark, where it stands for a state a reader should be able to jump to
   * („Versandstatus"). Without this name the line is just text - the frame that used to make it
   * a named area is gone, the semantics must not be.
   */
  label?: string
  /** Details under the line - typically a KeyValueList. */
  children?: ReactNode
}

/**
 * A state as a line: coloured dot, sentence, below it the reason — **no status card**.
 *
 * The colour is in the dot, never in the text (design guidelines 2.3). The dot is decorative and
 * hidden from screen readers — the statement is complete in the word.
 */
export default function StatusLine({
  headline,
  detail,
  tone = 'neutral',
  component = 'p',
  label,
  children,
}: StatusLineProps) {
  return (
    <Box
      component={label ? 'section' : 'div'}
      aria-label={label}
      sx={{ mb: children || detail ? 2 : 0 }}
    >
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
        <Box
          aria-hidden
          sx={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            flex: 'none',
            bgcolor: DOT_COLOR[tone],
            transform: 'translateY(-1px)',
          }}
        />
        <Typography component={component} sx={{ fontSize: 14, fontWeight: 600 }}>
          {headline}
        </Typography>
      </Box>
      {detail && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5, pl: 2 }}>
          {detail}
        </Typography>
      )}
      {children && <Box sx={{ mt: 1.25, pl: 2 }}>{children}</Box>}
    </Box>
  )
}
