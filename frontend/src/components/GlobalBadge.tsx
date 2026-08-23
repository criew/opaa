import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import { fontFamily } from '../theme/tokens'

/**
 * Marks a surface as application-wide (#787, mockups 2b/2c): a quiet mono chip reading
 * "Global". Accent-derived (like the focus ring), so a branding override recolors it with the
 * rest of the app; the scope is carried by the visible text itself, never by color alone
 * (accessibility.md).
 */
export default function GlobalBadge() {
  return (
    <Box
      component="span"
      sx={{
        fontFamily: fontFamily.mono,
        fontSize: 9.5,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        lineHeight: 1,
        color: 'text.primary',
        bgcolor: (t) => alpha(t.palette.primary.main, 0.1),
        border: 1,
        borderColor: (t) => alpha(t.palette.primary.main, 0.4),
        borderRadius: '4px',
        px: '6px',
        py: '3px',
        flex: 'none',
      }}
    >
      Global
    </Box>
  )
}
