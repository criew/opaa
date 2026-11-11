import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { StatusTone } from './StatusLine'
import { fontSize } from '../theme/tokens'

const TONE_COLOR: Record<StatusTone, string> = {
  neutral: 'text.disabled',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
}

/**
 * A state inside a table cell or list entry: a dot before its word, the dot on the first line
 * when the word wraps. The dot is decorative - the word carries the whole statement. Where the
 * line may not wrap (a table cell), a word wider than its column ends in „…“ and names itself in
 * the tooltip; it is never cut off silently (guidelines 5.3).
 */
export default function StateDot({
  tone,
  label,
  strong = false,
}: {
  tone: StatusTone
  label: string
  strong?: boolean
}) {
  return (
    <Box
      component="span"
      sx={{ display: 'inline-flex', alignItems: 'flex-start', gap: 0.75, maxWidth: '100%' }}
    >
      <Box
        component="span"
        aria-hidden="true"
        sx={{
          width: 8,
          height: 8,
          mt: '6px',
          borderRadius: '50%',
          flex: 'none',
          bgcolor: TONE_COLOR[tone],
        }}
      />
      <Typography
        component="span"
        title={label}
        sx={{
          fontSize: fontSize.text,
          fontWeight: strong ? 600 : 500,
          minWidth: 0,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {label}
      </Typography>
    </Box>
  )
}
