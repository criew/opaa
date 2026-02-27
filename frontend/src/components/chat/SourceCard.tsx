import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Paper from '@mui/material/Paper'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { SourceReference } from '../../types/api'
import type { AccessLevel } from '../../types/chat'
import { deriveAccessLevel } from '../../utils/accessLevel'

const accessLevelColors: Record<AccessLevel, 'error' | 'warning' | 'success'> = {
  Confidential: 'error',
  Internal: 'warning',
  Public: 'success',
}

interface SourceCardProps {
  source: SourceReference
}

function formatIndexedAt(indexedAt: string | null): string {
  if (!indexedAt) return '-'
  const d = new Date(indexedAt)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export default function SourceCard({ source }: SourceCardProps) {
  const accessLevel = deriveAccessLevel(source.fileName)
  const relevancePercent = Math.round(source.relevanceScore * 100)

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 1.5,
        bgcolor: 'background.default',
        width: 220,
        minWidth: 220,
        opacity: source.cited ? 1 : 0.6,
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
        <Tooltip title={source.fileName}>
          <Typography variant="body2" fontWeight={600} noWrap sx={{ flexGrow: 1 }}>
            {source.fileName}
          </Typography>
        </Tooltip>
        <Tooltip title="Access levels coming in a future release">
          <Chip
            label={accessLevel}
            size="small"
            color={accessLevelColors[accessLevel]}
            sx={{ height: 20, fontSize: '0.7rem' }}
          />
        </Tooltip>
      </Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mt: 0.5 }}>
        <Typography variant="caption" color="text.secondary">
          {relevancePercent}% relevant
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {source.matchCount} {source.matchCount === 1 ? 'Treffer' : 'Treffer'}
        </Typography>
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
        Indexiert: {formatIndexedAt(source.indexedAt)}
      </Typography>
    </Paper>
  )
}
