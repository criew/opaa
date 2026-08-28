import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'

interface MetaBadgeProps {
  children: string
  accent?: boolean
}

/**
 * The small bordered meta label from mockup 1d's library table (kind, distribution level, role),
 * shared across the migrated pages so badges keep one voice.
 */
export default function MetaBadge({ children, accent = false }: MetaBadgeProps) {
  return (
    <Typography
      component="span"
      sx={{
        fontSize: 10.5,
        // #957: accent via primary.main, not hardcoded blue[700] - the token splits per scheme
        // (#634: light blue-700, dark blue-500); blue[700] only reached 3.8:1 on the dark
        // surfaces. Border derives from the same colour (GlobalBadge pattern, 40%).
        color: accent ? 'primary.main' : 'text.secondary',
        border: 1,
        borderColor: accent ? (t) => alpha(t.palette.primary.main, 0.4) : 'divider',
        borderRadius: '4px',
        px: 1,
        py: 0.25,
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </Typography>
  )
}
