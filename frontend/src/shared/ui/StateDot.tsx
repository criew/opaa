import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { StatusTone } from './StatusLine'

const TONE_COLOR: Record<StatusTone, string> = {
  neutral: 'text.disabled',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
}

/**
 * A state inside a table cell or list entry: a dot before its word, the dot on the first line
 * when the word wraps. The dot is decorative - the word carries the whole statement.
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
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'flex-start', gap: 0.75 }}>
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
      <Typography component="span" sx={{ fontSize: 13, fontWeight: strong ? 600 : 500 }}>
        {label}
      </Typography>
    </Box>
  )
}
