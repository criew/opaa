import Typography from '@mui/material/Typography'
import { blue } from '../theme/tokens'

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
        color: accent ? blue[700] : 'text.secondary',
        border: 1,
        borderColor: accent ? blue[300] : 'divider',
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
